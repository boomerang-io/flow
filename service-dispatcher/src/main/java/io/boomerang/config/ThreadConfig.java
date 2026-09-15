package io.boomerang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ThreadConfig {

  // Shutdown waits this long for dispatched work still running; nothing caps how many run at once.
  private static final long TASK_TERMINATION_TIMEOUT_MS = 10_000;

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    // The two queue polls plus the lease heartbeat, so the heartbeat never waits behind a
    // 30-second long-poll.
    scheduler.setPoolSize(3);
    scheduler.setThreadNamePrefix("scheduler-pool-");
    scheduler.initialize();
    return scheduler;
  }

  /**
   * The executor every {@code @Async} method runs on, one virtual thread per hand-off. It has to
   * exist and has to stay primary: a ThreadPoolTaskScheduler is itself a TaskExecutor, and Spring
   * resolves {@code @Async} by asking for the one TaskExecutor bean, so while the scheduler above
   * was the module's only one every hand-off landed on its three threads - a dispatched TaskRun
   * holds one for the whole life of the Task, behind the queue polls and the lease heartbeat.
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
