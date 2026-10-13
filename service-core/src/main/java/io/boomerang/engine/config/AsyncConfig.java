package io.boomerang.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * The engine's two {@code @Async} executors. Both give every task its own virtual thread: a task
 * transition blocks on Mongo and on the dispatcher, so a fixed pool caps concurrent transitions at
 * its size and queues the rest behind them.
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig {

  // Shutdown waits this long for transitions still running; nothing caps how many run at once.
  private static final long TASK_TERMINATION_TIMEOUT_MS = 10_000;

  @Bean(name = "asyncTaskExecutor")
  public SimpleAsyncTaskExecutor getTaskExecutor() {
    return virtualThreadExecutor("TaskExecutor-");
  }

  @Bean(name = "asyncWorkflowExecutor")
  public SimpleAsyncTaskExecutor getWorkflowExecutor() {
    return virtualThreadExecutor("WorkflowExecutor-");
  }

  private static SimpleAsyncTaskExecutor virtualThreadExecutor(String threadNamePrefix) {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
    executor.setVirtualThreads(true);
    executor.setTaskTerminationTimeout(TASK_TERMINATION_TIMEOUT_MS);
    return executor;
  }
}
