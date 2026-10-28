package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A run a person starts records them: the submit stamps their user id as the run's initiator, and
 * reads of the run, single and listed, name them.
 */
class WorkflowRunInitiatorTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;

  @Test
  void aRunAPersonStartsNamesThemOnTheRunAndInTheList() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest workspaceRequest = new WorkspaceRequest();
    workspaceRequest.setName("run-initiator-" + suffix);
    workspaceRequest.setDisplayName(workspaceRequest.getName());
    String workspace = workspaceService.create(workspaceRequest).getName();
    Workflow workflow = new Workflow();
    workflow.setName("run-initiator-" + suffix);
    String workflowName = workflowService.create(workspace, workflow).getName();

    UserEntity user = new UserEntity();
    user.setName("ada@example.com");
    user.setEmail("ada-" + suffix + "@example.com");
    user.setDisplayName("Ada Lovelace");
    String userId = userRepository.save(user).getId();
    relationshipService.createNode(RelationshipType.USER, userId, user.getName(), Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER, userId, RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, workspace, Optional.empty());
    signInAs(userId);

    WorkflowRun submitted = workflowService.submit(workspace, workflowName, new WorkflowSubmitRequest(), false);

    assertThat(submitted.getInitiatedByRef()).isEqualTo(userId);
    assertThat(workflowRunService.get(submitted.getId(), false).getInitiatedByName()).isEqualTo("Ada Lovelace");
    assertThat(
            workflowRunService
                .query(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(List.of(submitted.getId())),
                    Optional.empty(), Optional.empty())
                .getContent())
        .extracting(WorkflowRun::getInitiatedByName)
        .containsExactly("Ada Lovelace");
  }

  private static void signInAs(String userId) {
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userId);
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
