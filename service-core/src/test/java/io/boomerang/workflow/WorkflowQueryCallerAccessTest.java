package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowResponsePage;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A workspace's workflow query lists, for every kind of caller, the workflows the relationship
 * lookup names for that caller: all of the workspace's for a member, the workspace's own token and
 * a global token, with names given or not; and for a workflow's own token, as the scheduler's
 * credential is, its own workflow when the query names it and nothing otherwise.
 */
class WorkflowQueryCallerAccessTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "workflow-query-caller-task";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private UserRepository userRepository;

  private String workspace;
  private String workspaceId;
  private String firstName;
  private String firstId;
  private String secondId;
  private String memberId;

  @BeforeEach
  void aWorkspaceOfTwoWorkflowsAndAMember() {
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    String nonce = Long.toString(System.nanoTime());
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("wqc-" + nonce);
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    workspaceId =
        relationshipService
            .nodesBySlug(RelationshipType.WORKSPACE, List.of(workspace))
            .get(workspace)
            .getRef();
    firstName = "wqc-first-" + nonce;
    workflowService.create(workspace, runnableWorkflow(firstName, TASK_SLUG));
    workflowService.create(workspace, runnableWorkflow("wqc-second-" + nonce, TASK_SLUG));
    firstId = workflowId(firstName);
    secondId = workflowId("wqc-second-" + nonce);

    UserEntity user = new UserEntity();
    user.setEmail("wqc-" + nonce + "@test.local");
    user.setName("Workflow Query Caller " + nonce);
    user.setType(UserType.user);
    user.setStatus(UserStatus.active);
    memberId = userRepository.save(user).getId();
    relationshipService.createNode(RelationshipType.USER, memberId, user.getEmail(), Optional.empty());
    relationshipService.setMemberRole(memberId, workspace, "editor");
  }

  @Test
  void aCallerWhoReachesTheWorkspaceIsListedEveryWorkflowNamedOrNot() {
    Map<String, Token> callers = new LinkedHashMap<>();
    callers.put("member", token(AuthScope.session, memberId, null, workspaceId));
    callers.put("workspace token", token(AuthScope.key, workspaceId, null, workspaceId));
    Token global = new Token(AuthScope.global);
    global.setPrincipal("workflow-query-global");
    global.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    callers.put("global token", global);

    callers.forEach(
        (caller, identity) -> {
          signIn(identity);
          assertThat(queried(Optional.empty())).as(caller).containsExactlyInAnyOrder(firstId, secondId);
          assertThat(queried(Optional.of(List.of(firstId)))).as(caller).containsExactly(firstId);
          assertThat(queried(Optional.of(List.of(firstName)))).as(caller).containsExactly(firstId);
          assertThat(queried(Optional.of(List.of(firstId, secondId))))
              .as(caller)
              .containsExactlyInAnyOrder(firstId, secondId);
          assertThat(queried(Optional.of(List.of("no-such-workflow")))).as(caller).isEmpty();
          assertListedAsTheLookupNames(caller);
        });
  }

  @Test
  void aWorkflowsOwnTokenIsListedItsOwnWorkflowWhenTheQueryNamesIt() {
    signIn(token(AuthScope.key, firstId, TokenActorKind.WORKFLOW, firstId));

    assertThat(
            relationshipService.grantsIn(
                workspace, PermissionResource.WORKFLOW, PermissionAction.READ))
        .as("the workspace's routes admit the token")
        .isTrue();
    assertThat(queried(Optional.of(List.of(firstId)))).containsExactly(firstId);
    // Its node has no edge to follow: its sibling, and an unnamed listing, are not its to see.
    assertThat(queried(Optional.of(List.of(secondId)))).isEmpty();
    assertThat(queried(Optional.of(List.of(firstName)))).isEmpty();
    assertThat(queried(Optional.empty())).isEmpty();
    assertListedAsTheLookupNames("workflow token");
  }

  // The query's workflows equal the relationship lookup's, for no names and for each naming.
  private void assertListedAsTheLookupNames(String caller) {
    for (Optional<List<String>> names :
        List.of(
            Optional.<List<String>>empty(),
            Optional.of(List.of(firstId)),
            Optional.of(List.of(secondId)),
            Optional.of(List.of(firstName)),
            Optional.of(List.of(firstId, secondId)),
            Optional.of(List.<String>of()))) {
      assertThat(queried(names))
          .as("%s naming %s", caller, names)
          .containsExactlyInAnyOrderElementsOf(
              relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, workspace, names));
    }
  }

  // The ids on the workspace's page of its own workflows: templates and starters are left out.
  private List<String> queried(Optional<List<String>> names) {
    WorkflowResponsePage page =
        workflowService.query(
            workspace,
            Optional.of(100),
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            names,
            Optional.of(List.of("workflow")));
    return (page.getContent() == null)
        ? List.of()
        : page.getContent().stream().map(Workflow::getId).toList();
  }

  private String workflowId(String name) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }

  // A token of the class with one grant over everything, held where its class holds it.
  private static Token token(
      AuthScope type, String principal, TokenActorKind actorKind, String grantPrincipal) {
    Token token = new Token(type);
    token.setPrincipal(principal);
    token.setActorKind(actorKind);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, grantPrincipal, List.of("**/**"))));
    return token;
  }

  private static void signIn(Token identity) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(identity.getPrincipal(), null);
    authentication.setDetails(identity);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
