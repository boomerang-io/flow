package io.boomerang.dispatcher;

import io.boomerang.kube.KubeHelperService;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.v1.TaskRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes this dispatcher's finished Tekton TaskRuns once they are older than
 * {@code kube.task.ttlDays}, regardless of {@code kube.task.deletion}. Kubernetes Jobs expire
 * natively via {@code ttlSecondsAfterFinished}; Tekton ships no TTL controller, so this reconciler
 * gives both executors the same retention. Only objects carrying this dispatcher's own product and
 * tier labels are considered, and a failed tick costs only a delay - the leftovers are still there
 * when the next tick asks again.
 */
@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "tekton", matchIfMissing = true)
@ConditionalOnProperty(
    name = "flow.dispatcher.task.reconcile.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TaskRuntimeReconciler {

  private static final Logger LOGGER = LogManager.getLogger(TaskRuntimeReconciler.class);

  private final TektonClient client;

  private final KubeHelperService helperKubeService;

  private final int ttlDays;

  public TaskRuntimeReconciler(
      TektonClient client,
      KubeHelperService helperKubeService,
      @Value("${kube.task.ttlDays}") int ttlDays) {
    this.client = client;
    this.helperKubeService = helperKubeService;
    this.ttlDays = ttlDays;
  }

  @Scheduled(fixedDelayString = "${flow.dispatcher.task.reconcile-ms:3600000}")
  public void reconcile() {
    try {
      Instant cutoff = Instant.now().minus(Duration.ofDays(ttlDays));
      List<TaskRun> held =
          client
              .v1()
              .taskRuns()
              .withLabels(helperKubeService.getTaskRuntimeLabels())
              .list()
              .getItems();
      int deleted = 0;
      for (TaskRun taskRun : held) {
        if (!isExpired(taskRun, cutoff)) {
          continue;
        }
        try {
          // The same propagation the executor's own delete uses; an absent TaskRun is already gone.
          client
              .v1()
              .taskRuns()
              .resource(taskRun)
              .withPropagationPolicy(DeletionPropagation.BACKGROUND)
              .delete();
          deleted++;
        } catch (Exception e) {
          // One TaskRun the API server will not delete must not cost the rest of the tick.
          LOGGER.error(
              "Unable to delete expired TaskRun ({}): {}",
              taskRun.getMetadata().getName(),
              e.getMessage());
        }
      }
      LOGGER.info("Task runtime reconcile: held={}, deleted={}", held.size(), deleted);
    } catch (Exception e) {
      // A tick is best effort - the leftovers stay held and the next tick asks again.
      LOGGER.warn("Task runtime reconcile failed: {}", e.getMessage());
    }
  }

  /**
   * True for a TaskRun whose first condition is terminal ({@code Succeeded} True or False) and
   * whose {@code completionTime} is before the cutoff. Anything still running, cancelling, or not
   * yet reconciled by Tekton stays.
   */
  static boolean isExpired(TaskRun taskRun, Instant cutoff) {
    if (taskRun.getStatus() == null || taskRun.getStatus().getCompletionTime() == null) {
      return false;
    }
    List<Condition> conditions = taskRun.getStatus().getConditions();
    if (conditions == null || conditions.isEmpty()) {
      return false;
    }
    String status = conditions.get(0).getStatus();
    return ("True".equals(status) || "False".equals(status))
        && Instant.parse(taskRun.getStatus().getCompletionTime()).isBefore(cutoff);
  }
}
