package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.sdk.model.TaskDeletion;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a refused create or a failed watch is reported: a namespace quota is the cluster being full
 * and an unreachable API server is the cluster being away, both of which the engine requeues; any
 * other refusal is the task's own problem and fails it.
 */
class TaskServiceCreateFailureTest {

  private final TaskExecutor executor = mock(TaskExecutor.class);
  private final TaskImageResolver imageResolver = mock(TaskImageResolver.class);
  private final TaskService taskService = new TaskService(executor, imageResolver);

  @BeforeEach
  void setUp() {
    when(imageResolver.image(any())).thenReturn("alpine:3.19");
  }

  private static TaskRun task() {
    TaskRun task = new TaskRun();
    task.setId("task-1");
    task.setTimeout(30L);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setDeletion(TaskDeletion.Never);
    task.setSpec(spec);
    return task;
  }

  private String reasonWhenCreateThrows(KubernetesClientException refusal) throws Exception {
    doThrow(refusal).when(executor).create(any(), any());
    return assertThrows(TaskExecutionException.class, () -> taskService.execute(task()))
        .getStatusReason();
  }

  private String reasonWhenWatchThrows(KubernetesClientException failure) throws Exception {
    doThrow(failure).when(executor).watch(any(), any());
    return assertThrows(TaskExecutionException.class, () -> taskService.execute(task()))
        .getStatusReason();
  }

  @Test
  void anAiTaskWithNoImageIsRefusedAsNoTaskImageLikeAnyOtherType() throws Exception {
    TaskRun task = task();
    task.setType(TaskType.ai);
    task.setName("summarise");
    when(imageResolver.image(any())).thenReturn("");

    TaskExecutionException refusal =
        assertThrows(TaskExecutionException.class, () -> taskService.execute(task));

    assertEquals("DispatchError", refusal.getStatusReason());
    assertTrue(refusal.getMessage().startsWith("NO_TASK_IMAGE"));
    // The refusal names the task, not a Java class.
    assertTrue(refusal.getMessage().contains("summarise (task-1)"), refusal.getMessage());
    verify(executor, never()).create(any(), any());
  }

  @Test
  void anAiTaskDeclaringAnImageOutsideTheAllowedPrefixesIsRefusedBeforeItRuns() throws Exception {
    TaskRun task = task();
    task.setType(TaskType.ai);
    task.getSpec().setImage("evil:latest");
    when(imageResolver.image(any())).thenReturn("evil:latest");
    when(imageResolver.allowedAiImage("evil:latest")).thenReturn(false);

    TaskExecutionException refusal =
        assertThrows(TaskExecutionException.class, () -> taskService.execute(task));

    assertEquals("ImageNotAllowed", refusal.getStatusReason());
    assertTrue(refusal.getMessage().contains("evil:latest"));
    assertTrue(refusal.getMessage().contains("flow.dispatcher.ai.image-prefixes"));
    verify(executor, never()).create(any(), any());
  }

  @Test
  void onlyAnAiTaskIsHeldToTheImagePrefixes() throws Exception {
    TaskRun task = task();
    task.setType(TaskType.custom);
    task.getSpec().setImage("evil:latest");
    when(imageResolver.allowedAiImage(any())).thenReturn(false);

    taskService.execute(task);

    verify(executor).create(any(), any());
  }

  @Test
  void aQuotaRefusalIsReportedAsExceededQuota() throws Exception {
    assertEquals(
        "ExceededQuota",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "jobs.batch \"task-1\" is forbidden: exceeded quota: compute, requested:"
                    + " count/jobs.batch=1, used: count/jobs.batch=10, limited: count/jobs.batch=10",
                403,
                null)));
  }

  @Test
  void anAdmissionWebhookDenialIsStillAdmissionDenied() throws Exception {
    assertEquals(
        "AdmissionDenied",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "admission webhook \"policy.example.com\" denied the request", 403, null)));
  }

  @Test
  void aClientErrorWithNoMessageIsADispatchError() throws Exception {
    // An API server too loaded to answer can fail a request with no message at all.
    assertEquals(
        "DispatchError", reasonWhenCreateThrows(new KubernetesClientException((String) null)));
  }

  @Test
  void anyOtherForbiddenIsADispatchError() throws Exception {
    assertEquals(
        "DispatchError",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "jobs.batch is forbidden: User \"system:serviceaccount:flow:dispatcher\" cannot"
                    + " create resource",
                403,
                null)));
  }

  @Test
  void anApiServerErrorOnCreateIsClusterUnavailable() throws Exception {
    for (int code : new int[] {500, 502, 503, 504}) {
      assertEquals(
          "ClusterUnavailable",
          reasonWhenCreateThrows(new KubernetesClientException("the server is unavailable", code, null)),
          "HTTP " + code);
    }
  }

  @Test
  void aTimeoutOrRefusedConnectionOnCreateIsClusterUnavailable() throws Exception {
    assertEquals(
        "ClusterUnavailable",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "Operation: [create] failed", new SocketTimeoutException("Read timed out"))));
    assertEquals(
        "ClusterUnavailable",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "Operation: [create] failed", new ConnectException("Connection refused"))));
  }

  @Test
  void anIoFailureBehindAWrappingExceptionIsClusterUnavailable() throws Exception {
    assertEquals(
        "ClusterUnavailable",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "Operation: [create] failed",
                new IllegalStateException("send failed", new HttpTimeoutException("request timed out")))));
  }

  @Test
  void anApiServerErrorOrTimeoutWhileWatchingIsClusterUnavailable() throws Exception {
    assertEquals(
        "ClusterUnavailable",
        reasonWhenWatchThrows(new KubernetesClientException("the server is unavailable", 503, null)));
    assertEquals(
        "ClusterUnavailable",
        reasonWhenWatchThrows(
            new KubernetesClientException(
                "Operation: [get] failed", new SocketTimeoutException("Read timed out"))));
  }

  @Test
  void aClientErrorIsNeverClusterUnavailable() throws Exception {
    for (int code : new int[] {400, 401, 404, 409, 422, 429}) {
      assertEquals(
          "DispatchError",
          reasonWhenCreateThrows(new KubernetesClientException("the server refused it", code, null)),
          "HTTP " + code);
    }
  }

  @Test
  void anAdmissionWebhookDenialKeepsItsReasonWhateverTheStatus() throws Exception {
    assertEquals(
        "AdmissionDenied",
        reasonWhenCreateThrows(
            new KubernetesClientException(
                "Internal error occurred: admission webhook \"policy.example.com\" failed", 500, null)));
  }

  @Test
  void aClientExceptionWithNeitherStatusNorIoCauseIsADispatchError() throws Exception {
    assertEquals(
        "DispatchError",
        reasonWhenCreateThrows(
            new KubernetesClientException("bad config", new IllegalArgumentException("no token"))));
  }
}
