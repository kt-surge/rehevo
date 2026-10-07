package interview.guide.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    private int batchSize = 8;
    /**
     * 同一报告允许同时调用的批次上限。默认 1 是基于本地 Provider 配额故障的保守值；
     * 只有受控实验确认不放大限流后才提高。
     */
    private int maxConcurrentBatches = 1;
    /** 跨报告共用执行器的有界排队容量，满载批次会明确失败而非无限堆积。 */
    private int batchExecutorQueueCapacity = 16;
    private int failedItemRetryLimit = 3;
    /** 整份报告允许消耗的模型调用预算；到期后剩余题以评估失败返回，不再发起新调用。 */
    private int requestDeadlineSeconds = 180;
    private String systemPromptPath = "classpath:prompts/interview-evaluation-system.st";
    private String userPromptPath = "classpath:prompts/interview-evaluation-user.st";
    private String summarySystemPromptPath = "classpath:prompts/interview-evaluation-summary-system.st";
    private String summaryUserPromptPath = "classpath:prompts/interview-evaluation-summary-user.st";
}
