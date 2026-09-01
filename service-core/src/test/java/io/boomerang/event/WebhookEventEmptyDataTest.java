package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A CloudEvent is allowed to carry no data at all. The listener once dereferenced the data
 * unconditionally and threw on an empty body; a data-less event MUST still reach
 * {@code WorkflowService.submit} with the event itself as the only run param.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventEmptyDataTest {

  private static final String WORKFLOW_REF = "workflow-1";
  private static final String WORKSPACE_REF = "workspace-1";

  @Mock private WorkflowService workflowService;
  @Mock private WorkflowRunService workflowRunService;
  @Mock private RelationshipService relationshipService;

  private WebhookEventService webhookEventService;

  @BeforeEach
  void setUp() {
    webhookEventService =
        new WebhookEventService(
            workflowService,
            workflowRunService,
            Optional.empty(),
            Optional.empty(),
            relationshipService);
    when(relationshipService.check(
            RelationshipType.WORKFLOW, WORKFLOW_REF, Optional.empty(), Optional.empty()))
        .thenReturn(true);
    when(relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, WORKFLOW_REF))
        .thenReturn(WORKSPACE_REF);
  }

  @Test
  void anEventWithoutDataIsSubmittedWithOnlyTheEventParam() {
    CloudEvent event =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("urn:webhook-empty-data-test"))
            .withType("io.boomerang.test.webhook")
            .withSubject(WORKFLOW_REF + "/topic")
            .withoutData()
            .build();
    WorkflowRun expected = new WorkflowRun();
    expected.setId("run-1");
    when(workflowService.submit(
            eq(WORKSPACE_REF), eq(WORKFLOW_REF), any(WorkflowSubmitRequest.class), anyBoolean()))
        .thenReturn(expected);

    assertThatCode(() -> webhookEventService.processEvent(event, Optional.of(WORKFLOW_REF)))
        .doesNotThrowAnyException();

    ArgumentCaptor<WorkflowSubmitRequest> request =
        ArgumentCaptor.forClass(WorkflowSubmitRequest.class);
    verify(workflowService)
        .submit(eq(WORKSPACE_REF), eq(WORKFLOW_REF), request.capture(), anyBoolean());
    assertThat(request.getValue().getTrigger()).isEqualTo(TriggerEnum.event);
    assertThat(request.getValue().getParams())
        .extracting(RunParam::getName)
        .contains("event")
        .doesNotContain("data");
  }
}
