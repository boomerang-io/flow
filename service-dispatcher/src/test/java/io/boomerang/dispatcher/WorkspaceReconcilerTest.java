package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.sdk.DispatcherClient;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseQuery;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseResponse;
import io.boomerang.kube.KubeService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the release contract: the cluster says which workspace claims are held, the engine says
 * whose run is finished, and only the run refs the engine names are deleted. Nothing on the run
 * records the release, so a failed tick must leave every claim held rather than guess.
 */
class WorkspaceReconcilerTest {

  private final KubeService kubeService = mock(KubeService.class);
  private final WorkspaceService workspaceService = mock(WorkspaceService.class);
  private final DispatcherClient dispatcherClient = mock(DispatcherClient.class);

  private final WorkspaceReconciler reconciler =
      new WorkspaceReconciler(kubeService, workspaceService, dispatcherClient);

  private static PersistentVolumeClaim claim(String workflowRunRef) {
    return new PersistentVolumeClaimBuilder()
        .withNewMetadata()
        .withName("bmrg-pvc-" + workflowRunRef)
        .withLabels(Map.of("boomerang.io/workspace-ref", workflowRunRef))
        .endMetadata()
        .build();
  }

  private static WorkspaceReleaseResponse response(List<String> workflowRunRefs) {
    WorkspaceReleaseResponse response = new WorkspaceReleaseResponse();
    response.setWorkflowRunRefs(workflowRunRefs);
    return response;
  }

  @Test
  void refsArePagedAtFiveHundred() {
    List<PersistentVolumeClaim> claims = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      claims.add(claim("run-" + i));
    }
    when(kubeService.listWorkspacePVCs()).thenReturn(claims);
    when(dispatcherClient.releasable(any())).thenReturn(new WorkspaceReleaseResponse());

    reconciler.reconcile();

    ArgumentCaptor<WorkspaceReleaseQuery> captor =
        ArgumentCaptor.forClass(WorkspaceReleaseQuery.class);
    verify(dispatcherClient, times(2)).releasable(captor.capture());
    WorkspaceReleaseQuery first = captor.getAllValues().get(0);
    WorkspaceReleaseQuery second = captor.getAllValues().get(1);
    assertEquals(500, first.getWorkflowRunRefs().size(), "first page caps at 500 run refs");
    assertEquals(100, second.getWorkflowRunRefs().size(), "the remainder is a second page");
    assertEquals("run-599", second.getWorkflowRunRefs().get(99));
    verify(workspaceService, never()).delete(anyString());
  }

  @Test
  void onlyTheRefsTheEngineReturnsAreDeleted() {
    when(kubeService.listWorkspacePVCs())
        .thenReturn(List.of(claim("run-1"), claim("run-2"), claim("run-3")));
    when(dispatcherClient.releasable(any())).thenReturn(response(List.of("run-2", "run-3")));

    reconciler.reconcile();

    verify(workspaceService).delete("run-2");
    verify(workspaceService).delete("run-3");
    verify(workspaceService, never()).delete("run-1");
  }

  @Test
  void aClaimWithoutTheWorkspaceLabelIsIgnored() {
    PersistentVolumeClaim unlabelled =
        new PersistentVolumeClaimBuilder().withNewMetadata().withName("stray").endMetadata().build();
    when(kubeService.listWorkspacePVCs()).thenReturn(List.of(unlabelled));

    reconciler.reconcile();

    verify(dispatcherClient, never()).releasable(any());
    verify(workspaceService, never()).delete(anyString());
  }

  @Test
  void anEngineFailureLeavesEveryClaimHeld() {
    when(kubeService.listWorkspacePVCs()).thenReturn(List.of(claim("run-1")));
    when(dispatcherClient.releasable(any())).thenThrow(new RuntimeException("engine down"));

    assertDoesNotThrow(reconciler::reconcile);

    verify(workspaceService, never()).delete(anyString());
  }

  @Test
  void anEmptyAnswerLeavesEveryClaimHeld() {
    when(kubeService.listWorkspacePVCs()).thenReturn(List.of(claim("run-1")));
    when(dispatcherClient.releasable(any())).thenReturn(new WorkspaceReleaseResponse());

    reconciler.reconcile();

    verify(workspaceService, never()).delete(anyString());
  }

  @Test
  void oneClaimThatWillNotDeleteDoesNotAbortTheTick() {
    when(kubeService.listWorkspacePVCs()).thenReturn(List.of(claim("run-1"), claim("run-2")));
    when(dispatcherClient.releasable(any())).thenReturn(response(List.of("run-1", "run-2")));
    when(workspaceService.delete("run-1")).thenThrow(new RuntimeException("api server said no"));

    assertDoesNotThrow(reconciler::reconcile);

    verify(workspaceService).delete("run-2");
  }
}
