package io.boomerang.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@code GET /api/v2/workspace/{workspace}/schedule/query}: {@code labels} keeps the schedules that
 * carry every label given, alone or beside {@code workflows}; and a schedule reads back the
 * creation date it was stored with, not the time of the read.
 */
class ScheduleQueryTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "schedule-query-test-task";

  @Autowired private ScheduleService scheduleService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private MongoTemplate mongoTemplate;

  private String workspace;
  private String first;
  private String second;

  @BeforeEach
  void twoWorkflowsInAWorkspace() {
    seedGlobalTask(TASK_SLUG);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("schedule-query-ws-" + suffix);
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    first = workflow("schedule-query-first-" + suffix);
    second = workflow("schedule-query-second-" + suffix);
  }

  @Test
  void oneLabelKeepsTheSchedulesThatCarryIt() {
    String redGold = create(first, "red-gold", Map.of("team", "red", "tier", "gold"));
    String red = create(first, "red", Map.of("team", "red"));
    create(first, "blue", Map.of("team", "blue"));

    assertThat(ids(query(Optional.empty(), List.of("team=red")))).containsExactlyInAnyOrder(redGold, red);
  }

  @Test
  void twoLabelsKeepOnlyTheSchedulesThatCarryBoth() {
    String redGold = create(first, "red-gold", Map.of("team", "red", "tier", "gold"));
    create(first, "red", Map.of("team", "red"));
    create(first, "gold", Map.of("tier", "gold"));

    assertThat(ids(query(Optional.empty(), List.of("team=red", "tier=gold")))).containsExactly(redGold);
  }

  @Test
  void aLabelNoScheduleCarriesIsAnEmptyPage() {
    create(first, "red", Map.of("team", "red"));

    Page<WorkflowSchedule> page = query(Optional.empty(), List.of("team=green"));

    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isZero();
  }

  @Test
  void aLabelBesideWorkflowsKeepsTheSchedulesThatMatchBoth() {
    create(first, "first-red", Map.of("team", "red"));
    String secondRed = create(second, "second-red", Map.of("team", "red"));
    create(second, "second-blue", Map.of("team", "blue"));

    assertThat(ids(query(Optional.of(List.of(second)), List.of("team=red")))).containsExactly(secondRed);
  }

  @Test
  void aScheduleReadsTheCreationDateItWasStoredWith() throws InterruptedException {
    String id = create(first, "dated", Map.of("dated", "yes"));
    Date stored = rawSchedule(id).getDate("creationDate");
    assertThat(stored).as("the create stores a creation date").isNotNull();

    Thread.sleep(50);
    Date firstRead = only(query(Optional.empty(), List.of("dated=yes"))).getCreationDate();
    Thread.sleep(50);
    Date secondRead = only(query(Optional.empty(), List.of("dated=yes"))).getCreationDate();

    assertThat(firstRead).isEqualTo(stored);
    assertThat(secondRead).isEqualTo(stored);
    assertThat(scheduleService.get(workspace, id).getCreationDate()).isEqualTo(stored);
  }

  private String workflow(String name) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    return workflowService.create(workspace, workflow).getName();
  }

  // As the webapp sends one: no creation date.
  private String create(String workflow, String name, Map<String, String> labels) {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setName(name);
    schedule.setWorkflowRef(workflow);
    schedule.setType(WorkflowScheduleType.runOnce);
    schedule.setStatus(WorkflowScheduleStatus.inactive);
    schedule.setDateSchedule(new Date(System.currentTimeMillis() + 86_400_000L));
    schedule.setTimezone("UTC");
    schedule.setLabels(new HashMap<>(labels));
    return scheduleService.create(workspace, schedule).getId();
  }

  private Page<WorkflowSchedule> query(Optional<List<String>> workflows, List<String> labels) {
    return scheduleService.query(
        workspace,
        Optional.of(0),
        Optional.of(50),
        Sort.by("creationDate"),
        Optional.empty(),
        Optional.empty(),
        workflows,
        Optional.of(labels));
  }

  private static List<String> ids(Page<WorkflowSchedule> page) {
    return page.getContent().stream().map(WorkflowSchedule::getId).toList();
  }

  private static WorkflowSchedule only(Page<WorkflowSchedule> page) {
    assertThat(page.getContent()).hasSize(1);
    return page.getContent().get(0);
  }

  private Document rawSchedule(String id) {
    return mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(id)),
        Document.class,
        mongoTemplate.getCollectionName(WorkflowScheduleEntity.class));
  }
}
