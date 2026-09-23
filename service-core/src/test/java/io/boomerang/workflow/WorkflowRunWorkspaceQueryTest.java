package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.model.WorkflowRunResponsePage;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunSummary;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace scoping on the read side of the {@code
 * /api/v2/workspace/&#123;workspace&#125;/workflowrun} surface: {@code query}, {@code insight} and
 * {@code count}.
 *
 * <p>F3 moved these three off the deleted {@code api.WorkspaceWorkflowRunService} and onto the
 * shared {@code workspaceWorkflowRefs} helper in {@link WorkflowRunService}. Nothing in the suite
 * touched that path, so the one call that decides what a caller may read - {@code
 * RelationshipService.filter(WORKFLOW, ..., WORKSPACE, [queryTeam], false)} - changed shape
 * unpinned. This pins it.
 *
 * <p>The identity is a {@code session}-scope member rather than the base class's global token: a
 * global token returns {@code true} from every relationship check and anchors {@code filter} at
 * ROOT, so it cannot exercise scoping at all. The member deliberately belongs to <b>two</b>
 * workspaces - with only one, a query scoped to it would pass on identity filtering alone and the
 * workspace narrowing under test would never be load-bearing.
 */
class WorkflowRunWorkspaceQueryTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "wfrun-query-member";
  // Both of these have the member as a member; only the first is ever named in a query.
  private static final String MY_WORKSPACE = "wfrun-query-my-ws";
  private static final String SIBLING_WORKSPACE = "wfrun-query-sibling-ws";
  // This one the member cannot reach at all.
  private static final String FOREIGN_WORKSPACE = "wfrun-query-foreign-ws";
  private static final String EMPTY_WORKSPACE = "wfrun-query-empty-ws";

  private static final String MY_WORKFLOW = "wfrun-query-my-wf";
  private static final String SIBLING_WORKFLOW = "wfrun-query-sibling-wf";
  private static final String FOREIGN_WORKFLOW = "wfrun-query-foreign-wf";

  private static final List<RunPhase> ALL_PHASES = List.of(RunPhase.values());

  @Autowired private WorkflowRunService workflowRunService;

  private String myRunId;
  private String siblingRunId;
  private String foreignRunId;

  @BeforeEach
  void seedThreeWorkspacesAndAMemberIdentity() {
    seedRelationshipRoot();
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    // Wired as WorkspaceService.create wires a real workspace (root CONTAINS workspace), so the
    // workspace-level reachability check the query now performs sees the same graph production does.
    for (String workspace :
        List.of(MY_WORKSPACE, SIBLING_WORKSPACE, FOREIGN_WORKSPACE, EMPTY_WORKSPACE)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspace,
          workspace,
          Optional.empty(),
          Optional.empty());
    }
    for (String workspace : List.of(MY_WORKSPACE, SIBLING_WORKSPACE, EMPTY_WORKSPACE)) {
      relationshipService.createEdge(
          RelationshipType.USER,
          MEMBER,
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          workspace,
          Optional.empty());
    }

    myRunId = workflowOwnedBy(MY_WORKSPACE, MY_WORKFLOW);
    siblingRunId = workflowOwnedBy(SIBLING_WORKSPACE, SIBLING_WORKFLOW);
    foreignRunId = workflowOwnedBy(FOREIGN_WORKSPACE, FOREIGN_WORKFLOW);

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    // A real member session carries a workspace-scoped grant per membership (resolved by
    // TokenService); check() consults it before the graph walk, filter() does not. Without it the
    // workspace-level reachability test the query now performs would deny every workspace.
    principal.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, MY_WORKSPACE, List.of("**/read")),
            new ResolvedPermissions(
                PermissionScope.workspace, SIBLING_WORKSPACE, List.of("**/read")),
            new ResolvedPermissions(
                PermissionScope.workspace, EMPTY_WORKSPACE, List.of("**/read"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void queryReturnsOnlyRunsOfTheNamedWorkspacesWorkflows() {
    WorkflowRunResponsePage page = queryWorkspace(MY_WORKSPACE);
    List<String> ids = page.getContent().stream().map(WorkflowRun::getId).toList();

    assertTrue(ids.contains(myRunId), "the named workspace's own run must be returned");
    assertFalse(
        ids.contains(siblingRunId),
        "a workspace the caller is ALSO a member of must not leak into another workspace's query");
    assertFalse(
        ids.contains(foreignRunId), "a workspace the caller cannot reach must never be returned");
    assertEquals(
        List.of(MY_WORKFLOW),
        page.getContent().stream().map(WorkflowRun::getWorkflowRef).distinct().toList(),
        "every returned run must belong to a Workflow of the named workspace");
  }

  @Test
  void runIdFilterNarrowsWithinTheWorkspaceAndCannotReachAForeignRun() {
    // A second run of the workspace's own workflow, so the id filter has something to exclude.
    String otherMyRunId =
        savedWorkflowRun(MY_WORKFLOW, RunStatus.running, RunPhase.running).getId();

    List<String> ids =
        queryWorkspace(MY_WORKSPACE, Optional.of(List.of(myRunId))).getContent().stream()
            .map(WorkflowRun::getId)
            .toList();
    assertEquals(List.of(myRunId), ids, "workflowruns= must narrow the page to the named ids");
    assertFalse(ids.contains(otherMyRunId));

    assertTrue(
        queryWorkspace(MY_WORKSPACE, Optional.of(List.of(foreignRunId))).getContent().isEmpty(),
        "naming a run outside the workspace must return nothing, not the run");
  }

  @Test
  void insightAndCountAreScopedToTheSameWorkflows() {
    long mine = runCount(MY_WORKFLOW);
    assertTrue(
        runCount(SIBLING_WORKFLOW) > 0,
        "the sibling workspace must actually have runs, or nothing is being excluded");

    WorkflowRunInsight insight =
        workflowRunService.insight(
            MY_WORKSPACE, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals(
        List.of(MY_WORKFLOW),
        insight.getRuns().stream().map(WorkflowRunSummary::getWorkflowRef).distinct().toList(),
        "insight must summarise only the named workspace's Workflows");
    assertEquals(
        mine,
        insight.getTotalRuns().longValue(),
        "insight must total only the named workspace's runs");

    WorkflowRunCount count =
        workflowRunService.count(
            MY_WORKSPACE, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals(
        mine,
        count.getStatus().get("all").longValue(),
        "count must total only the named workspace's runs");
  }

  @Test
  void anUnreachableWorkspaceIs404FromQueryAndAnswersZeroFromInsightAndCount() {
    // The caller has no path to FOREIGN_WORKSPACE. That is an unresolvable workspace reference,
    // so query answers the same 404 the rest of the product uses for one (TEAM_INVALID_REF) -
    // not a run reference error, since no run was named.
    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> queryWorkspace(FOREIGN_WORKSPACE),
            "query must refuse a workspace the caller cannot reach");
    assertEquals(BoomerangError.TEAM_INVALID_REF.getReason(), ex.getReason());

    // insight and count deliberately do NOT throw on the same input - they answer zero. The
    // asymmetry predates F3, which put all three on one helper where it is easy to "tidy away".
    assertEquals(
        0L,
        workflowRunService
            .insight(
                FOREIGN_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getTotalRuns()
            .longValue(),
        "insight answers zero for an unreachable workspace rather than throwing");
    assertEquals(
        0L,
        workflowRunService
            .count(
                FOREIGN_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getStatus()
            .get("all")
            .longValue(),
        "count answers zero for an unreachable workspace rather than throwing");
  }

  @Test
  void aReachableWorkspaceWithNoWorkflowsAnswersAnEmptyPageNotAnError() {
    // EMPTY_WORKSPACE is a member workspace with nothing in it - a new workspace, or one whose
    // workflows were all deleted. It exists; it simply has nothing to list.
    WorkflowRunResponsePage page = queryWorkspace(EMPTY_WORKSPACE);
    assertEquals(0, page.getTotalElements());
    assertTrue(page.getContent().isEmpty());
  }

  @Test
  void aWorkflowsFilterMatchingNothingAnswersAnEmptyPageNotAnError() {
    // The workspace is reachable and has workflows; the filter names one it does not have. A
    // filter that matches nothing is an empty result, exactly as any other list filter behaves.
    WorkflowRunResponsePage page =
        workflowRunService.query(
            MY_WORKSPACE,
            Optional.empty(),
            Optional.empty(),
            Optional.of(10),
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of("no-such-workflow")),
            Optional.empty());
    assertEquals(0, page.getTotalElements());
    assertTrue(page.getContent().isEmpty());
    assertEquals(10, page.getPageable().getPageSize(), "the requested page shape is preserved");
  }

  private WorkflowRunResponsePage queryWorkspace(String workspace) {
    return queryWorkspace(workspace, Optional.empty());
  }

  private WorkflowRunResponsePage queryWorkspace(
      String workspace, Optional<List<String>> workflowRuns) {
    return workflowRunService.query(
        workspace,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        workflowRuns,
        Optional.empty(),
        Optional.empty());
  }

  private long runCount(String workflowRef) {
    return workflowRunRepository.findByWorkflowRefAndPhaseIn(workflowRef, ALL_PHASES).size();
  }

  /** A Workflow owned by the given workspace, plus one run of it. Returns the run id. */
  private String workflowOwnedBy(String workspace, String workflowRef) {
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowRef,
        workflowRef,
        Optional.empty(),
        Optional.empty());
    return savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running).getId();
  }
}
