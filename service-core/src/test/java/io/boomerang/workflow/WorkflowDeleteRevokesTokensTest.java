package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a workflow revokes the tokens whose principal is that workflow's id, and no others. A
 * workflow name is only unique within its workspace, so two workspaces may each hold a workflow of
 * the same name; the id is what tells their tokens apart.
 */
class WorkflowDeleteRevokesTokensTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE_A = "wf-token-ws-a";
  private static final String WORKSPACE_B = "wf-token-ws-b";
  private static final String WORKFLOW = "wf-token-same-name";
  private static final String TASK_SLUG = "wf-token-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private TokenService tokenService;
  @Autowired private TokenRepository tokenRepository;

  @BeforeEach
  void seedWorkspaces() {
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    workspaceNode(WORKSPACE_A);
    workspaceNode(WORKSPACE_B);
  }

  @Test
  void deletingAWorkflowRevokesItsOwnTokensAndNoOthers() {
    workflowService.create(WORKSPACE_A, runnableWorkflow(WORKFLOW, TASK_SLUG));
    workflowService.create(WORKSPACE_B, runnableWorkflow(WORKFLOW, TASK_SLUG));
    String deleted = workflowRef(WORKSPACE_A);
    String kept = workflowRef(WORKSPACE_B);
    workflowToken(deleted);
    workflowToken(kept);

    workflowService.delete(WORKSPACE_A, WORKFLOW);

    assertTrue(tokens(deleted).isEmpty(), "the deleted workflow's token must be revoked");
    assertFalse(
        tokens(kept).isEmpty(), "a same-named workflow in another workspace keeps its token");
  }

  // What the editor's Configure > Tokens tab mints: a key token acting as the workflow.
  private void workflowToken(String workflowId) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setName("editor-token");
    request.setType(AuthScope.key);
    request.setActorKind(TokenActorKind.WORKFLOW);
    request.setPrincipal(workflowId);
    request.setPermissions(List.of("workflow/read"));
    tokenService.create(request);
  }

  // The public create response carries no id; the relationship node does, as the delete reads it.
  private String workflowRef(String workspace) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(WORKFLOW)))
        .get(0);
  }

  private List<TokenEntity> tokens(String workflowId) {
    return tokenRepository.findByPrincipalAndType(workflowId, AuthScope.key).orElse(List.of());
  }

  // Anchored under root so the global test identity resolves the workspaces, as production does.
  private void workspaceNode(String name) {
    if (relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, name)) {
      return;
    }
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        name,
        name,
        Optional.empty(),
        Optional.empty());
  }
}
