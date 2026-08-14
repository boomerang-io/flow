package io.boomerang.config;

import java.util.concurrent.Executor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class AsyncConfiguration implements AsyncConfigurer {

  @Override
  @Bean(name = "logStreamExecutor")
  public Executor getAsyncExecutor() {

    int maxThreads = 200;
    int maxQueue = 100000;

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(maxThreads);
    executor.setMaxPoolSize(maxThreads);
    executor.setQueueCapacity(maxQueue);

    executor.setThreadNamePrefix("logStreamExecutor-");
    
    return executor;
  }

  @Bean
  protected WebMvcConfigurer webMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(getTaskExecutor());
      }
    };
  }

  @Bean
  protected ConcurrentTaskExecutor getTaskExecutor() {
    return new ConcurrentTaskExecutor(this.getAsyncExecutor());
  }

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return new SimpleAsyncUncaughtExceptionHandler();
  }

}
