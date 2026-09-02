package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.model.WorkspaceType;
import io.boomerang.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The workspace type set at create is returned on read and changeable through patch — except the
 * {@code system} type, which marks the one loader-seeded system workspace and can be neither
 * assigned nor removed.
 */
class WorkspaceTypePatchTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;

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
    assertEquals(WorkspaceType.hobby, workspaceService.get(workspaceName).getType());

    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setType(WorkspaceType.pro);
    Workspace patched = workspaceService.patch(workspaceName, patchRequest);
    assertEquals(WorkspaceType.pro, patched.getType());
    assertEquals(WorkspaceType.pro, workspaceService.get(workspaceName).getType());

    // A patch that omits type leaves the stored value untouched.
    WorkspaceRequest untouchedRequest = new WorkspaceRequest();
    untouchedRequest.setDisplayName("renamed display");
    Workspace untouched = workspaceService.patch(workspaceName, untouchedRequest);
    assertEquals(WorkspaceType.pro, untouched.getType());
  }

  @Test
  void systemTypeCannotBeAssignedAtCreate() {
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName("workspace-type-system-create");
    createRequest.setDisplayName("workspace-type-system-create");
    createRequest.setType(WorkspaceType.system);
    assertThrows(BoomerangException.class, () -> workspaceService.create(createRequest));
  }

  @Test
  void systemTypeCannotBeAssignedByPatch() {
    String workspaceName = "workspace-type-system-to";
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    createRequest.setType(WorkspaceType.hobby);
    workspaceService.create(createRequest);

    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setType(WorkspaceType.system);
    assertThrows(
        BoomerangException.class, () -> workspaceService.patch(workspaceName, patchRequest));
    assertEquals(WorkspaceType.hobby, workspaceService.get(workspaceName).getType());
  }

  @Test
  void systemTypeCannotBeRemovedByPatch() {
    // The system workspace itself is loader-seeded, so mark one at the entity level the same way.
    String workspaceName = "workspace-type-system-from";
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    workspaceService.create(createRequest);
    WorkspaceEntity entity = workspaceRepository.findByNameIgnoreCase(workspaceName).orElseThrow();
    entity.setType(WorkspaceType.system);
    workspaceRepository.save(entity);

    WorkspaceRequest patchRequest = new WorkspaceRequest();
    patchRequest.setType(WorkspaceType.pro);
    assertThrows(
        BoomerangException.class, () -> workspaceService.patch(workspaceName, patchRequest));
    assertEquals(WorkspaceType.system, workspaceService.get(workspaceName).getType());

    // Re-sending the unchanged system type is a no-op, not a refusal.
    WorkspaceRequest sameTypeRequest = new WorkspaceRequest();
    sameTypeRequest.setType(WorkspaceType.system);
    assertEquals(
        WorkspaceType.system, workspaceService.patch(workspaceName, sameTypeRequest).getType());
  }
}
