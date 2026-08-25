package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace guard on the {@code /api/v2/workspace/&#123;workspace&#125;/workflow} operations.
 *
 * <p>F3 moved this guard from the deleted {@code api.WorkspaceWorkflowService} pass-through into
 * {@link WorkflowService} itself. The check was untested at the pass-through, so its call path
 * changed shape with nothing pinning it: this test pins that every workspace-scoped operation still
 * refuses a Workflow the caller cannot reach through the named workspace, and that a caller who can
 * reach it is unaffected.
 *
 * <p>The identity is deliberately {@code session}-scope with a real {@code MEMBER_OF} edge: {@code
 * RelationshipService.filter} anchors a session principal at its USER node and walks only the
 * workspaces it belongs to, whereas the base class's global identity anchors at ROOT and reaches
 * everything.
 */
class WorkflowWorkspaceAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "wf-authz-member";
  private static final String MY_WORKSPACE = "wf-authz-my-ws";
  private static final String FOREIGN_WORKSPACE = "wf-authz-foreign-ws";
  private static final String MY_WORKFLOW = "wf-authz-mine";
  private static final String FOREIGN_WORKFLOW = "wf-authz-theirs";
  private static final String TASK_SLUG = "wf-authz-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRepository workflowRepository;

  // Resolved while the seeding (global) identity is still in place - the member under test cannot
  // see the foreign Workflow at all, which is the whole point of the guard.
  private String foreignWorkflowRef;

  /**
   * Seeds under the base class's global identity (writing relationship edges a member is not
   * entitled to write), then replaces it with the session member. Runs after {@code
   * establishTestIdentity}.
   */
  @BeforeEach
  void establishMemberIdentity() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);

    workspaceNode(MY_WORKSPACE);
    workspaceNode(FOREIGN_WORKSPACE);
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        MEMBER,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        MY_WORKSPACE,
        Optional.empty());

    workspaceWorkflow(MY_WORKSPACE, MY_WORKFLOW);
    workspaceWorkflow(FOREIGN_WORKSPACE, FOREIGN_WORKFLOW);
    foreignWorkflowRef = workflowRef(FOREIGN_WORKFLOW);

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void everyScopedOperationRefusesAWorkflowTheCallerCannotReachThroughThatWorkspace() {
    assertRefused(
        () -> workflowService.get(MY_WORKSPACE, FOREIGN_WORKFLOW, Optional.empty(), false), "get");
    assertRefused(() -> workflowService.changelog(MY_WORKSPACE, FOREIGN_WORKFLOW), "changelog");
    assertRefused(() -> workflowService.export(MY_WORKSPACE, FOREIGN_WORKFLOW), "export");
    assertRefused(() -> workflowService.duplicate(MY_WORKSPACE, FOREIGN_WORKFLOW), "duplicate");
    assertRefused(
        () -> workflowService.composeGet(MY_WORKSPACE, FOREIGN_WORKFLOW, Optional.empty()),
        "composeGet");
    assertRefused(
        () -> workflowService.getAvailableParameters(MY_WORKSPACE, FOREIGN_WORKFLOW),
        "getAvailableParameters");
    assertRefused(
        () ->
            workflowService.submit(
                MY_WORKSPACE, FOREIGN_WORKFLOW, new WorkflowSubmitRequest(), false),
        "submit");
    assertRefused(() -> workflowService.delete(MY_WORKSPACE, FOREIGN_WORKFLOW), "delete");

    // Refused before any work: the foreign Workflow is neither tombstoned nor run.
    assertEquals(
        WorkflowStatus.active,
        workflowRepository.findById(foreignWorkflowRef).orElseThrow().getStatus(),
        "a refused delete must not tombstone the Workflow");
    assertTrue(
        workflowRunRepository.findByWorkflowRefAndPhaseIn(
            foreignWorkflowRef, List.of(io.boomerang.common.enums.RunPhase.values())).isEmpty(),
        "a refused submit must not create a run");
  }

  @Test
  void aWorkflowIsAlsoRefusedThroughAWorkspaceTheCallerIsNotAMemberOf() {
    // The Workflow IS in FOREIGN_WORKSPACE, but the caller has no path to that workspace at all.
    assertRefused(
        () -> workflowService.get(FOREIGN_WORKSPACE, FOREIGN_WORKFLOW, Optional.empty(), false),
        "get");
  }

  /**
   * The workspace narrowing itself, isolated.
   *
   * <p>For the session member above it is the USER anchor that does the refusing - {@code
   * RelationshipService.filter} walks out from the principal's node, so a foreign Workflow is
   * unreachable whether or not an intermediate workspace is passed. The {@code intermediate}
   * arguments only bite for a principal anchored at ROOT, which is every {@code global}-scope
   * token. This pins that half: a global token asking for a Workflow through the WRONG workspace's
   * path is still refused, because {@code filter} - unlike {@code check}, which returns true
   * unconditionally for global scope (RelationshipService:417-419) - keeps walking and applies the
   * containment.
   */
  @Test
  void aGlobalTokenIsStillNarrowedByTheWorkspaceInThePath() {
    Token global = new Token(AuthScope.global);
    global.setPrincipal("wf-authz-global");
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(global.getPrincipal(), null);
    authentication.setDetails(global);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertRefused(
        () -> workflowService.get(MY_WORKSPACE, FOREIGN_WORKFLOW, Optional.empty(), false),
        "get");
    assertEquals(
        FOREIGN_WORKFLOW,
        workflowService.get(FOREIGN_WORKSPACE, FOREIGN_WORKFLOW, Optional.empty(), false).getName(),
        "the same token reaching the Workflow through its OWN workspace is served");

    // submit resolves through the SAME filter call, which is why it cannot reproduce the
    // wrong-owner defect f46ede7 fixed in retry(). retry() guards with check(), and check()
    // returns true unconditionally for a global token, so the path segment reached the edge
    // write unverified; submit() guards with filter(), which keeps walking and applies the
    // workspace containment even for global scope. The `team` it writes into
    // createNodeAndEdge(WORKSPACE, team, HAS_WORKFLOWRUN, ...) is therefore always a workspace
    // that really does contain the Workflow. Pinned so that a future change to either call site
    // cannot quietly open the hole.
    assertRefused(
        () ->
            workflowService.submit(
                MY_WORKSPACE, FOREIGN_WORKFLOW, new WorkflowSubmitRequest(), false),
        "submit");
    assertTrue(
        workflowRunRepository
            .findByWorkflowRefAndPhaseIn(
                foreignWorkflowRef, List.of(io.boomerang.common.enums.RunPhase.values()))
            .isEmpty(),
        "a submit refused by the workspace narrowing must not create a run");
  }

  @Test
  void aWorkflowTheCallerCanReachThroughThatWorkspaceIsServed() {
    assertEquals(
        MY_WORKFLOW,
        workflowService.get(MY_WORKSPACE, MY_WORKFLOW, Optional.empty(), false).getName(),
        "the guard must not reject a Workflow reachable through the named workspace");
  }

  @Test
  void theScopedQueryAndCountSeeOnlyTheNamedWorkspacesWorkflows() {
    List<String> names =
        workflowService
            .query(
                MY_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getContent()
            .stream()
            .map(Workflow::getName)
            .toList();
    assertTrue(names.contains(MY_WORKFLOW), "the caller's own workspace workflow must be returned");
    assertFalse(
        names.contains(FOREIGN_WORKFLOW),
        "another workspace's workflow must never appear in the page");
  }

  private void assertRefused(Executable operation, String label) {
    BoomerangException ex =
        assertThrows(BoomerangException.class, operation, label + " must be refused");
    assertEquals(
        "WORKFLOW_INVALID_REFERENCE",
        ex.getReason(),
        label + " must fail with the workflow-ref error");
  }

  // Anchored under root so the seeding identity (global, which anchors at ROOT) can resolve these
  // workspaces - the same shape production has, where every workspace hangs off the root node.
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

  // Idempotent: @BeforeEach runs per test method against one shared Testcontainers database.
  private void workspaceWorkflow(String workspace, String name) {
    if (!relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)),
            false)
        .isEmpty()) {
      return;
    }
    workflowService.create(workspace, runnableWorkflow(name, TASK_SLUG));
  }

  private String workflowRef(String name) {
    return relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.empty(),
            Optional.empty(),
            false)
        .get(0);
  }
}
