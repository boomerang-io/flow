package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The workspace type set at create is returned on read and changeable through patch. */
class WorkspaceTypePatchTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
  }

  @Test
  void typeIsReturnedOnCreateAndHonouredOnPatch() {
    String workspaceName = "workspace-type-patch-test";
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    createRequest.setType(WorkspaceType.hobby);
    Workspace created = workspaceService.create(createRequest);
    assertEquals(WorkspaceType.hobby, created.getType());

    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setType(WorkspaceType.pro);
    Workspace patched = workspaceService.patch(workspaceName, patchRequest);
    assertEquals(WorkspaceType.pro, patched.getType());

    // A patch that omits type leaves the stored value untouched.
    WorkspaceRequest untouchedRequest = new WorkspaceRequest();
    untouchedRequest.setDisplayName("renamed display");
    Workspace untouched = workspaceService.patch(workspaceName, untouchedRequest);
    assertEquals(WorkspaceType.pro, untouched.getType());
  }
}
