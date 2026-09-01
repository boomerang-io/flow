package io.boomerang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ThreadConfig {

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
}
