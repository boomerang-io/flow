package io.boomerang.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.api.WorkspaceWorkflowService;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowRunService;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link WebhookEventService#processEvent} used to call {@code relationshipService.check(...)}
 * as a bare statement - the boolean was discarded, so the gate never actually rejected anything.
 * Any caller holding a webhook-capable token could trigger any workflow in any workspace. The
 * fix must actually branch: a caller with no relationship to the target workflow is rejected, and
 * a caller that does have the relationship is unaffected.
 */
class WebhookEventAuthorizationTest extends AbstractEngineIntegrationTest {

  @Autowired private RelationshipService realRelationshipService;

  private WorkspaceWorkflowService workspaceWorkflowService;
  private WorkflowRunService workflowRunService;
  private WebhookEventService webhookEventService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aWorkflowThePrincipalHasNoRelationshipToIsRejected() {
    seedRelationshipRoot();
    seedFixture("own"); // the principal's own workspace/workflow
    seedFixture("other"); // a real workflow the principal has no path to
    principalMemberOf("own-owner", "own-ws");
    setUpService();

    CloudEvent event = eventFor("other-workflow");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> webhookEventService.processEvent(event, Optional.of("other-workflow")));
    assertEquals("PERMISSION_DENIED", ex.getReason());
    verify(workspaceWorkflowService, never())
        .submit(any(), any(), any(WorkflowSubmitRequest.class), anyBoolean());
  }

  @Test
  void aWorkflowThePrincipalHasARelationshipToStillRuns() {
    seedRelationshipRoot();
    seedFixture("related-a");
    principalMemberOf("related-a-owner", "related-a-ws");
    setUpService();

    CloudEvent event = eventFor("related-a-workflow");
    WorkflowRun expected = new WorkflowRun();
    expected.setId("run-1");
    when(workspaceWorkflowService.submit(
            eq("related-a-ws"), eq("related-a-workflow"), any(WorkflowSubmitRequest.class), anyBoolean()))
        .thenReturn(expected);

    WorkflowRun result = webhookEventService.processEvent(event, Optional.of("related-a-workflow"));

    assertEquals("run-1", result.getId());
    verify(workspaceWorkflowService)
        .submit(
            eq("related-a-ws"), eq("related-a-workflow"), any(WorkflowSubmitRequest.class), anyBoolean());
  }

  // Wires principal:owner --MEMBER_OF--> workspace:ws --HAS_WORKFLOW--> workflow:<prefix>-workflow
  private void seedFixture(String prefix) {
    realRelationshipService.createNode(
        RelationshipType.USER, prefix + "-owner", prefix + "-owner", Optional.empty());
    realRelationshipService.createNode(
        RelationshipType.WORKSPACE, prefix + "-ws", prefix + "-ws", Optional.empty());
    realRelationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        prefix + "-ws",
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        prefix + "-workflow",
        prefix + "-workflow",
        Optional.empty(),
        Optional.empty());
  }

  private void principalMemberOf(String userRef, String workspaceRef) {
    realRelationshipService.createEdge(
        RelationshipType.USER,
        userRef,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        workspaceRef,
        Optional.empty());

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userRef);
    Authentication authentication = new UsernamePasswordAuthenticationToken(userRef, null);
    ((UsernamePasswordAuthenticationToken) authentication).setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private void setUpService() {
    workspaceWorkflowService = mock(WorkspaceWorkflowService.class);
    workflowRunService = mock(WorkflowRunService.class);
    webhookEventService =
        new WebhookEventService(
            workspaceWorkflowService,
            workflowRunService,
            Optional.empty(),
            Optional.empty(),
            realRelationshipService);
  }

  private static CloudEvent eventFor(String subjectWorkflowRef) {
    return CloudEventBuilder.v1()
        .withId(UUID.randomUUID().toString())
        .withSource(URI.create("urn:webhook-authorization-test"))
        .withType("io.boomerang.test.webhook")
        .withSubject(subjectWorkflowRef + "/topic")
        .withData(
            "application/json", "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8))
        .build();
  }
}
