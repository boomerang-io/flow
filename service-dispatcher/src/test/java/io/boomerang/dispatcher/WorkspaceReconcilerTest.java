package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.WorkspaceReleaseQuery;
import io.boomerang.common.model.WorkspaceReleaseResponse;
import io.boomerang.dispatcher.model.HeldWorkspace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the release contract: the workspace store says which workspaces are held, the engine says
 * whose owner is finished, and only the refs the engine names are deleted. Nothing on the run
 * records the release, so a failed tick must leave every workspace held rather than guess.
 */
class WorkspaceReconcilerTest {

  private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
  private final WorkspaceService workspaceService = mock(WorkspaceService.class);
  private final EngineClient engineClient = mock(EngineClient.class);

  private final WorkspaceReconciler reconciler =
      new WorkspaceReconciler(workspaceStore, workspaceService, engineClient);

  private static HeldWorkspace claim(String workspaceRef, String workspaceType) {
    return new HeldWorkspace(workspaceRef, workspaceType);
  }

  private static WorkspaceReleaseResponse response(
      List<String> workflowRunRefs, List<String> workflowRefs) {
    WorkspaceReleaseResponse response = new WorkspaceReleaseResponse();
    response.setWorkflowRunRefs(workflowRunRefs);
    response.setWorkflowRefs(workflowRefs);
    return response;
  }

  @Test
  void refsAreGroupedByStorageTypeAndPagedAtFiveHundred() {
    List<HeldWorkspace> claims = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      claims.add(claim("run-" + i, "workflowrun"));
    }
    claims.add(claim("wf-1", "workflow"));
    when(workspaceStore.held()).thenReturn(claims);
    when(engineClient.releasableWorkspaces(any())).thenReturn(new WorkspaceReleaseResponse());

    reconciler.reconcile();

    ArgumentCaptor<WorkspaceReleaseQuery> captor =
        ArgumentCaptor.forClass(WorkspaceReleaseQuery.class);
    verify(engineClient, times(2)).releasableWorkspaces(captor.capture());
    WorkspaceReleaseQuery first = captor.getAllValues().get(0);
    WorkspaceReleaseQuery second = captor.getAllValues().get(1);
    assertEquals(500, first.getWorkflowRunRefs().size(), "first page caps at 500 run refs");
    assertEquals(List.of("wf-1"), first.getWorkflowRefs());
    assertEquals(100, second.getWorkflowRunRefs().size(), "the remainder is a second page");
    assertEquals("run-599", second.getWorkflowRunRefs().get(99));
    assertTrue(second.getWorkflowRefs().isEmpty(), "the workflow refs fitted on the first page");
    verify(workspaceService, never()).delete(anyString(), anyString());
  }

  @Test
  void onlyTheRefsTheEngineReturnsAreDeleted() {
    when(workspaceStore.held())
        .thenReturn(
            List.of(
                claim("run-1", "workflowrun"),
                claim("run-2", "workflowrun"),
                claim("wf-1", "workflow")));
    when(engineClient.releasableWorkspaces(any()))
        .thenReturn(response(List.of("run-2"), List.of("wf-1")));

    reconciler.reconcile();

    verify(workspaceService).delete("workflowrun", "run-2");
    verify(workspaceService).delete("workflow", "wf-1");
    verify(workspaceService, never()).delete("workflowrun", "run-1");
  }

  @Test
  void aHeldWorkspaceWithoutItsLabelsIsIgnored() {
    when(workspaceStore.held()).thenReturn(List.of(new HeldWorkspace(null, null)));

    reconciler.reconcile();

    verify(engineClient, never()).releasableWorkspaces(any());
    verify(workspaceService, never()).delete(anyString(), anyString());
  }

  @Test
  void anEngineFailureLeavesEveryClaimHeld() {
    when(workspaceStore.held()).thenReturn(List.of(claim("run-1", "workflowrun")));
    when(engineClient.releasableWorkspaces(any())).thenThrow(new RuntimeException("engine down"));

    assertDoesNotThrow(reconciler::reconcile);

    verify(workspaceService, never()).delete(anyString(), anyString());
  }

  @Test
  void anEmptyAnswerLeavesEveryClaimHeld() {
    when(workspaceStore.held()).thenReturn(List.of(claim("run-1", "workflowrun")));
    when(engineClient.releasableWorkspaces(any())).thenReturn(new WorkspaceReleaseResponse());

    reconciler.reconcile();

    verify(workspaceService, never()).delete(anyString(), anyString());
  }

  @Test
  void oneClaimThatWillNotDeleteDoesNotAbortTheTick() {
    when(workspaceStore.held())
        .thenReturn(List.of(claim("run-1", "workflowrun"), claim("run-2", "workflowrun")));
    when(engineClient.releasableWorkspaces(any()))
        .thenReturn(response(List.of("run-1", "run-2"), List.of()));
    when(workspaceService.delete("workflowrun", "run-1"))
        .thenThrow(new RuntimeException("api server said no"));

    assertDoesNotThrow(reconciler::reconcile);

    verify(workspaceService).delete("workflowrun", "run-2");
  }
}
