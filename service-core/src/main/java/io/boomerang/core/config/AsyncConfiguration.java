package io.boomerang.core.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The default {@code @Async} executor, which is also the executor Spring MVC dispatches async
 * returns on. A log stream parks its thread for the life of the stream, so the executor gives every
 * task a virtual thread rather than a slot in a fixed pool.
 */
@Configuration
public class AsyncConfiguration implements AsyncConfigurer {

  // Shutdown waits this long for tasks still running; nothing caps how many run at once.
  private static final long TASK_TERMINATION_TIMEOUT_MS = 10_000;

  @Override
  @Bean(name = "logStreamExecutor")
  public SimpleAsyncTaskExecutor getAsyncExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("logStreamExecutor-");
    executor.setVirtualThreads(true);
    executor.setTaskTerminationTimeout(TASK_TERMINATION_TIMEOUT_MS);
    return executor;
  }

  @Bean
  protected WebMvcConfigurer webMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(getAsyncExecutor());
      }
    };
  }

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return new SimpleAsyncUncaughtExceptionHandler();
  }
}
