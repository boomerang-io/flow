package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace guard on the {@code /api/v2/workspace/&#123;workspace&#125;/workflowrun} operations.
 *
 * <p>F3 moved this guard from the deleted {@code api.WorkspaceWorkflowRunService} pass-through into
 * {@link WorkflowRunService} itself. The check was untested at the pass-through, so its call path
 * changed shape with nothing pinning it: this test pins that every workspace-scoped operation still
 * refuses a run the caller cannot reach through the named workspace, and that a caller who can
 * reach it is unaffected.
 */
class WorkflowRunWorkspaceAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "wfrun-authz-member";
  private static final String MY_WORKSPACE = "wfrun-authz-my-ws";
  private static final String FOREIGN_WORKSPACE = "wfrun-authz-foreign-ws";

  @Autowired private WorkflowRunService workflowRunService;

  private String myRunId;
  private String foreignRunId;

  /**
   * Replaces the base class's global identity - a global token passes {@code check} for any
   * workspace, so it cannot exercise a guard at all. Runs after {@code establishTestIdentity}.
   */
  @BeforeEach
  void establishMemberIdentity() {
    seedRelationshipRoot();
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, MY_WORKSPACE, MY_WORKSPACE, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, FOREIGN_WORKSPACE, FOREIGN_WORKSPACE, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        MEMBER,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        MY_WORKSPACE,
        Optional.empty());

    myRunId = ownedRun(MY_WORKSPACE, "wfrun-authz-mine");
    foreignRunId = ownedRun(FOREIGN_WORKSPACE, "wfrun-authz-theirs");

    // Owner-shaped grant ("**/**"), the only workspace-role shape (seed/roles.json) that
    // satisfies RelationshipService.checkPermissions()'s coarse resource-type gate for a
    // non-WORKSPACE RelationshipType (here WORKFLOWRUN) - see the Rule-3 finding in the task
    // report: a real "editor"/"reader" grant (["**/read","**/write","**/action"]) is now denied
    // by that gate for every non-WORKSPACE check(), including this one.
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    principal.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, MY_WORKSPACE, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void everyScopedOperationRefusesARunTheCallerCannotReachThroughThatWorkspace() {
    assertRefused(() -> workflowRunService.get(MY_WORKSPACE, foreignRunId, false), "get");
    assertRefused(
        () -> workflowRunService.start(MY_WORKSPACE, foreignRunId, Optional.empty()), "start");
    assertRefused(() -> workflowRunService.finalize(MY_WORKSPACE, foreignRunId), "finalize");
    assertRefused(() -> workflowRunService.cancel(MY_WORKSPACE, foreignRunId), "cancel");
    assertRefused(() -> workflowRunService.pause(MY_WORKSPACE, foreignRunId), "pause");
    assertRefused(() -> workflowRunService.resume(MY_WORKSPACE, foreignRunId), "resume");
    assertRefused(() -> workflowRunService.retry(MY_WORKSPACE, foreignRunId), "retry");

    // Refused before any work: the foreign run is untouched.
    WorkflowRunEntity untouched = workflowRunRepository.findById(foreignRunId).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus(), "a refused call must not mutate the run");
    assertEquals(RunPhase.running, untouched.getPhase(), "a refused call must not mutate the run");
  }

  @Test
  void aRunTheCallerCanReachThroughThatWorkspaceIsServed() {
    assertEquals(
        myRunId,
        workflowRunService.get(MY_WORKSPACE, myRunId, false).getBody().getId(),
        "the guard must not reject a run reachable through the named workspace");
  }

  @Test
  void aRunIsAlsoRefusedThroughAWorkspaceTheCallerIsNotAMemberOf() {
    // The run IS in FOREIGN_WORKSPACE, but the caller has no path to that workspace at all.
    assertRefused(() -> workflowRunService.get(FOREIGN_WORKSPACE, foreignRunId, false), "get");
  }

  private void assertRefused(Executable operation, String label) {
    BoomerangException ex =
        assertThrows(BoomerangException.class, operation, label + " must be refused");
    assertEquals(
        "WORKFLOWRUN_INVALID_REFERENCE", ex.getReason(), label + " must fail with the run-ref error");
  }

  private String ownedRun(String workspace, String workflowRef) {
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        run.getId(),
        run.getId(),
        Optional.empty(),
        Optional.empty());
    return run.getId();
  }
}
