package io.boomerang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ThreadConfig {

  // Shutdown waits this long for a runtime-object delete still in its grace.
  private static final long TASK_TERMINATION_TIMEOUT_MS = 10_000;

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    // The workspace and task-runtime reconcilers, so neither waits behind the other. The queue
    // polls and the lease heartbeat run on the dispatcher SDK's own threads.
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("scheduler-pool-");
    scheduler.initialize();
    return scheduler;
  }

  /**
   * The executor every {@code @Async} method runs on, one virtual thread per hand-off. It has to
   * exist and has to stay primary: a ThreadPoolTaskScheduler is itself a TaskExecutor, and Spring
   * resolves {@code @Async} by asking for the one TaskExecutor bean, so without it every hand-off
   * - a runtime-object delete, grace included - would land on the reconcilers' threads.
   */
  @Bean
  @Primary
  public SimpleAsyncTaskExecutor applicationTaskExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("dispatch-");
    executor.setVirtualThreads(true);
    executor.setTaskTerminationTimeout(TASK_TERMINATION_TIMEOUT_MS);
    return executor;
  }
}
