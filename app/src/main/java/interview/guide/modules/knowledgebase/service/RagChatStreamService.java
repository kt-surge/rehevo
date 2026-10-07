package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagGenerationState;
import interview.guide.modules.knowledgebase.model.RagStreamEventDTO;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** 单实例流资源编排。数据库终态另有行锁；多实例停止需将请求路由到流所属实例。 */
@Slf4j
@Service
public class RagChatStreamService {
  private final RagChatSessionService sessions;
  private final RagChatStreamProperties properties;
  private final ConcurrentHashMap<Long, Turn> active = new ConcurrentHashMap<>();
  private final Semaphore capacity;

  public RagChatStreamService(RagChatSessionService sessions, RagChatStreamProperties properties) {
    this.sessions = sessions;
    this.properties = properties;
    this.capacity = new Semaphore(properties.getMaxActiveSessions());
  }

  public Flux<RagStreamEventDTO> stream(Long sessionId, String question) {
    return Flux.defer(() -> {
      if (!capacity.tryAcquire()) {
        return Flux.error(new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "同时生成的回答过多，请稍后重试"));
      }
      Turn turn = new Turn(sessionId);
      if (active.putIfAbsent(sessionId, turn) != null) {
        capacity.release();
        return Flux.error(new BusinessException(ErrorCode.BAD_REQUEST, "本会话已有回答正在生成"));
      }
      try {
        turn.messageId = sessions.prepareStreamMessage(sessionId, question);
      } catch (Exception error) {
        release(turn);
        return Flux.error(error);
      }
      Flux<String> answer = Mono.fromCallable(() -> sessions.getStreamAnswer(sessionId, question))
          .subscribeOn(Schedulers.boundedElastic())
          .flatMapMany(result -> {
            synchronized (turn) {
              if (turn.terminal != null) {
                return Flux.empty();
              }
              turn.evidence = result.evidence();
            }
            return result.content();
          })
          .takeUntilOther(Mono.delay(properties.deadline()).flatMap(ignored ->
              Mono.error(new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "回答生成超时"))))
          .takeUntilOther(turn.cancel.asMono());
      Flux<RagStreamEventDTO> events = answer.materialize().concatMap(signal -> {
        if (signal.isOnNext()) {
          synchronized (turn) {
            if (turn.terminal != null) {
              return Mono.empty();
            }
            String chunk = signal.get();
            if (turn.content.length() + chunk.length() > properties.getMaxAnswerCharacters()) {
              return Mono.error(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "回答超出长度限制"));
            }
            turn.content.append(chunk);
            return Mono.just(RagStreamEventDTO.delta(turn.messageId, chunk));
          }
        }
        return Mono.fromCallable(() -> finish(turn,
                signal.isOnError() ? RagGenerationState.FAILED : RagGenerationState.COMPLETED,
                signal.getThrowable()))
            .subscribeOn(Schedulers.boundedElastic());
      }, 1).onErrorResume(error -> Mono.fromCallable(() -> finish(turn, RagGenerationState.FAILED, error))
          .subscribeOn(Schedulers.boundedElastic()));
      Flux<RagStreamEventDTO> heartbeat = Flux.interval(Duration.ofSeconds(properties.getHeartbeatSeconds()))
          .map(ignored -> new RagStreamEventDTO("heartbeat", turn.messageId, null, null, null, null));
      return Flux.concat(Mono.just(RagStreamEventDTO.start(turn.messageId)),
              events.mergeWith(heartbeat).takeUntil(event -> "terminal".equals(event.event())))
          .doFinally(signal -> {
            if (signal == SignalType.CANCEL) {
              // 断连没有下游可发 terminal，仍通过相同入口保存收到的正文。
              Mono.fromRunnable(() -> finish(turn, RagGenerationState.CANCELLED, null))
                  .subscribeOn(Schedulers.boundedElastic())
                  .doFinally(ignored -> release(turn))
                  .subscribe(ignored -> {}, error -> log.error("保存断连终态失败: messageId={}", turn.messageId, error));
            } else {
              release(turn);
            }
          });
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public RagStreamEventDTO cancel(Long sessionId, Long messageId) {
    Turn turn = active.get(sessionId);
    if (turn == null || !messageId.equals(turn.messageId)) {
      return sessions.getStreamTerminal(sessionId, messageId);
    }
    RagStreamEventDTO result = finish(turn, RagGenerationState.CANCELLED, null);
    turn.cancel.tryEmitValue(true);
    return result;
  }

  public void deleteSession(Long sessionId) {
    Turn turn = active.get(sessionId);
    if (turn != null && turn.messageId != null) {
      cancel(sessionId, turn.messageId);
    }
    sessions.deleteSession(sessionId);
  }

  private RagStreamEventDTO finish(Turn turn, RagGenerationState requested, Throwable error) {
    synchronized (turn) {
      if (turn.terminal != null) {
        return turn.terminal;
      }
      RagGenerationState state = requested;
      String code = null;
      String message = null;
      if (state == RagGenerationState.COMPLETED && turn.content.isEmpty()) {
        state = RagGenerationState.FAILED;
        code = "EMPTY_ANSWER";
        message = "未收到回答，请重试";
      } else if (state == RagGenerationState.FAILED) {
        boolean timeout = error instanceof BusinessException business
            && ErrorCode.AI_SERVICE_TIMEOUT.getCode().equals(business.getCode());
        code = timeout ? "GENERATION_TIMEOUT" : "GENERATION_FAILED";
        message = timeout ? "回答生成超时，已保留收到的内容" : "回答生成失败，已保留收到的内容";
        log.warn("RAG 生成终止: sessionId={}, messageId={}, code={}", turn.sessionId, turn.messageId, code, error);
      } else if (state == RagGenerationState.CANCELLED) {
        code = "USER_CANCELLED";
        message = "已停止，回答未完成";
      }
      // 先冻结，后保存；持锁期间到达的正文或其他终态不能覆盖该决定。
      turn.terminal = RagStreamEventDTO.terminal(turn.messageId, state, code, message);
      try {
        RagGenerationState saved = sessions.finishStreamMessage(turn.messageId, turn.content.toString(),
            turn.evidence, state, code);
        if (saved == null) {
          turn.terminal = RagStreamEventDTO.terminal(turn.messageId, RagGenerationState.CANCELLED,
              "MESSAGE_REMOVED", "会话已删除");
        } else if (saved != state) {
          turn.terminal = RagStreamEventDTO.terminal(turn.messageId, saved, "ALREADY_TERMINAL", "回答已经结束");
        }
      } catch (Exception failure) {
        log.error("保存 RAG 终态失败: sessionId={}, messageId={}", turn.sessionId, turn.messageId, failure);
        turn.terminal = RagStreamEventDTO.terminal(turn.messageId, RagGenerationState.FAILED,
            "SAVE_FAILED", "回答保存失败，当前内容未保存，请保留本页内容后重试");
      }
      return turn.terminal;
    }
  }

  private void release(Turn turn) {
    if (active.remove(turn.sessionId, turn)) {
      capacity.release();
    }
  }

  int activeCount() {
    return active.size();
  }

  private static final class Turn {
    private final Long sessionId;
    private volatile Long messageId;
    private final StringBuilder content = new StringBuilder();
    private final Sinks.One<Boolean> cancel = Sinks.one();
    private List<QueryResponse.RetrievalEvidence> evidence = List.of();
    private RagStreamEventDTO terminal;

    private Turn(Long sessionId) {
      this.sessionId = sessionId;
    }
  }
}
