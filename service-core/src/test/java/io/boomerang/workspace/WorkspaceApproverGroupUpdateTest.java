package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.ApproverGroupRequest;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An approver-group edit carries the group's id, so a rename must update that group in place
 * rather than create a second group under the new name.
 */
class WorkspaceApproverGroupUpdateTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
  }

  @Test
  void renamingAnApproverGroupByIdUpdatesTheExistingGroup() {
    String workspaceName = "approver-group-rename-workspace-test";
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    workspaceService.create(createRequest);

    // Groups are added through the workspace patch, matching the webapp's flow.
    ApproverGroupRequest group = new ApproverGroupRequest();
    group.setName("Release Approvers");
    group.setApprovers(List.of());
    WorkspaceRequest addRequest = new WorkspaceRequest();
    addRequest.setApproverGroups(List.of(group));
    Workspace created = workspaceService.patch(workspaceName, addRequest);
    assertEquals(1, created.getApproverGroups().size());
    String groupId = created.getApproverGroups().get(0).getId();

    ApproverGroupRequest rename = new ApproverGroupRequest();
    rename.setId(groupId);
    rename.setName("Deploy Approvers");
    rename.setApprovers(List.of());
    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setApproverGroups(List.of(rename));

    Workspace patched = workspaceService.patch(workspaceName, patchRequest);

    assertEquals(1, patched.getApproverGroups().size(), "a rename must not create a second group");
    assertEquals(groupId, patched.getApproverGroups().get(0).getId());
    assertEquals("Deploy Approvers", patched.getApproverGroups().get(0).getName());
  }
}
