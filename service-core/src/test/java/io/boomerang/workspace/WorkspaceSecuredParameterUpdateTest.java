package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The workspace-parameter path shares the same flaw as {@link
 * io.boomerang.workflow.ParameterService#update}: a read never returns a secured value, so a
 * patch that only edits some other field must not let the resulting blank wipe the stored secret.
 */
class WorkspaceSecuredParameterUpdateTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
  }

  @Test
  void patchingAnotherFieldOnASecuredWorkspaceParameterPreservesItsStoredValue() {
    String workspaceName = "secured-param-workspace-test";
    WorkspaceRequest createRequest = new WorkspaceRequest();
    createRequest.setName(workspaceName);
    createRequest.setDisplayName(workspaceName);
    AbstractParam securedParam = new AbstractParam();
    securedParam.setName("db-password");
    securedParam.setType("password");
    securedParam.setValue("hunter2");
    createRequest.setParameters(List.of(securedParam));
    Workspace created = workspaceService.create(createRequest);
    assertNull(
        parameterNamed(created.getParameters(), "db-password").getValue(),
        "create's own response is already filtered");

    // The read -> edit -> patch round trip: the caller never saw the real value, so what they
    // send back for this parameter is the filtered null - only "description" is a real edit.
    WorkspaceRequest patchRequest = new WorkspaceRequest();
    AbstractParam editedParam = new AbstractParam();
    editedParam.setName("db-password");
    editedParam.setType("password");
    editedParam.setValue(null);
    editedParam.setDescription("now documented");
    patchRequest.setParameters(List.of(editedParam));

    workspaceService.patch(workspaceName, patchRequest);

    // Read the raw entity directly - the service-level get() filters password values too.
    WorkspaceEntity persisted = workspaceRepository.findByNameIgnoreCase(workspaceName).orElseThrow();
    AbstractParam stored = parameterNamed(persisted.getParameters(), "db-password");
    assertEquals("hunter2", stored.getValue(), "the stored secret must survive the patch");
    assertEquals("now documented", stored.getDescription());
  }

  private static AbstractParam parameterNamed(List<AbstractParam> params, String name) {
    return params.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
  }
}
