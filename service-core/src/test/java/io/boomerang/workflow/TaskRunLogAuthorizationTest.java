package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.LogClient;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The guard and the scrub on {@code GET /api/v2/taskrun/&#123;taskRunId&#125;/log}.
 *
 * <p>F3 moved this operation from the deleted {@code api.WorkspaceTaskRunService} pass-through into
 * {@link WorkflowRunService#streamTaskRunLog}. Nothing pinned either half at the pass-through, so
 * its call path changed shape untested: this pins that a caller who cannot reach the TaskRun's
 * owning WorkflowRun is refused, that a caller who can is served, and that the served stream still
 * has the owning run's password-typed values replaced.
 *
 * <p>Both identity halves are exercised, because they refuse (or do not refuse) for different
 * reasons. A {@code session} principal is refused by the USER anchor {@code
 * RelationshipService.check} walks from. A {@code global} token is NOT refused - {@code check}
 * returns true unconditionally for global scope (RelationshipService:417-419) - and this route
 * cannot narrow it, because it carries no workspace path segment to pass as an intermediate. That
 * is the pre-existing shape carried over unchanged, and it is pinned here so a future change to
 * either side is a deliberate one.
 */
class TaskRunLogAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "trlog-authz-member";
  private static final String MY_WORKSPACE = "trlog-authz-my-ws";
  private static final String FOREIGN_WORKSPACE = "trlog-authz-foreign-ws";
  private static final String PASSWORD_PARAM = "trlogToken";
  private static final String SECRET = "trlog-p4ssw0rd";

  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  // The only external the stream touches: the agent's log endpoint. Stubbed so the served path
  // produces real bytes to assert on without an agent.
  @MockitoBean private LogClient logClient;

  private String myTaskRunId;
  private String foreignTaskRunId;

  /**
   * Seeds under the base class's global identity (writing relationship edges a member is not
   * entitled to write), then replaces it with the session member. Runs after {@code
   * establishTestIdentity}.
   */
  @BeforeEach
  void establishMemberIdentity() {
    seedRelationshipRoot();
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

    myTaskRunId = ownedTaskRun(MY_WORKSPACE, "trlog-mine");
    foreignTaskRunId = ownedTaskRun(FOREIGN_WORKSPACE, "trlog-theirs");

    StreamingResponseBody rawLog =
        out -> out.write(("connected as " + SECRET + "\n").getBytes(StandardCharsets.UTF_8));
    when(logClient.streamLog(any(), any(), any())).thenReturn(rawLog);

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void aTaskRunWhoseOwningRunTheCallerCannotReachIsRefused() {
    assertRefused(
        () -> workflowRunService.streamTaskRunLog(foreignTaskRunId),
        "PERMISSION_DENIED",
        "foreign taskrun log");
  }

  @Test
  void aTaskRunTheCallerOwnsIsServed() {
    assertNotNull(
        workflowRunService.streamTaskRunLog(myTaskRunId),
        "the guard must not refuse a TaskRun whose owning run the caller can reach");
  }

  @Test
  void anUnknownOrBlankTaskRunIsRejectedBeforeAnyRelationshipCall() {
    assertRefused(
        () -> workflowRunService.streamTaskRunLog("   "), "TASKRUN_INVALID_REF", "blank id");
    assertRefused(
        () -> workflowRunService.streamTaskRunLog("trlog-does-not-exist"),
        "TASKRUN_INVALID_REF",
        "unknown id");
  }

  /**
   * The global half. {@code check} returns true unconditionally for a global-scope token and this
   * route has no workspace segment to narrow with, so a global caller reaches any TaskRun's log.
   * Carried over from the pass-through unchanged - pinned, not fixed.
   */
  @Test
  void aGlobalTokenReachesAnyTaskRunsLogBecauseTheRouteCarriesNoWorkspace() {
    installGlobalIdentity();
    assertNotNull(
        workflowRunService.streamTaskRunLog(foreignTaskRunId),
        "a global token is not narrowed by this route - see the class javadoc");
    assertNotNull(
        workflowRunService.streamTaskRunLog(myTaskRunId), "and reaches its own the same way");
  }

  @Test
  void theServedStreamHasTheOwningRunsPasswordValuesReplaced() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    workflowRunService.streamTaskRunLog(myTaskRunId).writeTo(sink);
    String streamed = sink.toString(StandardCharsets.UTF_8);

    assertTrue(
        streamed.contains(DataAdapterUtil.REDACTED),
        "the password-typed param's resolved value must be replaced: " + streamed);
    assertFalse(streamed.contains(SECRET), "the raw secret must not reach the caller: " + streamed);
  }

  private void installGlobalIdentity() {
    Token global = new Token(AuthScope.global);
    global.setPrincipal("trlog-authz-global");
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(global.getPrincipal(), null);
    authentication.setDetails(global);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private void assertRefused(Executable operation, String reason, String label) {
    BoomerangException ex =
        assertThrows(BoomerangException.class, operation, label + " must be refused");
    assertEquals(reason, ex.getReason(), label + " must fail with " + reason);
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

  /**
   * A completed TaskRun under a WorkflowRun the given workspace owns, whose revision declares one
   * {@code password} param and whose run carries its resolved value - the exact join {@code
   * DataAdapterUtil.sensitiveValues} performs.
   */
  private String ownedTaskRun(String workspace, String prefix) {
    AbstractParam param = new AbstractParam();
    param.setName(PASSWORD_PARAM);
    param.setType(FieldType.PASSWORD.value());
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setWorkflowRef(prefix + "-wf");
    revision.setVersion(1);
    revision.setParams(List.of(param));
    revision = workflowRevisionRepository.save(revision);

    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(prefix + "-wf");
    run.setWorkflowRevisionRef(revision.getId());
    run.setStatus(RunStatus.succeeded);
    run.setPhase(RunPhase.completed);
    run.setCreationDate(new Date());
    run.setParams(List.of(new RunParam(PASSWORD_PARAM, SECRET)));
    run = workflowRunRepository.save(run);

    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        run.getId(),
        run.getId(),
        Optional.empty(),
        Optional.empty());

    TaskRunEntity taskRun =
        savedTaskRun(
            prefix + "-task",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    return taskRun.getId();
  }
}
