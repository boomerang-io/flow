package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The public submit route records the trigger from the caller, never the body: a person signed in
 * to the webapp runs {@code manual}, a token {@code api}, and both answer to the Manual switch. A
 * body may send only those two; internal submits keep the trigger they name.
 */
class WorkflowSubmitTriggerTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  private Workspace workspace;
  private String member;

  @BeforeEach
  void aWorkspaceAndAMember() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("trigger-ws-" + System.nanoTime());
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request);
    member = "trigger-member-" + System.nanoTime();
    relationshipService.createNode(RelationshipType.USER, member, member, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        member,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        workspace.getName(),
        Optional.empty());
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aPersonSignedInToTheWebappSubmitsAsManual() {
    String name = saved("trigger-session", true);
    actAs(AuthScope.session, member);

    assertThat(submitOnDemand(name, null).getTrigger()).isEqualTo("manual");
  }

  @Test
  void aTokenSubmitsAsApiWhateverTheBodySays() {
    String name = saved("trigger-key", true);
    actAs(AuthScope.key, workspace.getId());

    assertThat(submitOnDemand(name, null).getTrigger()).isEqualTo("api");
    assertThat(submitOnDemand(name, TriggerEnum.manual).getTrigger()).isEqualTo("api");
  }

  @Test
  void theManualSwitchRefusesBothCallers() {
    String name = saved("trigger-off", false);

    actAs(AuthScope.session, member);
    assertRefused(() -> submitOnDemand(name, null), BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
    actAs(AuthScope.key, workspace.getId());
    assertRefused(() -> submitOnDemand(name, null), BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
  }

  @Test
  void aBodyClaimingAnotherRoutesTriggerIsRefused() {
    String name = saved("trigger-claim", true);
    actAs(AuthScope.key, workspace.getId());

    assertRefused(
        () -> submitOnDemand(name, TriggerEnum.schedule),
        BoomerangError.WORKFLOWRUN_INVALID_TRIGGER);
  }

  @Test
  void aScheduleFiringKeepsItsOwnTrigger() {
    String name = saved("trigger-schedule", true);
    actAs(AuthScope.session, member);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.schedule);

    assertThat(
            workflowService
                .submit(workspace.getName(), name, request, false, "schedule-fired")
                .getTrigger())
        .isEqualTo("schedule");
  }

  private WorkflowRun submitOnDemand(String name, TriggerEnum sent) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(sent);
    return workflowService.submitOnDemand(workspace.getName(), name, request, false);
  }

  private static void assertRefused(Runnable submit, BoomerangError error) {
    assertThatThrownBy(submit::run)
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getReason())
        .isEqualTo(error.getReason());
  }

  private void actAs(AuthScope type, String principal) {
    Token identity = new Token(type);
    identity.setPrincipal(principal);
    identity.setPermissions(
        List.of(
            new ResolvedPermissions(
                PermissionScope.workspace, workspace.getName(), List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null);
    authentication.setDetails(identity);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  // Saved as the base class's global identity, with Manual as given and Schedule on.
  private String saved(String name, boolean manual) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setParams(new LinkedList<>());
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(manual));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(dependency)));
    workflow.setTasks(new LinkedList<>(List.of(start, end)));
    return workflowService.create(workspace.getName(), workflow).getName();
  }
}
