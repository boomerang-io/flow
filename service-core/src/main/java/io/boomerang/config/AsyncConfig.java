package io.boomerang.config;

import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig {

  private static final Logger LOGGER = LogManager.getLogger();

  @Bean(name = "asyncTaskExecutor")
  public Executor getTaskExecutor() {
    int maxThreads = 200;
    int maxQueue = 100000;

    LOGGER.info(
        "Creating task executor service: (max concurrent threads: {}) (max queue: {})",
        maxThreads,
        maxQueue);

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(maxThreads);
    executor.setMaxPoolSize(maxThreads);
    executor.setQueueCapacity(maxQueue);

    executor.setThreadNamePrefix("TaskExecutor-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "asyncWorkflowExecutor")
  public Executor getWorkflowExecutor() {
    int maxThreads = 100;
    int maxQueue = 100000;

    LOGGER.info(
        "Creating workflow executor service: (max concurrent threads: {}) (max queue: {})",
        maxThreads,
        maxQueue);

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(maxThreads);
    executor.setMaxPoolSize(maxThreads);
    executor.setQueueCapacity(maxQueue);

    executor.setThreadNamePrefix("WorkflowExecutor-");
    executor.initialize();
    return executor;
  }
}
