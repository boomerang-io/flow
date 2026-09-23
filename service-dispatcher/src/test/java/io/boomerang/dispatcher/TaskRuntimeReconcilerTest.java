package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.kube.KubeHelperService;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunBuilder;
import io.fabric8.tekton.v1.TaskRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the TTL contract for the Tekton executor: a tick deletes only this dispatcher's own
 * TaskRuns (product and tier labels), and only those with a terminal {@code Succeeded} condition
 * whose {@code completionTime} has outlived the TTL. Anything running, recent, or foreign stays.
 */
@EnableKubernetesMockClient(crud = true)
class TaskRuntimeReconcilerTest {

  private static final Map<String, String> DISPATCHER_LABELS =
      Map.of("boomerang.io/product", "bmrg-flow", "boomerang.io/tier", "task");

  KubernetesClient client;

  private static TaskRun taskRun(
      String name, Map<String, String> labels, String conditionStatus, Instant completionTime) {
    TaskRunBuilder builder =
        new TaskRunBuilder().withNewMetadata().withName(name).withLabels(labels).endMetadata();
    if (conditionStatus != null) {
      Condition condition = new Condition();
      condition.setType("Succeeded");
      condition.setStatus(conditionStatus);
      TaskRunStatus status = new TaskRunStatus();
      status.setConditions(List.of(condition));
      if (completionTime != null) {
        status.setCompletionTime(completionTime.toString());
      }
      builder.withStatus(status);
    }
    return builder.build();
  }

  private TaskRuntimeReconciler reconciler() {
    KubeHelperService helperKubeService = mock(KubeHelperService.class);
    when(helperKubeService.getTaskRuntimeLabels()).thenReturn(DISPATCHER_LABELS);
    return new TaskRuntimeReconciler(client.adapt(TektonClient.class), helperKubeService, 7);
  }

  @Test
  void deletesOnlyOwnExpiredTerminalTaskRuns() {
    TektonClient tektonClient = client.adapt(TektonClient.class);
    Instant expired = Instant.now().minus(Duration.ofDays(8));
    Instant recent = Instant.now().minus(Duration.ofDays(6));
    // Outlived the TTL: one succeeded, one failed - both go.
    tektonClient.v1().taskRuns().resource(taskRun("old-ok", DISPATCHER_LABELS, "True", expired)).create();
    tektonClient.v1().taskRuns().resource(taskRun("old-ko", DISPATCHER_LABELS, "False", expired)).create();
    // Inside the TTL, still running, or not this dispatcher's - all stay.
    tektonClient.v1().taskRuns().resource(taskRun("recent", DISPATCHER_LABELS, "True", recent)).create();
    tektonClient.v1().taskRuns().resource(taskRun("running", DISPATCHER_LABELS, "Unknown", null)).create();
    tektonClient.v1().taskRuns().resource(taskRun("foreign", Map.of(), "True", expired)).create();

    reconciler().reconcile();

    List<String> remaining =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().stream()
            .map(taskRun -> taskRun.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());
    assertEquals(List.of("foreign", "recent", "running"), remaining);
  }

  @Test
  void aTickThatCannotListDoesNotThrow() {
    KubeHelperService helperKubeService = mock(KubeHelperService.class);
    when(helperKubeService.getTaskRuntimeLabels())
        .thenThrow(new IllegalStateException("no labels"));
    TaskRuntimeReconciler reconciler =
        new TaskRuntimeReconciler(client.adapt(TektonClient.class), helperKubeService, 7);
    // Best effort: a failed tick warns and leaves everything for the next tick.
    reconciler.reconcile();
  }

  @Test
  void expiryRequiresATerminalConditionAndAnOldCompletionTime() {
    Instant cutoff = Instant.now().minus(Duration.ofDays(7));
    Instant before = cutoff.minus(Duration.ofHours(1));
    Instant after = cutoff.plus(Duration.ofHours(1));

    assertTrue(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), "True", before), cutoff));
    assertTrue(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), "False", before), cutoff));
    // Terminal but inside the TTL.
    assertFalse(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), "True", after), cutoff));
    // Still running: a Succeeded=Unknown condition, with or without a completion time.
    assertFalse(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), "Unknown", before), cutoff));
    assertFalse(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), "Unknown", null), cutoff));
    // Not yet reconciled by Tekton: no status at all.
    assertFalse(TaskRuntimeReconciler.isExpired(taskRun("t", Map.of(), null, null), cutoff));
  }
}
