package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 分析
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;

    public AnalyzeStreamConsumer(
        RedisService redisService,
        ApplicationMetrics applicationMetrics,
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository
    ) {
        super(redisService, applicationMetrics);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
    }

    record AnalyzePayload(Long resumeId, String content) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (resumeIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected boolean shouldSkip(AnalyzePayload payload) {
        return resumeRepository.findById(payload.resumeId())
            .map(resume -> resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected boolean markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
        return true;
    }

    @Override
    protected BusinessResult processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return BusinessResult.SKIPPED;
        }

        ResumeAnalysisResponse analysis = gradingService.analyzeResume(payload.content());
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return BusinessResult.SKIPPED;
        }
        persistenceService.saveAnalysis(resume, analysis);
        return BusinessResult.COMPLETED;
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected boolean markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
        return true;
    }

    @Override
    protected RetryResult retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);
            return RetryResult.ENQUEUED;

        } catch (Exception e) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
            return RetryResult.FAILED;
        }
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
      resumeRepository.findById(resumeId).ifPresent(resume -> {
        resume.setAnalyzeStatus(status);
        resume.setAnalyzeError(error);
        resumeRepository.save(resume);
        log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
      });
    }

}
