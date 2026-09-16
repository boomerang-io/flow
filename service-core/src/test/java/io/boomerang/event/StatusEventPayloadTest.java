package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.event.enums.EventPayload;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins both outbound status payload shapes. The thin default carries the identity and lifecycle of
 * a run and nothing else - no params, no results, no annotations - so a sink URL never receives
 * parameter or result values; {@code full} restores the whole public run model. The CloudEvent
 * envelope (type, source, subject, id) is the same either way.
 */
class StatusEventPayloadTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final List<String> THIN_WORKFLOWRUN_KEYS =
      List.of("id", "workflowRef", "status", "phase", "labels", "creationDate", "startTime", "duration");

  private static final List<String> THIN_TASKRUN_KEYS =
      List.of(
          "id", "workflowRef", "workflowRunRef", "status", "statusReason", "phase", "labels",
          "creationDate", "startTime", "duration");

  @Test
  void thinWorkflowRunEventCarriesIdentityAndLifecycleOnly() throws Exception {
    JsonNode data = payload(EventFactory.buildStatusUpdateEvent(workflowRun(), EventPayload.thin));

    assertThat(data.propertyNames()).containsExactlyInAnyOrderElementsOf(THIN_WORKFLOWRUN_KEYS);
    assertThat(data.get("id").asString()).isEqualTo("wfr-1");
    assertThat(data.get("workflowRef").asString()).isEqualTo("wf-1");
    assertThat(data.get("status").asString()).isEqualTo("succeeded");
    assertThat(data.get("phase").asString()).isEqualTo("completed");
    assertThat(data.get("labels").get("initiatorId").asString()).isEqualTo("user-1");
  }

  @Test
  void thinTaskRunEventCarriesIdentityAndLifecycleOnly() throws Exception {
    JsonNode data = payload(EventFactory.buildStatusUpdateEvent(taskRun(), EventPayload.thin));

    assertThat(data.propertyNames()).containsExactlyInAnyOrderElementsOf(THIN_TASKRUN_KEYS);
    assertThat(data.get("workflowRunRef").asString()).isEqualTo("wfr-1");
    assertThat(data.get("statusReason").asString()).isEqualTo("JobFailed");
  }

  @Test
  void aThinPayloadNeverCarriesParamsResultsOrAnnotations() throws Exception {
    for (JsonNode data :
        List.of(
            payload(EventFactory.buildStatusUpdateEvent(workflowRun(), EventPayload.thin)),
            payload(EventFactory.buildStatusUpdateEvent(taskRun(), EventPayload.thin)))) {
      assertThat(data.propertyNames()).doesNotContain("params", "results", "annotations");
      assertThat(data.toString()).doesNotContain("api-key", "s3cret", "result-value");
    }
  }

  @Test
  void theFullPayloadIsThePublicRunModel() throws Exception {
    JsonNode workflowRunData =
        payload(EventFactory.buildStatusUpdateEvent(workflowRun(), EventPayload.full));
    assertThat(workflowRunData.propertyNames()).contains("params", "results", "annotations");
    assertThat(workflowRunData.get("params").get(0).get("value").asString()).isEqualTo("s3cret");

    JsonNode taskRunData = payload(EventFactory.buildStatusUpdateEvent(taskRun(), EventPayload.full));
    assertThat(taskRunData.propertyNames()).contains("params", "results", "spec", "name");
    assertThat(taskRunData.get("results").get(0).get("value").asString()).isEqualTo("result-value");
  }

  @Test
  void theCloudEventEnvelopeIsTheSameForBothPayloads() throws Exception {
    var thin = EventFactory.buildStatusUpdateEvent(workflowRun(), EventPayload.thin).toCloudEvent();
    var full = EventFactory.buildStatusUpdateEvent(workflowRun(), EventPayload.full).toCloudEvent();

    assertThat(thin.getType()).isEqualTo("io.boomerang.event.status.workflowrun").isEqualTo(full.getType());
    assertThat(thin.getSource()).isEqualTo(full.getSource());
    assertThat(thin.getSubject())
        .isEqualTo("/workflowrun/wfr-1/status/succeeded")
        .isEqualTo(full.getSubject());
  }

  private static JsonNode payload(io.boomerang.event.model.Event event) throws Exception {
    return MAPPER.readTree(
        new String(event.toCloudEvent().getData().toBytes(), StandardCharsets.UTF_8));
  }

  private static WorkflowRunEntity workflowRun() {
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.setId("wfr-1");
    entity.setWorkflowRef("wf-1");
    entity.setStatus(RunStatus.succeeded);
    entity.setPhase(RunPhase.completed);
    entity.setCreationDate(new Date(0));
    entity.setStartTime(new Date(1000));
    entity.setDuration(2000);
    entity.setLabels(Map.of("initiatorId", "user-1"));
    entity.setAnnotations(Map.of("boomerang.io/kind", "WorkflowRun"));
    entity.setParams(List.of(new RunParam("password", "s3cret")));
    entity.setResults(List.of(new RunResult("token", "api-key")));
    return entity;
  }

  private static TaskRunEntity taskRun() {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId("tr-1");
    entity.setName("deploy");
    entity.setType(TaskType.template);
    entity.setWorkflowRef("wf-1");
    entity.setWorkflowRunRef("wfr-1");
    entity.setStatus(RunStatus.failed);
    entity.setPhase(RunPhase.completed);
    entity.setStatusReason("JobFailed");
    entity.setCreationDate(new Date(0));
    entity.setStartTime(new Date(1000));
    entity.setDuration(2000);
    entity.setLabels(Map.of("initiatorId", "user-1"));
    entity.setAnnotations(Map.of("boomerang.io/kind", "TaskRun"));
    entity.setParams(List.of(new RunParam("password", "s3cret")));
    entity.setResults(List.of(new RunResult("output", "result-value")));
    return entity;
  }
}
