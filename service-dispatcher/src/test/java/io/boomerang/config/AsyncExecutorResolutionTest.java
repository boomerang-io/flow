package io.boomerang.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Pins which executor {@code @Async} work runs on. A ThreadPoolTaskScheduler is itself a
 * TaskExecutor, so leaving it as the module's only one puts every {@code @Async} hand-off - a
 * runtime-object delete among them, which waits out a grace first - on the scheduler threads,
 * behind the reconcilers.
 */
class AsyncExecutorResolutionTest {

  @Test
  void asyncWorkRunsOnVirtualThreadsNotTheSchedulerPool() throws Exception {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(
            ThreadConfig.class, AsyncEnabled.class, RecordingBean.class)) {

      Thread thread =
          context.getBean(RecordingBean.class).currentThread().get(10, TimeUnit.SECONDS);

      assertThat(thread.isVirtual()).isTrue();
      assertThat(thread.getName()).startsWith("dispatch-");
    }
  }

  @Configuration
  @EnableAsync
  static class AsyncEnabled {}

  public static class RecordingBean {

    @Async
    public CompletableFuture<Thread> currentThread() {
      return CompletableFuture.completedFuture(Thread.currentThread());
    }
  }
}
