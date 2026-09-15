package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.enums.TaskDeletion;
import io.boomerang.common.model.TaskRun;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * The delete of a Task's runtime object waits out a one-second grace and must not charge it to the
 * thread that just ran the Task. The delete is only genuinely off that thread when it is reached
 * through the {@code @Async} proxy - a self-call is not intercepted, and the annotation then does
 * nothing at all.
 */
class TaskDeleteAsyncTest {

  private static final long DELETE_GRACE_MS = 1000;

  private final TaskExecutor executor = mock(TaskExecutor.class);

  @Test
  void executeReturnsBeforeTheDeleteRuns() throws Exception {
    CountDownLatch deleted = new CountDownLatch(1);
    AtomicReference<Thread> deleteThread = new AtomicReference<>();
    when(executor.watch(any(), any())).thenReturn(List.of());
    doAnswer(
            invocation -> {
              deleteThread.set(Thread.currentThread());
              deleted.countDown();
              return null;
            })
        .when(executor)
        .delete(any());

    try (AnnotationConfigApplicationContext context = context()) {
      TaskService taskService = context.getBean(TaskService.class);
      Thread caller = Thread.currentThread();

      long start = System.nanoTime();
      taskService.execute(taskRun());
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertThat(elapsedMs).isLessThan(DELETE_GRACE_MS);
      assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(deleteThread.get()).isNotSameAs(caller);
    }
  }

  private AnnotationConfigApplicationContext context() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "task-service",
                Map.of(
                    "kube.task.deletion",
                    "Never",
                    "kube.task.timeout",
                    "60",
                    "flow.dispatcher.ai.image",
                    "boomerangio/flow-task-ai:latest")));
    context.registerBean("taskRuntimeExecutor", TaskExecutor.class, () -> executor);
    context.register(AsyncTestConfig.class, TaskImageResolver.class, TaskService.class);
    context.refresh();
    return context;
  }

  private static TaskRun taskRun() {
    TaskRun task = new TaskRun();
    task.setId("task-1");
    task.setTimeout(1L);
    task.getSpec().setImage("boomerangio/task:latest");
    task.getSpec().setDeletion(TaskDeletion.Always);
    return task;
  }

  @Configuration
  @EnableAsync
  static class AsyncTestConfig {

    @Bean
    static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
      return new PropertySourcesPlaceholderConfigurer();
    }
  }
}
