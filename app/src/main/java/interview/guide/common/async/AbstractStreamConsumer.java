package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final ApplicationMetrics applicationMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong streamBacklog = new AtomicLong();
    private final AtomicLong streamPending = new AtomicLong();
    private final AtomicLong oldestPendingIdleMillis = new AtomicLong();
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this(redisService, new ApplicationMetrics(null));
    }

    protected AbstractStreamConsumer(RedisService redisService, ApplicationMetrics applicationMetrics) {
        this.redisService = redisService;
        this.applicationMetrics = applicationMetrics;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        applicationMetrics.registerStreamGauges(
            streamKey(), streamBacklog, streamPending, oldestPendingIdleMillis
        );
        refreshStreamMetrics();
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("{} consumer stop requested: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            } finally {
                refreshStreamMetrics();
            }
        }
    }

    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        if (!running.get()) {
            log.info("Consumer shutting down; retain unread batch message: messageId={}", messageId);
            return;
        }
        long startNanos = System.nanoTime();
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId);
            applicationMetrics.recordStreamTask(streamKey(), ApplicationMetrics.Outcome.DISCARDED,
                System.nanoTime() - startNanos);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            applicationMetrics.recordStreamTask(streamKey(), ApplicationMetrics.Outcome.DISCARDED,
                System.nanoTime() - startNanos);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                applicationMetrics.recordStreamTask(streamKey(), ApplicationMetrics.Outcome.SKIPPED,
                    System.nanoTime() - startNanos);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (shouldRetainPending(payload)) {
              log.warn("{} message requires migration; retain Pending: messageId={}", taskDisplayName(), messageId);
              return;
            }
            if (!markProcessing(payload)) {
              ackMessage(messageId);
              applicationMetrics.recordStreamTask(streamKey(), ApplicationMetrics.Outcome.SKIPPED,
                  System.nanoTime() - startNanos);
              return;
            }
            BusinessResult business = processBusiness(payload);
            if (!running.get() && business != BusinessResult.COMPLETED) {
              log.info("Consumer shutting down; retain unfinished business message: messageId={}", messageId);
              return;
            }
            if (business != BusinessResult.COMPLETED) {
              ackMessage(messageId);
              applicationMetrics.recordStreamTask(streamKey(), switch (business) {
                case SKIPPED -> ApplicationMetrics.Outcome.SKIPPED;
                case DEFERRED -> ApplicationMetrics.Outcome.DEFERRED;
                case FAILED -> ApplicationMetrics.Outcome.FAILURE;
                case COMPLETED -> ApplicationMetrics.Outcome.SUCCESS;
              }, System.nanoTime() - startNanos);
              return;
            }
            markCompleted(payload);
            ackMessage(messageId);
            applicationMetrics.recordStreamTask(streamKey(),
                retryCount > 0 ? ApplicationMetrics.Outcome.RECOVERED : ApplicationMetrics.Outcome.SUCCESS,
                System.nanoTime() - startNanos);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            // InterruptedException 会清掉线程标记；用生命周期状态判断停止，保留未完成消息。
            if (!running.get()) {
                log.info("Consumer shutting down; retain failed Pending message: messageId={}", messageId);
                return;
            }
            if (shouldSkip(payload)) {
              ackMessage(messageId);
              applicationMetrics.recordStreamTask(streamKey(), ApplicationMetrics.Outcome.SKIPPED,
                  System.nanoTime() - startNanos);
              log.info("Failed task is now obsolete or deleted; skip: {}", payloadIdentifier(payload));
              return;
            }
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                RetryResult retryResult = retryMessage(payload, retryCount + 1);
                applicationMetrics.recordStreamTask(streamKey(),
                    switch (retryResult) {
                      case ENQUEUED -> ApplicationMetrics.Outcome.RETRY;
                      case FAILED -> ApplicationMetrics.Outcome.FAILURE;
                      case SKIPPED -> ApplicationMetrics.Outcome.SKIPPED;
                    },
                    System.nanoTime() - startNanos);
            } else {
                boolean failed = markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
                applicationMetrics.recordStreamTask(streamKey(), failed
                        ? ApplicationMetrics.Outcome.FAILURE : ApplicationMetrics.Outcome.SKIPPED,
                    System.nanoTime() - startNanos);
            }
            ackMessage(messageId);
        }
    }

    private void refreshStreamMetrics() {
        RedisService.StreamGroupMetrics metrics = redisService.streamGroupMetrics(streamKey(), groupName());
        streamBacklog.set(metrics.backlog());
        streamPending.set(metrics.pending());
        oldestPendingIdleMillis.set(metrics.oldestPendingIdleMillis());
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected boolean shouldRetainPending(T payload) { return false; }

    protected abstract boolean markProcessing(T payload);

    protected enum BusinessResult { COMPLETED, SKIPPED, DEFERRED, FAILED }

    protected abstract BusinessResult processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract boolean markFailed(T payload, String error);

    /** FAILED 只在失败终态已经持久化后返回；持久化异常应抛出并保留原 Pending。 */
    protected enum RetryResult { ENQUEUED, FAILED, SKIPPED }

    protected abstract RetryResult retryMessage(T payload, int retryCount);
}
