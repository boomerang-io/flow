package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace guard on {@code POST /api/v2/workspace/&#123;workspace&#125;/workflow}.
 *
 * <p>{@code WorkflowService.create(team, ...)} wrote the {@code HAS_WORKFLOW} ownership edge into
 * whatever workspace the path named without ever asking whether the caller can reach that
 * workspace - the sibling {@code TaskService.create(team, ...)} checks {@code check(WORKSPACE,
 * team)} first. Every create-shaped entry point funnels here: {@code apply} (when the named
 * Workflow does not exist yet), {@code composeApply} via {@code apply}, and {@code duplicate}, so
 * one guard at the top of {@code create} covers all four routes.
 *
 * <p>Identities are session tokens with permissions resolved by {@code
 * TokenService.resolvePermissionsForUser} against a real MEMBER_OF edge and the seeded {@code
 * workspace/owner} role - the production resolution, not synthetic grants.
 */
class WorkflowCreateAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String MY_WORKSPACE = "wf-create-authz-my-ws";
  private static final String FOREIGN_WORKSPACE = "wf-create-authz-foreign-ws";
  private static final String MEMBER_EMAIL = "wf-create-authz-member@test.local";
  private static final String TASK_SLUG = "wf-create-authz-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TokenService tokenService;

  private String memberId;

  /** Seeds under the base class's global identity, then installs the session member. */
  @BeforeEach
  void establishWorkspaceMember() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    seedOwnerRole();
    workspaceNode(MY_WORKSPACE);
    workspaceNode(FOREIGN_WORKSPACE);

    UserEntity member = userRepository.findByEmailAndStatus(MEMBER_EMAIL, UserStatus.active);
    if (member == null) {
      member = new UserEntity();
      member.setEmail(MEMBER_EMAIL);
      member.setName(MEMBER_EMAIL);
      member.setType(UserType.user);
      member.setStatus(UserStatus.active);
      member = userRepository.save(member);
      relationshipService.createNode(
          RelationshipType.USER, member.getId(), MEMBER_EMAIL, Optional.empty());
      relationshipService.createEdge(
          RelationshipType.USER,
          member.getId(),
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          MY_WORKSPACE,
          Optional.of(Map.of("role", "owner")));
    }
    memberId = member.getId();

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(memberId);
    principal.setPermissions(tokenService.resolvePermissionsForUser(member));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(memberId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void creatingIntoAWorkspaceTheCallerCannotReachIsRefusedBeforeAnythingIsWritten() {
    String name = "wf-create-authz-intruder";

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.create(FOREIGN_WORKSPACE, runnableWorkflow(name, TASK_SLUG)),
            "creating into an unreachable workspace must be refused");
    assertEquals("PERMISSION_DENIED", ex.getReason());

    assertFalse(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKFLOW, name),
        "a refused create must not write the ownership node/edge");
    assertTrue(
        workflowRepository.findAll().stream()
            .map(WorkflowEntity::getName)
            .noneMatch(name::equals),
        "a refused create must not persist the Workflow entity");
  }

  @Test
  void aMemberCreatesIntoTheirOwnWorkspaceAndOwnershipIsRecorded() {
    String name = "wf-create-authz-mine";

    Workflow created = workflowService.create(MY_WORKSPACE, runnableWorkflow(name, TASK_SLUG));

    assertEquals(name, created.getName(), "the member's create must be served");
    assertEquals(
        MY_WORKSPACE,
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef(name)),
        "the created Workflow must be owned by the named workspace");
  }

  private String workflowRef(String name) {
    return relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(MY_WORKSPACE)),
            false)
        .get(0);
  }

  // Anchored under root so a global (ROOT-anchored) identity can resolve these workspaces - the
  // same shape production has, where every workspace hangs off the root node.
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

  /** Mirror of the loader's roles.json workspace/owner document - permission resolution needs it. */
  private void seedOwnerRole() {
    if (roleRepository.findByTypeAndName("workspace", "owner") == null) {
      RoleEntity owner = new RoleEntity();
      owner.setType(PermissionScope.workspace);
      owner.setName("owner");
      owner.setPermissions(List.of("**/**"));
      roleRepository.save(owner);
    }
  }
}
