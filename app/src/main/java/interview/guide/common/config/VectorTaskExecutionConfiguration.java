package interview.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class VectorTaskExecutionConfiguration {
  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler vectorRecoveryTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("vector-recovery-scan-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  @Bean(name = "vectorExecutionTaskScheduler")
  public ThreadPoolTaskScheduler vectorExecutionTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("vector-execution-heartbeat-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }
}
