package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A patch merges quotas field by field: the quotas it does not carry keep their stored value. */
class WorkspaceQuotaPatchTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;

  @Test
  void patchingOneQuotaLeavesTheOthersStored() {
    String workspaceName = "workspace-quota-patch-test";
    Quotas initial = new Quotas();
    initial.setMaxWorkflowCount(7);
    initial.setMaxConcurrentRuns(0);
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    createRequest.setQuotas(initial);
    workspaceService.create(createRequest);

    Quotas durationOnly = new Quotas();
    durationOnly.setMaxWorkflowRunDuration(45);
    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setQuotas(durationOnly);
    workspaceService.patch(workspaceName, patchRequest);

    Quotas stored = storedQuotas(workspaceName);
    assertEquals(45, stored.getMaxWorkflowRunDuration());
    assertEquals(7, stored.getMaxWorkflowCount());
    assertEquals(0, stored.getMaxConcurrentRuns());
    assertNull(stored.getMaxWorkflowRunMonthly());

    // A patch that carries no quotas at all leaves every stored quota untouched.
    WorkspaceRequest renameRequest = new WorkspaceRequest();
    renameRequest.setDisplayName("renamed display");
    workspaceService.patch(workspaceName, renameRequest);

    Quotas untouched = storedQuotas(workspaceName);
    assertEquals(45, untouched.getMaxWorkflowRunDuration());
    assertEquals(7, untouched.getMaxWorkflowCount());
    assertEquals(0, untouched.getMaxConcurrentRuns());
  }

  private Quotas storedQuotas(String workspaceName) {
    WorkspaceEntity entity = workspaceRepository.findByNameIgnoreCase(workspaceName).orElseThrow();
    return entity.getQuotas();
  }
}
