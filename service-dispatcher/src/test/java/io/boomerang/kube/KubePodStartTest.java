package io.boomerang.kube;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.executor.PodStart;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How far a Task's pod has got towards running, read from the pod its labels select. */
@EnableKubernetesMockClient(crud = true)
class KubePodStartTest {

  KubernetesClient client;

  private final KubeHelperService helper = new KubeHelperService();

  private static ContainerStatus waiting(String name, String reason) {
    ContainerStateWaiting waiting = new ContainerStateWaiting();
    waiting.setReason(reason);
    waiting.setMessage(reason + " message");
    ContainerState state = new ContainerState();
    state.setWaiting(waiting);
    ContainerStatus status = new ContainerStatus();
    status.setName(name);
    status.setState(state);
    return status;
  }

  private void pod(String task, PodStatus status) {
    Pod pod =
        new PodBuilder()
            .withNewMetadata()
            .withName(task + "-pod")
            .withLabels(Map.of("task", task))
            .endMetadata()
            .build();
    Pod created = client.pods().resource(pod).create();
    created.setStatus(status);
    client.pods().resource(created).updateStatus();
  }

  @Test
  void noPodIsNotCreated() {
    PodStart start = helper.podStart(client, Map.of("task", "none"));

    assertThat(start.started()).isFalse();
    assertThat(start.message()).isEqualTo("no pod was created");
  }

  @Test
  void aPodThatLeftPendingHasStarted() {
    PodStatus status = new PodStatus();
    status.setPhase("Running");
    pod("running", status);

    assertThat(helper.podStart(client, Map.of("task", "running")).started()).isTrue();
  }

  @Test
  void anInitContainerHeldOnItsImageIsReported() {
    // Tekton's init containers pull their own images; one stuck there holds the whole pod.
    PodStatus status = new PodStatus();
    status.setPhase("Pending");
    status.setInitContainerStatuses(List.of(waiting("prepare", "ErrImagePull")));
    status.setContainerStatuses(List.of(waiting("step-task", "PodInitializing")));
    pod("init", status);

    PodStart start = helper.podStart(client, Map.of("task", "init"));

    assertThat(start.started()).isFalse();
    assertThat(start.waitingReason()).isEqualTo("ErrImagePull");
    assertThat(start.message()).startsWith("prepare:");
  }

  @Test
  void anUnscheduledPodSaysWhy() {
    PodCondition scheduled = new PodCondition();
    scheduled.setType("PodScheduled");
    scheduled.setStatus("False");
    scheduled.setMessage("0/3 nodes are available: 3 Insufficient cpu.");
    PodStatus status = new PodStatus();
    status.setPhase("Pending");
    status.setConditions(List.of(scheduled));
    pod("unscheduled", status);

    PodStart start = helper.podStart(client, Map.of("task", "unscheduled"));

    assertThat(start.waitingReason()).isNull();
    assertThat(start.message()).contains("Insufficient cpu");
  }

  @Test
  void tektonsStepContainerIsReadForTheFailureReason() {
    ContainerStateTerminated terminated = new ContainerStateTerminated();
    terminated.setReason("OOMKilled");
    ContainerState state = new ContainerState();
    state.setTerminated(terminated);
    ContainerStatus step = new ContainerStatus();
    step.setName("step-task");
    step.setState(state);
    PodStatus status = new PodStatus();
    status.setPhase("Failed");
    status.setContainerStatuses(List.of(step));
    pod("tekton-oom", status);

    assertThat(helper.getPodFailureReason(client, Map.of("task", "tekton-oom")))
        .isEqualTo("OOMKilled");
  }
}
