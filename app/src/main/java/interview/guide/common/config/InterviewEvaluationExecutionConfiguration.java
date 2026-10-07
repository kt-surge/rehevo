package interview.guide.common.config;

import interview.guide.common.evaluation.InterviewEvaluationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 评估模型调用使用独立、有界的执行器，避免报告批次抢占 Web 请求线程或无限排队。
 */
@Configuration
public class InterviewEvaluationExecutionConfiguration {

    @Bean(name = "interviewEvaluationExecutor")
    public ThreadPoolTaskExecutor interviewEvaluationExecutor(
            InterviewEvaluationProperties properties) {
        int concurrency = Math.max(1, properties.getMaxConcurrentBatches());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(0, properties.getBatchExecutorQueueCapacity()));
        executor.setThreadNamePrefix("interview-evaluation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
