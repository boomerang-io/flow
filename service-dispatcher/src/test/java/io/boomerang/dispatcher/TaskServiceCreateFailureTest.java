package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.sdk.model.TaskDeletion;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a refused create is reported: a namespace quota is the cluster being full, which the engine
 * requeues; any other refusal is the task's own problem and fails it.
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
}
