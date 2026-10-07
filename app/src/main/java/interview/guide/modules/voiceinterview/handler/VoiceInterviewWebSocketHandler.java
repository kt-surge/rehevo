package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.dto.VoiceClientPlaybackReport;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.VoiceAsrClient;
import interview.guide.modules.voiceinterview.service.VoiceAsrSegmentConsumer;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceLlmClient;
import interview.guide.modules.voiceinterview.service.VoiceTtsClient;
import interview.guide.modules.voiceinterview.service.VoiceFrameTtsClient;
import interview.guide.modules.voiceinterview.service.OrderedPcmTtsPipeline;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.turn.VoiceTurnCoordinator;
import interview.guide.modules.voiceinterview.turn.VoiceTurnEvent;
import interview.guide.modules.voiceinterview.turn.VoiceTurnToken;
import interview.guide.modules.voiceinterview.turn.AsrFinalSegmentBuffer;
import interview.guide.modules.voiceinterview.turn.AsrTranscriptSegment;
import interview.guide.modules.voiceinterview.turn.VoiceTurnOutboundWriter;
import interview.guide.modules.voiceinterview.turn.VoiceTurnResources;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket Handler for Voice Interview
 * 语音面试 WebSocket 处理器
 * <p>
 * Handles real-time bidirectional audio streaming for voice interviews.
 * Processing pipeline: User Audio → STT → LLM → TTS → AI Audio
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private final ObjectMapper objectMapper;
    private final VoiceAsrClient sttService;
    private final VoiceTtsClient ttsService;
    private final VoiceLlmClient llmService;
    private final VoiceInterviewService interviewService;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final ApplicationMetrics applicationMetrics;

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度（与 {@link VoiceInterviewProperties#getUserUtteranceDebounceMs()} 配合）
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    // Activity tracking for pause timeout
    // 活动跟踪（用于暂停超时）
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);  // 4:30
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;            // 5:00
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    private static final int MAX_ASR_READY_RETRY = 2;
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;
    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
        "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
        "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PostConstruct
    void warmupOpeningAudioCache() {
        applicationMetrics.registerVoiceActiveSessionsGauge(sessions);
        voicePipelineExecutor.execute(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null || !opening.isWarmupEnabled()) {
                    return;
                }
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("Opening audio cache warmup skipped: {}", e.getMessage());
            }
        });
    }

    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // Increase message size limits for audio streaming
        // 1 second of PCM audio @ 16kHz, 16-bit = ~32KB raw, ~42KB base64
        // Set limit to 256KB to allow some buffer and multiple messages
        session.setTextMessageSizeLimit(256 * 1024); // 256KB
        session.setBinaryMessageSizeLimit(256 * 1024); // 256KB

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessions.put(sessionId, safeSession);
        sessionStates.put(sessionId, new SessionState());
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            startDashScopeStt(sessionId, safeSession);

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * Create welcome message
     */
    private String createWelcomeMessage() {
        return toJson(Map.of(
            "type", "control",
            "action", "welcome",
            "message", "连接成功，准备开始语音面试",
            "timestamp", System.currentTimeMillis()
        ));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }

    /**
     * Send message to WebSocket session
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("Message sent to session: {}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("Session is closed, cannot send message");
            }
        } catch (Exception e) {
            log.error("Error sending message to session", e);
        }
    }

    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        voicePipelineExecutor.execute(() -> {
            try {
                if (session == null || !session.isOpen()) {
                    return;
                }

                List<String> history = getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    // 已有历史对话（如重连/恢复），不重复开场
                    return;
                }

                VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
                if (sessionEntity == null) {
                    log.warn("Session entity not found when sending opening question: {}", sessionId);
                    return;
                }

                String aiReply = buildOpeningQuestion(sessionEntity);
                if (aiReply == null || aiReply.isBlank()) {
                    return;
                }

                SessionState state = sessionStates.get(sessionId);
                if (state == null) {
                    return;
                }
                // 开场题也进入 Turn 生命周期，保证浏览器的“停止回复”可以获得服务端确认。
                // 它不属于用户回答后的性能口径，音频发送时显式跳过首音频计时。
                VoiceTurnTrace openingTurn = state.beginOpeningTurn(System.nanoTime());
                sendTurnStatus(session, "turn_started", "正在播放开场问题",
                    state.nextOutboundEvent(openingTurn).orElse(null));

                if (!session.isOpen()) {
                    return;
                }

                // 先落库再推前端，确保用户提交时 DB 中已有该条消息
                saveMessage(sessionId, null, aiReply);
                if (!state.isActiveTurn(openingTurn)) {
                    return;
                }
                sendTurnSpeakingIfNeeded(session, state, openingTurn);
                sendTextMessage(session, aiReply, true,
                    state.nextOutboundEvent(openingTurn).orElse(null));

                // 语音随后下发
                byte[] wavAudio = getOpeningWavAudio(aiReply);
                if (wavAudio.length > 0 && session.isOpen() && state.isActiveTurn(openingTurn)) {
                    sendAudio(session, wavAudio, aiReply,
                        state.nextOutboundEvent(openingTurn).orElse(null), false);
                }

                log.info("Opening question sent for session {}", sessionId);
            } catch (Exception e) {
                log.error("Failed to send opening question for session {}", sessionId, e);
            }
        });
    }

    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
            ? opening.getAlgorithmSkills()
            : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
            ? configured
            : DEFAULT_OPENING_QUESTION_BACKEND;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                    sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
            }

            // Update last activity time for pause timeout detection
            // 更新最后活动时间（用于暂停超时检测）
            lastActivityTime.put(sessionId, System.currentTimeMillis());

            switch (type) {
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("Received audio message without data");
                    }
                    break;
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling message for session {}", sessionId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        sessionStates.values().forEach(SessionState::closeTurnResources);
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessions.remove(sessionId);
            SessionState removedState = sessionStates.remove(sessionId);
            if (removedState != null) {
                removedState.cancelActiveTurn();
                removedState.closeTurnResources();
                if (removedState.isProcessing().get()) {
                    applicationMetrics.recordVoiceCancellation(ApplicationMetrics.VoiceCancellationReason.DISCONNECT);
                }
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            lastActivityTime.remove(sessionId);

            // Stop STT transcription
            sttService.stopTranscription(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /** 无会话、append 失败等均可重连 ASR */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    private void startDashScopeStt(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                (VoiceAsrSegmentConsumer) segment -> handleSttSegment(sessionId, segment, true),
                (VoiceAsrSegmentConsumer) segment -> handleSttSegment(sessionId, segment, false),
                () -> {
                    applicationMetrics.recordVoiceAsrReady();
                    sendAsrReady(session);
                },
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendAsrStatus(session, "asr_unavailable", "语音识别暂不可用，请重新连接");
                }
        );

        scheduleAsrReadyCheck(sessionId, session, 0);
    }

    private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
        utteranceMergeScheduler.schedule(
            () -> checkAsrReadyOrRetry(sessionId, session, retryCount),
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            applicationMetrics.recordVoiceAsrReconnect(ApplicationMetrics.Outcome.RETRY);
            restartDashScopeStt(sessionId);
            scheduleAsrReadyCheck(sessionId, session, nextRetry);
            return;
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount);
        sendAsrStatus(session, "asr_unavailable", "语音识别连接超时，请重新连接");
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）
     */
    private void restartDashScopeStt(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                (VoiceAsrSegmentConsumer) segment -> handleSttSegment(sessionId, segment, true),
                (VoiceAsrSegmentConsumer) segment -> handleSttSegment(sessionId, segment, false),
                () -> {
                    applicationMetrics.recordVoiceAsrReady();
                    sendAsrReady(session);
                },
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendAsrStatus(session, "asr_unavailable", "语音识别暂不可用，请重新连接");
                }
        );
    }

    /**
     * Handle user audio message
     * Just send audio to STT transcriber (results come via callback)
     */
    private void handleUserAudio(String sessionId, String base64Audio) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        SessionState state = sessionStates.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            applicationMetrics.recordVoiceDroppedAudio();
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId);
                    applicationMetrics.recordVoiceDroppedAudio();
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restartDashScopeStt(sessionId);
                    applicationMetrics.recordVoiceAsrReconnect(ApplicationMetrics.Outcome.RETRY);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("Error handling user audio for session {}", sessionId, e);
            String errorMessage = getErrorMessage(e);
            sendError(session, errorMessage);
        }
    }

    /**
     * Handle STT result from callback (partial = live; final = committed segment for LLM).
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        handleSttSegment(sessionId, AsrTranscriptSegment.unidentified(recognizedText), isFinalSegment);
    }

    private void handleSttSegment(String sessionId, AsrTranscriptSegment segment, boolean isFinalSegment) {
        String recognizedText = segment.text();
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);

        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId);
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            sendSubtitle(session, state.getMergeBufferPreviewWithPartial(segment), false);
            return;
        }

        // 用户已提交、LLM 正在处理时，丢弃迟到的 STT 定稿段，防止污染下一轮 mergeBuffer
        if (state.isProcessing().get()) {
            log.debug("Discarding late STT final segment during processing for session {}: {}",
                sessionId, recognizedText);
            return;
        }

        log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
        applicationMetrics.recordVoiceFinalSegment();

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(segment);
        sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis(ApplicationMetrics.VoiceTimer.ASR_MERGE_WAIT, mergeWaitMs, ApplicationMetrics.Outcome.SUCCESS);
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length());

        // 排队前登记，使尚未启动的轮次也能取消；处理线程不得创建替代轮次。
        VoiceTurnTrace turnTrace = state.beginTurn(System.nanoTime());
        sendTurnStatus(session, "turn_started", "正在生成面试官回复", state.nextOutboundEvent(turnTrace).orElse(null));
        try {
            voicePipelineExecutor.execute(() -> {
                state.setProcessingThread(Thread.currentThread());
                try {
                    triggerLlmResponse(sessionId, session, state, turnTrace);
                } finally {
                    state.isProcessing().set(false);
                    state.setProcessingThread(null);
                }
            });
        } catch (RejectedExecutionException error) {
            turnTrace.resources.close();
            state.isProcessing().set(false);
            state.failTurn(turnTrace).ifPresent(event ->
                sendTurnStatus(session, "turn_failed", "面试官任务暂无法执行，请重试", event));
            completeTurnTrace(sessionId, turnTrace, ApplicationMetrics.VoiceTurnMode.UNKNOWN,
                ApplicationMetrics.Outcome.FAILURE);
            log.warn("Voice task rejected for session {}", sessionId, error);
        }
    }



    /**
     * Trigger LLM response for completed sentence.
     * When streaming is enabled, uses sentence-level TTS overlap: each detected sentence
     * triggers a concurrent TTS call, so TTS runs in parallel with the rest of LLM generation.
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state,
                                   VoiceTurnTrace turnTrace) {
        ApplicationMetrics.Outcome turnOutcome = ApplicationMetrics.Outcome.FAILURE;
        ApplicationMetrics.VoiceTurnMode turnMode = ApplicationMetrics.VoiceTurnMode.UNKNOWN;
        List<OrderedTtsChunkEmitter> turnEmitters = new ArrayList<>(1);
        List<CompletableFuture<byte[]>> turnTtsFutures = new ArrayList<>();
        try {
            if (!state.isActiveTurn(turnTrace)) {
                return;
            }
            state.aiSpeaking.set(true);
            if (!session.isOpen()) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, text: {}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = getHistory(sessionId);

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                OrderedPcmTtsPipeline framePipeline = null;
                if (voiceInterviewProperties.getFrameTts().isEnabled()) {
                    if (!(ttsService instanceof VoiceFrameTtsClient frameClient)
                        || voiceInterviewProperties.getQwen().getTts().getSampleRate() != 24000
                        || !"pcm".equalsIgnoreCase(voiceInterviewProperties.getQwen().getTts().getFormat())) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "音频帧实验要求 PCM 24000 的帧流客户端");
                    }
                    var limits = voiceInterviewProperties.getFrameTts();
                    framePipeline = new OrderedPcmTtsPipeline(frameClient, voicePipelineExecutor,
                        Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()),
                        Duration.ofSeconds(ttsTimeoutSec), limits.getMaxPendingSentences(),
                        limits.getMaxBufferedBytes(), frame -> sendPcmFrame(session, state, turnTrace, frame));
                    if (!turnTrace.resources.register(framePipeline)) {
                        throw new CancellationException("语音轮次已取消");
                    }
                }
                OrderedPcmTtsPipeline turnFramePipeline = framePipeline;
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled && framePipeline == null
                    ? new OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                    : null;
                if (chunkEmitter != null) {
                    turnEmitters.add(chunkEmitter);
                }
                List<CompletableFuture<byte[]>> ttsFutures = turnTtsFutures;

                DashscopeLlmService.VoiceLlmResponse llmResponse = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                            return;
                        }
                        if (!state.isActiveTurn(turnTrace)) {
                            applicationMetrics.recordVoiceStaleCallbackDropped();
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            turnTrace.markFirstToken();
                            recordTimerSinceNanos(
                                ApplicationMetrics.VoiceTimer.LLM_FIRST_TOKEN,
                                llmStartNanos,
                                ApplicationMetrics.Outcome.SUCCESS
                            );
                        }
                        sendTurnSpeakingIfNeeded(session, state, turnTrace);
                        sendTextMessage(session, partialText, false, state.nextOutboundEvent(turnTrace).orElse(null));
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        if (!state.isActiveTurn(turnTrace)) {
                            applicationMetrics.recordVoiceStaleCallbackDropped();
                            return;
                        }
                        if (turnFramePipeline != null) {
                            turnFramePipeline.submit(sentence);
                            return;
                        }
                        if (chunkEmitter != null) {
                            chunkEmitter.submit(sentence, turnTrace);
                            return;
                        }
                        ttsSemaphore.acquireUninterruptibly();
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, voicePipelineExecutor);
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );
                aiReply = llmResponse.content();
                turnMode = llmResponse.streaming()
                    ? ApplicationMetrics.VoiceTurnMode.STREAM
                    : ApplicationMetrics.VoiceTurnMode.FALLBACK;

                ApplicationMetrics.Outcome llmOutcome = llmResponse.success()
                    ? ApplicationMetrics.Outcome.SUCCESS
                    : ApplicationMetrics.Outcome.FAILURE;
                recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.LLM_DURATION, llmStartNanos, llmOutcome);
                applicationMetrics.recordVoiceLlmCall(llmResponse.streaming(), llmOutcome);
                if (!llmResponse.success()) {
                    log.warn("LLM response failed for session {}: {}", sessionId, aiReply);
                    applicationMetrics.recordVoiceError(ApplicationMetrics.VoiceErrorStage.TURN);
                    if (session.isOpen() && state.isActiveTurn(turnTrace)) {
                        sendError(session, aiReply);
                    }
                    return;
                }
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }
                if (!state.isActiveTurn(turnTrace)) {
                    applicationMetrics.recordVoiceStaleCallbackDropped();
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTurnSpeakingIfNeeded(session, state, turnTrace);
                sendTextMessage(session, aiReply, true, state.nextOutboundEvent(turnTrace).orElse(null));
                saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (turnFramePipeline != null) {
                    long ttsStartNanos = System.nanoTime();
                    turnFramePipeline.finish();
                    int deliveredFrames = turnFramePipeline.completion().toCompletableFuture()
                        .get(ttsTimeoutSec + 12, TimeUnit.SECONDS);
                    if (deliveredFrames == 0) {
                        applicationMetrics.recordVoiceEmptyAudio();
                        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "面试官回复未生成有效音频");
                    }
                    recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.TTS_DURATION, ttsStartNanos,
                        ApplicationMetrics.Outcome.SUCCESS);
                    if (!session.isOpen() || !state.isActiveTurn(turnTrace)) {
                        return;
                    }
                    sendAudioComplete(session, state.nextOutboundEvent(turnTrace).orElse(null));
                } else if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.TTS_DURATION, ttsStartNanos, ApplicationMetrics.Outcome.SUCCESS);
                    if (emittedChunks == 0 && session.isOpen() && state.isActiveTurn(turnTrace)) {
                        log.info("[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                            sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                sendAudio(session, convertPcmToWav(fallbackPcm), aiReply,
                                    state.nextOutboundEvent(turnTrace).orElse(null));
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
                        }
                    }
                    recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.TTS_DURATION, ttsStartNanos, ApplicationMetrics.Outcome.SUCCESS);

                    if (!session.isOpen()) {
                        log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
                        return;
                    }
                    if (!state.isActiveTurn(turnTrace)) {
                        applicationMetrics.recordVoiceStaleCallbackDropped();
                        return;
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && session.isOpen() && state.isActiveTurn(turnTrace)) {
                        log.info("[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                            sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = convertPcmToWav(fallbackPcm);
                                log.info("[Session: {}] Fallback TTS succeeded, WAV size: {} bytes",
                                    sessionId, wavAudio.length);
                                sendAudio(session, wavAudio, aiReply,
                                    state.nextOutboundEvent(turnTrace).orElse(null));
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen() && state.isActiveTurn(turnTrace)) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = convertPcmToWav(mergedPcm);
                            log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                sessionId, pcmChunks.size(), wavAudio.length);
                            sendAudio(session, wavAudio, aiReply,
                                state.nextOutboundEvent(turnTrace).orElse(null));
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                            applicationMetrics.recordVoiceEmptyAudio();
                        }
                    }
                }
            } else {
                turnMode = ApplicationMetrics.VoiceTurnMode.NON_STREAMING;
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.LLM_DURATION, llmStartNanos, ApplicationMetrics.Outcome.SUCCESS);
                applicationMetrics.recordVoiceLlmCall(false, ApplicationMetrics.Outcome.SUCCESS);
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }
                if (!state.isActiveTurn(turnTrace)) {
                    applicationMetrics.recordVoiceStaleCallbackDropped();
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTurnSpeakingIfNeeded(session, state, turnTrace);
                sendTextMessage(session, aiReply, true, state.nextOutboundEvent(turnTrace).orElse(null));
                saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})",
                    sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                recordTimerSinceNanos(ApplicationMetrics.VoiceTimer.TTS_DURATION, ttsStartNanos, ApplicationMetrics.Outcome.SUCCESS);

                if (!session.isOpen()) {
                    return;
                }
                if (!state.isActiveTurn(turnTrace)) {
                    applicationMetrics.recordVoiceStaleCallbackDropped();
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    applicationMetrics.recordVoiceEmptyAudio();
                } else {
                    byte[] wavAudio = convertPcmToWav(aiAudio);
                    sendAudio(session, wavAudio, aiReply,
                        state.nextOutboundEvent(turnTrace).orElse(null));
                }
            }

            state.setAccumulatedText("");
            turnOutcome = ApplicationMetrics.Outcome.SUCCESS;

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            applicationMetrics.recordVoiceError(ApplicationMetrics.VoiceErrorStage.TURN);
            if (session.isOpen() && state.isActiveTurn(turnTrace)) {
                sendError(session, "AI响应失败: " + e.getMessage());
                sendTurnStatus(session, "turn_failed", "面试官回复生成失败",
                    state.failTurn(turnTrace).orElse(null));
            }
        } finally {
            turnTrace.resources.close();
            if (state.isCancelledTurn(turnTrace)) {
                turnOutcome = ApplicationMetrics.Outcome.DISCARDED;
            }
            if (turnOutcome == ApplicationMetrics.Outcome.SUCCESS) {
                sendTurnStatus(session, "turn_completed", "面试官回复完成",
                    state.completeTurn(turnTrace).orElse(null));
            } else {
                state.failTurn(turnTrace).ifPresent(event ->
                    sendTurnStatus(session, "turn_failed", "面试官回复生成失败", event));
                turnEmitters.forEach(OrderedTtsChunkEmitter::abort);
                turnTtsFutures.forEach(future -> future.cancel(true));
            }
            completeTurnTrace(sessionId, turnTrace, turnMode, turnOutcome);
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        }
    }

    /**
     * Convert exception to user-friendly error message
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // Check for specific Aliyun errors
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // Default error message
        return "语音处理失败：" + e.getMessage();
    }

    /**
     * Handle control message (end_interview, start_phase)
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "reconnect_asr":
                voicePipelineExecutor.execute(() -> {
                    WebSocketSession session = sessions.get(sessionId);
                    if (session == null || !session.isOpen()) {
                        return;
                    }
                    if (sttService.isReady(sessionId)) {
                        sendAsrReady(session);
                        return;
                    }
                    sendAsrStatus(session, "asr_reconnecting", "正在重新连接语音识别");
                    applicationMetrics.recordVoiceAsrReconnect(ApplicationMetrics.Outcome.RETRY);
                    try {
                        restartDashScopeStt(sessionId);
                        scheduleAsrReadyCheck(sessionId, session, 0);
                    } catch (Exception error) {
                        log.warn("ASR reconnect failed for session {}", sessionId, error);
                        sendAsrStatus(session, "asr_unavailable", "语音识别暂不可用，请稍后重试");
                    }
                });
                break;
            case "submit":
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionStates.get(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                            state.pendingClientRequestId.set(extractClientRequestId(control));
                        }
                    }
                }
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "cancel":
                cancelActiveTurn(sessionId, extractCancelRequestId(control));
                break;
            case "cancel_acknowledged":
                recordClientCancelAcknowledgement(sessionId, control);
                break;
            case "playback_observed":
                recordClientPlayback(sessionId, control);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    private void cancelActiveTurn(String sessionId, String cancelRequestId) {
        long cancelStartedAtNanos = System.nanoTime();
        SessionState state = sessionStates.get(sessionId);
        WebSocketSession session = sessions.get(sessionId);
        if (state == null || session == null) {
            return;
        }
        var cancelledEvent = state.cancelActiveTurn();
        cancelledEvent.ifPresent(event -> {
            state.closeTurnResources();
            state.rememberCancelRequestId(cancelRequestId);
            applicationMetrics.recordVoiceCancellation(ApplicationMetrics.VoiceCancellationReason.USER);
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(0);
            Thread processingThread = state.getProcessingThread();
            if (processingThread != null) {
                processingThread.interrupt();
            }
            sendTurnStatus(session, "turn_cancelled", "已停止本轮面试官回复", event, cancelRequestId);
            applicationMetrics.recordVoiceCancelAck(System.nanoTime() - cancelStartedAtNanos);
        });
        if (cancelledEvent.isEmpty() && cancelRequestId != null && session.isOpen()) {
            state.rememberCancelRequestId(cancelRequestId);
            sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "cancel_confirmed",
                "cancelRequestId", cancelRequestId,
                "cancelOutcome", "already_terminal",
                "message", "已收到停止播放请求",
                "timestamp", System.currentTimeMillis()
            )));
            applicationMetrics.recordVoiceCancelAck(System.nanoTime() - cancelStartedAtNanos);
        }
    }

    private String extractCancelRequestId(WebSocketControlMessage control) {
        if (control.getData() == null) {
            return null;
        }
        Object requestId = control.getData().get("cancelRequestId");
        if (!(requestId instanceof String value) || !value.matches("[A-Za-z0-9-]{8,64}")) {
            return null;
        }
        return value;
    }

    private void recordClientCancelAcknowledgement(String sessionId, WebSocketControlMessage control) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null || control.getData() == null) {
            return;
        }
        Object requestId = control.getData().get("cancelRequestId");
        Object elapsed = control.getData().get("clientAckLatencyMs");
        if (!(requestId instanceof String value) || !(elapsed instanceof Number latency)) {
            return;
        }
        long elapsedMillis = latency.longValue();
        if (elapsedMillis < 0 || elapsedMillis > 60_000 || !state.consumeCancelRequestId(value)) {
            return;
        }
        applicationMetrics.recordVoiceClientCancelAck(elapsedMillis);
    }

    private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

  private String extractClientRequestId(WebSocketControlMessage control) {
    Object value = control.getData() == null ? null : control.getData().get("clientRequestId");
    return value instanceof String id && id.matches("[A-Za-z0-9-]{8,64}") ? id : null;
  }

  private void recordClientPlayback(String sessionId, WebSocketControlMessage control) {
    SessionState state = sessionStates.get(sessionId);
    var parsed = VoiceClientPlaybackReport.from(control.getData());
    VoiceTurnTrace trace = state == null ? null : state.currentTurn();
    if (parsed.isEmpty() || trace == null || state.isCancelledTurn(trace)
        || !trace.consumePlaybackReport(parsed.get())) {
      applicationMetrics.recordVoiceClientPlaybackRejected();
      return;
    }
    VoiceClientPlaybackReport report = parsed.get();
    applicationMetrics.recordVoiceClientPlayback(report.playbackMode(),
        report.submitToAudioReceivedMs(), report.submitToPlaybackStartMs());
    log.info("[VoiceClientPlayback] sessionId={} turnId={} mode={} receivedMs={} playbackMs={}",
        sessionId, report.turnId(), report.playbackMode(), report.submitToAudioReceivedMs(),
        report.submitToPlaybackStartMs());
  }

    private void sendAudio(WebSocketSession session, byte[] audio, String text) {
        sendAudio(session, audio, text, null);
    }

    private void sendAudio(WebSocketSession session, byte[] audio, String text, VoiceTurnEvent turnEvent) {
        sendAudio(session, audio, text, turnEvent, true);
    }

    private void sendAudio(WebSocketSession session, byte[] audio, String text, VoiceTurnEvent turnEvent,
                           boolean recordFirstAudio) {
        if (!session.isOpen()) {
            return;
        }
        if (recordFirstAudio) {
            recordFirstAudioIfNeeded(session);
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
                audio.length, base64Audio.length());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "audio");
        payload.put("data", base64Audio);
        payload.put("text", text);
        sendTurnPayload(session, payload, turnEvent);
    }

    private void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    private void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendTextMessage(session, text, isFinal, null);
    }

    private void sendTextMessage(WebSocketSession session, String text, boolean isFinal, VoiceTurnEvent turnEvent) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("content", text);
        payload.put("final", isFinal);
        sendTurnPayload(session, payload, turnEvent);
    }

    private void sendTurnStatus(WebSocketSession session, String action, String message, VoiceTurnEvent turnEvent) {
        sendTurnStatus(session, action, message, turnEvent, null);
    }

    private void sendTurnStatus(WebSocketSession session, String action, String message, VoiceTurnEvent turnEvent,
                                String cancelRequestId) {
        if (turnEvent == null || session == null || !session.isOpen()) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "control");
        payload.put("action", action);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        if ("turn_started".equals(action)) {
            SessionState state = sessionStates.get(extractSessionId(session));
            VoiceTurnTrace trace = state == null ? null : state.currentTurn();
            if (trace != null && trace.turnId().equals(turnEvent.turnId()) && trace.clientRequestId != null) {
                payload.put("clientRequestId", trace.clientRequestId);
            }
        }
        if (cancelRequestId != null) {
            payload.put("cancelRequestId", cancelRequestId);
        }
        sendTurnPayload(session, payload, turnEvent);
    }

    /**
     * 将 Turn 事件的编号和 WebSocket 写出放到同一会话临界区。
     *
     * <p>LLM 流和句级 TTS 可能来自不同虚拟线程；若先分配 sequence、再分别竞争
     * WebSocket 写锁，浏览器观察到的写出顺序可能与 sequence 相反。这里在真正写出前
     * 重新分配出站序号，使客户端的重复/倒退过滤不会误丢有效内容。</p>
     */
    private void sendTurnPayload(WebSocketSession session, Map<String, Object> payload, VoiceTurnEvent turnEvent) {
        if (turnEvent == null) {
            sendMessage(session, toJson(payload));
            return;
        }
        SessionState state = sessionStates.get(extractSessionId(session));
        if (state == null) {
            appendTurnEvent(session, payload, turnEvent);
            sendMessage(session, toJson(payload));
            return;
        }
        state.writeOutboundEvent(turnEvent, serializedEvent -> {
            appendTurnEvent(session, payload, serializedEvent);
            sendMessage(session, toJson(payload));
        });
    }

    private void appendTurnEvent(WebSocketSession session, Map<String, Object> payload, VoiceTurnEvent turnEvent) {
        payload.put("turnId", turnEvent.turnId());
        payload.put("eventId", turnEvent.eventId());
        payload.put("sequence", turnEvent.sequence());
        payload.put("turnPhase", turnEvent.phase().name());
        payload.put("sessionId", extractSessionId(session));
        payload.put("eventType", payload.get("type"));
        payload.put("createdAt", System.currentTimeMillis());
    }

    private void sendTurnSpeakingIfNeeded(WebSocketSession session, SessionState state, VoiceTurnTrace turnTrace) {
        if (turnTrace.markSpeaking()) {
            sendTurnStatus(session, "turn_speaking", "正在播放面试官回复",
                state.markTurnSpeaking(turnTrace).orElse(null));
        }
    }

    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    private void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    private void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", action,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }

    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        sendAudioChunk(session, wavAudio, index, isLast, null);
    }

    private void sendPcmFrame(WebSocketSession session, SessionState state, VoiceTurnTrace trace,
                              OrderedPcmTtsPipeline.Frame frame) {
        VoiceTurnEvent event = state.nextOutboundEvent(trace).orElseThrow(
            () -> new CancellationException("语音轮次已终止"));
        if (!session.isOpen()) { throw new CancellationException("语音连接已关闭"); }
        sendTurnSpeakingIfNeeded(session, state, trace);
        state.writeOutboundEvent(event, serialized -> {
            if (!session.isOpen() || !state.isActiveTurn(trace)) {
                throw new CancellationException("迟到音频帧已丢弃");
            }
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "audio_frame");
            payload.put("data", Base64.getEncoder().encodeToString(frame.pcm()));
            payload.put("sentenceIndex", frame.sentenceIndex());
            payload.put("frameIndex", frame.frameIndex());
            payload.put("endOfSentence", frame.endOfSentence());
            payload.put("encoding", "pcm_s16le");
            payload.put("sampleRate", 24000);
            payload.put("channels", 1);
            payload.put("bitsPerSample", 16);
            if (frame.pcm().length > 0) { recordFirstAudioIfNeeded(session); }
            appendTurnEvent(session, payload, serialized);
            sendMessage(session, toJson(payload));
        });
    }

    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast,
                                VoiceTurnEvent turnEvent) {
        if (!session.isOpen()) {
            return;
        }
        recordFirstAudioIfNeeded(session);
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "audio_chunk");
        payload.put("data", base64Audio);
        payload.put("index", index);
        payload.put("isLast", isLast);
        sendTurnPayload(session, payload, turnEvent);
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes", index, isLast, wavAudio.length);
    }

    private void recordFirstAudioIfNeeded(WebSocketSession session) {
        String sessionId = extractSessionId(session);
        SessionState state = sessionStates.get(sessionId);
        if (state == null || !state.markFirstAudioSent()) {
            return;
        }
        VoiceTurnTrace turnTrace = state.currentTurn();
        if (turnTrace != null) {
            turnTrace.markFirstAudio();
        }
        recordTimerSinceNanos(
            ApplicationMetrics.VoiceTimer.FIRST_AUDIO,
            state.getTurnStartedAtNanos(),
            ApplicationMetrics.Outcome.SUCCESS
        );
    }

    private void sendAudioComplete(WebSocketSession session) {
        sendAudioComplete(session, null);
    }

    private void sendAudioComplete(WebSocketSession session, VoiceTurnEvent turnEvent) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "control");
        payload.put("action", "audio_complete");
        payload.put("message", "面试官语音播放完成");
        payload.put("timestamp", System.currentTimeMillis());
        sendTurnPayload(session, payload, turnEvent);
    }

    private class OrderedTtsChunkEmitter {

        private final String sessionId;
        private final WebSocketSession session;
        private final Semaphore ttsSemaphore;
        private final long ttsTimeoutSec;
        private final Map<Integer, CompletableFuture<TurnAudio>> futures = new ConcurrentHashMap<>();
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger emittedChunks = new AtomicInteger();
        private final Object lock = new Object();
        private final CompletableFuture<Integer> completion;
        private volatile int totalChunks = -1;
        private volatile boolean aborted;

        OrderedTtsChunkEmitter(
                String sessionId,
                WebSocketSession session,
                Semaphore ttsSemaphore,
                long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.session = session;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voicePipelineExecutor);
        }

        void submit(String sentence, VoiceTurnTrace turnTrace) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence);
                } finally {
                    ttsSemaphore.release();
                }
            }, voicePipelineExecutor);

            futures.put(index, future.thenApply(audio -> new TurnAudio(turnTrace, audio)));
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        void abort() {
            synchronized (lock) {
                aborted = true;
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
            futures.values().forEach(future -> future.cancel(true));
            completion.cancel(true);
        }

        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                    sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<TurnAudio> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0 && !aborted) {
                            sendAudioComplete(session);
                        }
                        return emitted;
                    }

                    try {
                        TurnAudio turnAudio = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (turnAudio != null && turnAudio.audio() != null && turnAudio.audio().length > 0
                                && session.isOpen()) {
                            SessionState state = sessionStates.get(sessionId);
                            if (state == null || !state.isActiveTurn(turnAudio.turnTrace())) {
                                applicationMetrics.recordVoiceStaleCallbackDropped();
                                return emittedChunks.get();
                            }
                            sendTurnSpeakingIfNeeded(session, state, turnAudio.turnTrace());
                            sendAudioChunk(session, convertPcmToWav(turnAudio.audio()), index, false,
                                state.nextOutboundEvent(turnAudio.turnTrace()).orElse(null));
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        if (aborted) {
                            return emittedChunks.get();
                        }
                        log.warn("[Session: {}] Streaming TTS chunk {} failed: {}",
                            sessionId, index, e.getMessage());
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0 && !aborted) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private CompletableFuture<TurnAudio> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                if (aborted) {
                    return null;
                }
                while (!futures.containsKey(index)) {
                    if (aborted || totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }

        private record TurnAudio(VoiceTurnTrace turnTrace, byte[] audio) {
        }
    }

    private void recordTimerSinceNanos(
        ApplicationMetrics.VoiceTimer timer,
        long startNanos,
        ApplicationMetrics.Outcome outcome
    ) {
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        applicationMetrics.recordVoiceTimer(timer, elapsed, TimeUnit.NANOSECONDS, outcome);
    }

    private void completeTurnTrace(String sessionId, VoiceTurnTrace turnTrace,
                                   ApplicationMetrics.VoiceTurnMode turnMode,
                                   ApplicationMetrics.Outcome turnOutcome) {
        long turnDurationNanos = turnTrace.elapsedSinceStart();
        recordTimerNanos(ApplicationMetrics.VoiceTimer.TURN_DURATION, turnDurationNanos, turnOutcome);
        applicationMetrics.recordVoiceTurn(turnOutcome);

        recordTurnStage(ApplicationMetrics.VoiceTurnStage.ASR_FINAL_TO_SUBMIT, turnMode,
            turnTrace.asrFinalToSubmitNanos(), ApplicationMetrics.Outcome.SUCCESS);
        recordTurnStage(ApplicationMetrics.VoiceTurnStage.LLM_FIRST_TOKEN, turnMode,
            turnTrace.firstTokenLatencyNanos(), ApplicationMetrics.Outcome.SUCCESS);
        recordTurnStage(ApplicationMetrics.VoiceTurnStage.FIRST_AUDIO, turnMode,
            turnTrace.firstAudioLatencyNanos(), ApplicationMetrics.Outcome.SUCCESS);
        applicationMetrics.recordVoiceTurnStage(
            ApplicationMetrics.VoiceTurnStage.TURN_FINISHED,
            turnMode,
            turnDurationNanos,
            turnOutcome
        );

        log.info("voice_turn_trace turnId={} sessionId={} mode={} status={} asrFinalToSubmitMs={} "
                + "llmFirstTokenMs={} firstAudioMs={} turnDurationMs={}",
            turnTrace.turnId(), sessionId, turnMode.value(), turnOutcome.value(),
            turnTrace.asrFinalToSubmitMillis(), turnTrace.firstTokenLatencyMillis(),
            turnTrace.firstAudioLatencyMillis(), TimeUnit.NANOSECONDS.toMillis(turnDurationNanos));
    }

    private void recordTurnStage(ApplicationMetrics.VoiceTurnStage stage,
                                 ApplicationMetrics.VoiceTurnMode mode,
                                 long elapsedNanos,
                                 ApplicationMetrics.Outcome successOutcome) {
        if (elapsedNanos < 0) {
            applicationMetrics.recordVoiceTurnStage(stage, mode, -1, ApplicationMetrics.Outcome.SKIPPED);
            return;
        }
        applicationMetrics.recordVoiceTurnStage(stage, mode, elapsedNanos, successOutcome);
    }

    private void recordTimerNanos(ApplicationMetrics.VoiceTimer timer, long elapsedNanos,
                                  ApplicationMetrics.Outcome outcome) {
        applicationMetrics.recordVoiceTimer(timer, Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS, outcome);
    }

    private void recordTimerMillis(
        ApplicationMetrics.VoiceTimer timer,
        long millis,
        ApplicationMetrics.Outcome outcome
    ) {
        applicationMetrics.recordVoiceTimer(timer, Math.max(0, millis), TimeUnit.MILLISECONDS, outcome);
    }

    /**
     * Scheduled task to check for pause warnings and timeouts
     * Runs every 30 seconds
     * 定时任务：检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // Send warning at 4:30
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // Timeout at 5:00
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned);
            }
        } catch (Exception e) {
            log.error("Error during stale session cleanup", e);
        }
    }

    /**
     * Send pause warning notification
     * 发送暂停警告通知
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "pause_timeout_warning",
                "message", "会话将在30秒后暂停，请继续说话或点击继续",
                "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * Handle pause timeout - save state and disconnect
     * 处理暂停超时 - 保存状态并断开连接
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);

        try {
            if (session != null && session.isOpen()) {
                sendMessage(session, toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout",
                    "message", "会话因超时已暂停,可在历史记录中恢复",
                    "timestamp", System.currentTimeMillis()
                )));
            }

            // 2. Save session state to database
            interviewService.pauseSession(sessionId, "timeout");

            // 3. Close WebSocket connection
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 4. Cleanup - Stop ASR session to prevent resource leak
            sttService.stopTranscription(sessionId);
            sessions.remove(sessionId);
            SessionState pausedState = sessionStates.remove(sessionId);
            if (pausedState != null) {
                pausedState.cancelActiveTurn();
                pausedState.closeTurnResources();
                Thread processingThread = pausedState.getProcessingThread();
                if (processingThread != null) { processingThread.interrupt(); }
            }
            lastActivityTime.remove(sessionId);

            log.info("Session {} paused due to timeout", sessionId);

        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }

    /**
     * Extract session ID from WebSocket URI path
     * Path format: /ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Get chat history for session
     * Load conversation history from database
     */
    private List<String> getHistory(String sessionId) {
        try {
            List<VoiceInterviewMessageEntity> messages = interviewService.getConversationHistory(sessionId);
            List<String> history = new ArrayList<>();
            String pendingAiQuestion = null;

            for (VoiceInterviewMessageEntity msg : messages) {
                String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
                String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

                if (pendingAiQuestion != null) {
                    history.add("面试官：" + pendingAiQuestion);
                    pendingAiQuestion = null;
                    if (userText != null) {
                        history.add("候选人：" + userText);
                    }
                    if (aiText != null) {
                        pendingAiQuestion = aiText;
                    }
                    continue;
                }

                if (aiText != null && userText != null) {
                    history.add("面试官：" + aiText);
                    history.add("候选人：" + userText);
                } else if (aiText != null) {
                    pendingAiQuestion = aiText;
                } else if (userText != null) {
                    history.add("候选人：" + userText);
                }
            }
            if (pendingAiQuestion != null) {
                history.add("面试官：" + pendingAiQuestion);
            }

            log.debug("Loaded {} messages from history for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get session entity from database
     */
    private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId);
            return null;
        }
    }

    /**
     * Save message to database
     */
    private void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }

    /**
     * Convert PCM audio to WAV format
     * Adds 44-byte WAV header to PCM data for browser playback
     *
     * @param pcmData Raw PCM audio data (24kHz, 16-bit, mono)
     * @return WAV formatted audio data
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // Use 24000Hz for Qwen TTS Realtime API
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // Write WAV header directly to avoid stream allocation overhead
        int pos = 0;

        // RIFF header
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt chunk
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // Chunk size
        writeShortLE(wavData, pos, (short) 1); pos += 2; // Audio format (1 = PCM)
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data chunk
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // Copy PCM data
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * Write 32-bit integer in little-endian format
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write 16-bit short in little-endian format
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static final class VoiceTurnTrace {
        private final VoiceTurnToken token;
        private final long turnStartedAtNanos;
        private final long lastAsrFinalAtNanos;
        private final AtomicLong firstTokenAtNanos = new AtomicLong(0);
        private final AtomicLong firstAudioAtNanos = new AtomicLong(0);
        private final AtomicBoolean speaking = new AtomicBoolean(false);
        private final String clientRequestId;
        private final AtomicBoolean playbackReported = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final VoiceTurnResources resources = new VoiceTurnResources();

        private VoiceTurnTrace(VoiceTurnToken token, long turnStartedAtNanos, long lastAsrFinalAtNanos,
                               String clientRequestId) {
            this.token = token;
            this.turnStartedAtNanos = turnStartedAtNanos;
            this.lastAsrFinalAtNanos = lastAsrFinalAtNanos;
            this.clientRequestId = clientRequestId;
        }

        boolean consumePlaybackReport(VoiceClientPlaybackReport report) {
            return !failed.get() && clientRequestId != null && clientRequestId.equals(report.clientRequestId())
                && turnId().equals(report.turnId()) && firstAudioAtNanos.get() > 0
                && playbackReported.compareAndSet(false, true);
        }

        void markFirstToken() {
            firstTokenAtNanos.compareAndSet(0, System.nanoTime());
        }

        void markFirstAudio() {
            firstAudioAtNanos.compareAndSet(0, System.nanoTime());
        }

        String turnId() {
            return token.turnId();
        }

        VoiceTurnToken token() {
            return token;
        }

        boolean markSpeaking() {
            return speaking.compareAndSet(false, true);
        }

        long elapsedSinceStart() {
            return Math.max(0, System.nanoTime() - turnStartedAtNanos);
        }

        long asrFinalToSubmitNanos() {
            return lastAsrFinalAtNanos > 0
                ? Math.max(0, turnStartedAtNanos - lastAsrFinalAtNanos)
                : -1;
        }

        long firstTokenLatencyNanos() {
            return elapsedFrom(firstTokenAtNanos.get());
        }

        long firstAudioLatencyNanos() {
            return elapsedFrom(firstAudioAtNanos.get());
        }

        long asrFinalToSubmitMillis() {
            return toMillisOrMissing(asrFinalToSubmitNanos());
        }

        long firstTokenLatencyMillis() {
            return toMillisOrMissing(firstTokenLatencyNanos());
        }

        long firstAudioLatencyMillis() {
            return toMillisOrMissing(firstAudioLatencyNanos());
        }

        private long elapsedFrom(long eventAtNanos) {
            return eventAtNanos > 0 ? Math.max(0, eventAtNanos - turnStartedAtNanos) : -1;
        }

        private long toMillisOrMissing(long elapsedNanos) {
            return elapsedNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        }
    }

    /**
     * Internal class to hold session state
     */
    private static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM。 */
        private final AsrFinalSegmentBuffer mergeBuffer = new AsrFinalSegmentBuffer();
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 最近一次 ASR 定稿时间，用于衡量定稿到用户提交的交互等待。 */
        private final AtomicLong lastSttFinalAtNanos = new AtomicLong(0);
        /** 当前 Turn 起点，用于用户回答后的首音频延迟；开场 TTS 不写入该性能口径。 */
        private final AtomicLong turnStartedAtNanos = new AtomicLong(0);
        private final AtomicBoolean firstAudioSent = new AtomicBoolean(false);
        private final AtomicReference<VoiceTurnTrace> currentTurn = new AtomicReference<>();
        private final VoiceTurnCoordinator turnCoordinator = new VoiceTurnCoordinator();
        private final VoiceTurnOutboundWriter outboundWriter = new VoiceTurnOutboundWriter();
        /** 仅接受当前取消事件的客户端确认，避免重放或跨 Turn 污染客户端观测指标。 */
        private final AtomicReference<String> pendingCancelRequestId = new AtomicReference<>();
        private final AtomicReference<String> pendingClientRequestId = new AtomicReference<>();
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        void appendFinalSttSegment(String segment) {
            appendFinalSttSegment(AsrTranscriptSegment.unidentified(segment));
        }

        void appendFinalSttSegment(AsrTranscriptSegment segment) {
            mergeBuffer.appendFinal(segment, System.currentTimeMillis());
            markSttActivity();
            lastSttFinalAtNanos.set(System.nanoTime());
        }

        String getMergeBufferPreview() {
            return mergeBuffer.preview();
        }

        void setMergeBufferDirectly(String text) {
            mergeBuffer.replaceWithFinal(text, System.currentTimeMillis());
            lastSttFinalAtNanos.set(System.nanoTime());
        }

        String getMergeBufferPreviewWithPartial(String partial) {
            return mergeBuffer.previewWithPartial(partial);
        }

        String getMergeBufferPreviewWithPartial(AsrTranscriptSegment partial) {
            return mergeBuffer.previewWithPartial(partial);
        }

        String takeMergeBufferAndClear() {
            return mergeBuffer.takeAndClear();
        }

        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        VoiceTurnTrace beginTurn(long startNanos) {
            return beginTurn(startNanos, lastSttFinalAtNanos.get(), pendingClientRequestId.getAndSet(null));
        }

        VoiceTurnTrace beginOpeningTurn(long startNanos) {
            return beginTurn(startNanos, 0, null);
        }

        private VoiceTurnTrace beginTurn(long startNanos, long asrFinalAtNanos, String clientRequestId) {
            turnStartedAtNanos.set(startNanos);
            firstAudioSent.set(false);
            VoiceTurnTrace trace = new VoiceTurnTrace(
                turnCoordinator.begin(), startNanos, asrFinalAtNanos, clientRequestId);
            currentTurn.set(trace);
            return trace;
        }

        boolean isActiveTurn(VoiceTurnTrace turnTrace) {
            return turnTrace != null && turnCoordinator.isActive(turnTrace.token());
        }

        java.util.Optional<VoiceTurnEvent> nextOutboundEvent(VoiceTurnTrace turnTrace) {
            return turnTrace == null ? java.util.Optional.empty() : turnCoordinator.nextEvent(turnTrace.token());
        }

        java.util.Optional<VoiceTurnEvent> markTurnSpeaking(VoiceTurnTrace turnTrace) {
            return turnTrace == null ? java.util.Optional.empty() : turnCoordinator.markSpeaking(turnTrace.token());
        }

        java.util.Optional<VoiceTurnEvent> completeTurn(VoiceTurnTrace turnTrace) {
            return turnTrace == null ? java.util.Optional.empty() : turnCoordinator.complete(turnTrace.token());
        }

        java.util.Optional<VoiceTurnEvent> failTurn(VoiceTurnTrace turnTrace) {
            if (turnTrace == null) {
                return java.util.Optional.empty();
            }
            var event = turnCoordinator.fail(turnTrace.token());
            if (event.isPresent()) {
                turnTrace.failed.set(true);
            }
            return event;
        }

        java.util.Optional<VoiceTurnEvent> cancelActiveTurn() {
            return turnCoordinator.cancelActive();
        }

        void rememberCancelRequestId(String cancelRequestId) {
            pendingCancelRequestId.set(cancelRequestId);
        }

        boolean consumeCancelRequestId(String cancelRequestId) {
            if (cancelRequestId == null) {
                return false;
            }
            while (true) {
                String pending = pendingCancelRequestId.get();
                if (!cancelRequestId.equals(pending)) {
                    return false;
                }
                if (pendingCancelRequestId.compareAndSet(pending, null)) {
                    return true;
                }
            }
        }

        void writeOutboundEvent(VoiceTurnEvent event, java.util.function.Consumer<VoiceTurnEvent> writeOperation) {
            outboundWriter.write(event, writeOperation);
        }

        boolean isCancelledTurn(VoiceTurnTrace turnTrace) {
            return turnTrace != null && turnCoordinator.isCancelled(turnTrace.token());
        }

        boolean markFirstAudioSent() {
            return turnStartedAtNanos.get() > 0 && firstAudioSent.compareAndSet(false, true);
        }

        long getTurnStartedAtNanos() {
            return turnStartedAtNanos.get();
        }

        VoiceTurnTrace currentTurn() {
            return currentTurn.get();
        }

        void closeTurnResources() {
            VoiceTurnTrace trace = currentTurn.get();
            if (trace != null) { trace.resources.close(); }
        }

        long getMergeStartedAt() {
            return mergeBuffer.startedAtOr(System.currentTimeMillis());
        }

        long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        String getAccumulatedText() {
            return accumulatedText.get();
        }

        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        AtomicBoolean isProcessing() {
            return processing;
        }

        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}
