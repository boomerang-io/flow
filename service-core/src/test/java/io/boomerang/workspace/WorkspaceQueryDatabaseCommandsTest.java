package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.engine.DatabaseCommands.Recorded;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.model.ApproverGroupRequest;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The workspace query reads each page in a fixed number of database commands, however many
 * workspaces the page holds, for a member and for a global caller; and a workspace on a page reads
 * exactly as it does alone. Each query runs inside a request scope, as a served one does, so the
 * relationship graph's per-request memo is live.
 */
class WorkspaceQueryDatabaseCommandsTest extends AbstractEngineIntegrationTest {

  private static final int WORKSPACES = 60;
  private static final int SMALL_PAGE = 10;
  private static final String TASK_SLUG = "workspace-query-task";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenService tokenService;

  // Built once for the class: the context and its database are shared, and sixty creates are slow.
  private static String userId;
  private static final List<String> names = new ArrayList<>();

  @BeforeEach
  void aMemberOfSixtyWorkspacesOneOfThemBusy() {
    if (userId != null) {
      return;
    }
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    String nonce = Long.toString(System.nanoTime());
    UserEntity user = new UserEntity();
    user.setEmail("workspace-query-" + nonce + "@test.local");
    user.setName("Workspace Query " + nonce);
    user.setType(UserType.user);
    user.setStatus(UserStatus.active);
    userId = userRepository.save(user).getId();
    relationshipService.createNode(RelationshipType.USER, userId, user.getEmail(), Optional.empty());
    signInAsMember();
    for (int i = 0; i < WORKSPACES; i++) {
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName(String.format("wsq-%s-%02d", nonce, i));
      request.setDisplayName(request.getName());
      names.add(workspaceService.create(request).getName());
    }
    // The first workspace carries an approver group, a workflow and a running run of it, so every
    // per-workspace figure on the page has something to count.
    String busy = names.get(0);
    // Permissions resolved again now the workspaces exist, as the next request's would be.
    signInAsMember();
    ApproverGroupRequest group = new ApproverGroupRequest();
    group.setName("approvers");
    group.setApprovers(List.of(userId));
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setApproverGroups(List.of(group));
    workspaceService.patch(busy, patch);
    workflowService.create(busy, runnableWorkflow(busy + "-wf", TASK_SLUG));
    String workflowRef =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, busy, Optional.empty()).get(0);
    savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
  }

  @Test
  void aPageCostsTheSameCommandsForTenWorkspacesAsForSixty() {
    signInAsMember();
    assertConstantCost("member");
    signInAsGlobal();
    assertConstantCost("global");
  }

  @Test
  void aWorkspaceOnAPageReadsAsItDoesAlone() {
    signInAsMember();
    List<Workspace> page = query(WORKSPACES).getContent();
    assertThat(page).extracting(Workspace::getName).containsExactlyElementsOf(names);

    for (Workspace listed : page) {
      assertThat(listed)
          .usingRecursiveComparison()
          .as(listed.getName())
          .isEqualTo(workspaceService.get(listed.getName()));
    }
    Workspace busy = page.get(0);
    assertThat(busy.getMembers()).singleElement().satisfies(m -> assertThat(m.getId()).isEqualTo(userId));
    assertThat(busy.getApproverGroups())
        .singleElement()
        .satisfies(g -> assertThat(g.getApprovers()).extracting("id").containsExactly(userId));
    assertThat(busy.getQuotas().getCurrentWorkflowCount()).isEqualTo(1);
    assertThat(busy.getQuotas().getCurrentConcurrentRuns()).isEqualTo(1);
    assertThat(busy.getQuotas().getCurrentRuns()).isEqualTo(1);
    Workspace idle = page.get(1);
    assertThat(idle.getMembers()).hasSize(1);
    assertThat(idle.getApproverGroups()).isEmpty();
    assertThat(idle.getQuotas().getCurrentWorkflowCount()).isZero();
    assertThat(idle.getQuotas().getCurrentConcurrentRuns()).isZero();
  }

  private void assertConstantCost(String caller) {
    Recorded<Page<Workspace>> small = inRequest(() -> DatabaseCommands.record(() -> query(SMALL_PAGE)));
    long started = System.nanoTime();
    Recorded<Page<Workspace>> large = inRequest(() -> DatabaseCommands.record(() -> query(WORKSPACES)));
    long millis = (System.nanoTime() - started) / 1_000_000;
    assertThat(small.result().getContent()).hasSize(SMALL_PAGE);
    assertThat(large.result().getContent()).hasSize(WORKSPACES);
    System.out.printf(
        "DB COMMANDS | workspace query (%s) | %d workspaces: %d commands | %d workspaces: %d commands, %d ms%n",
        caller, SMALL_PAGE, small.commands().size(), WORKSPACES, large.commands().size(), millis);
    assertThat(large.commands().size()).as(caller).isEqualTo(small.commands().size());
  }

  // The page of this test's workspaces, by name.
  private Page<Workspace> query(int limit) {
    return workspaceService.query(
        Optional.of(0),
        Optional.of(limit),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(names));
  }

  private static <T> T inRequest(Supplier<T> operation) {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      return operation.get();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private void signInAsGlobal() {
    Token principal = new Token(AuthScope.global);
    principal.setPrincipal("workspace-query-global");
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  /** Session identity with permissions resolved as TokenService does per request. */
  private void signInAsMember() {
    UserEntity user = userRepository.findById(userId).orElseThrow();
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userId);
    principal.setPermissions(tokenService.resolvePermissionsForUser(user));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
