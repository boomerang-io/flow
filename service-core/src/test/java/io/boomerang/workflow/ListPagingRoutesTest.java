package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.WorkspaceEntity;
import jakarta.servlet.Filter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every list route pages the same way over HTTP: {@code page}, {@code limit}, {@code order} for the
 * direction and {@code sort} for the field; lists ordered by time read newest first and lists of
 * named things by name; a missing {@code limit} reads every match except on the audit trail, which
 * reads 25 and at most 100. The workflow and task lists still take the direction as {@code sort}
 * through 5.x.
 */
class ListPagingRoutesTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private MongoTemplate mongoTemplate;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String bearer;
  private String suffix;
  private String workspace;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    try {
      TokenCreateRequest request = new TokenCreateRequest();
      request.setType(AuthScope.global);
      request.setName("list-paging-" + UUID.randomUUID());
      request.setPermissions(List.of("**/**"));
      bearer = "Bearer " + tokenService.create(request).getToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
    suffix = UUID.randomUUID().toString().substring(0, 8);
    workspace = "list-paging-ws-" + suffix;
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        workspace,
        workspace,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void workflowsReadOldestFirstAndStillTakeTheDirectionAsSort() throws Exception {
    WorkflowEntity older = workflow(new Date(System.currentTimeMillis() - 60_000));
    WorkflowEntity newer = workflow(new Date());
    String route = "/api/v2/workspace/" + workspace + "/workflow/query?types=workflow";
    List<String> oldestFirst = List.of(older.getName(), newer.getName());
    List<String> newestFirst = List.of(newer.getName(), older.getName());

    assertThat(values(route, "$.content[*].name")).isEqualTo(oldestFirst);
    assertThat(values(route + "&order=DESC", "$.content[*].name")).isEqualTo(newestFirst);
    for (String legacy : List.of("DESC", "desc", "Desc")) {
      assertThat(values(route + "&sort=" + legacy, "$.content[*].name"))
          .as("sort=%s", legacy)
          .isEqualTo(newestFirst);
    }
    // order wins over the old name, and a sort that is not a direction is ignored.
    assertThat(values(route + "&order=ASC&sort=DESC", "$.content[*].name")).isEqualTo(oldestFirst);
    assertThat(values(route + "&sort=name", "$.content[*].name")).isEqualTo(oldestFirst);
  }

  @Test
  void runsReadNewestFirst() throws Exception {
    WorkflowEntity workflow = workflow(new Date());
    WorkflowRunEntity older = run(workflow.getId(), new Date(System.currentTimeMillis() - 60_000));
    WorkflowRunEntity newer = run(workflow.getId(), new Date());
    String route = "/api/v2/workspace/" + workspace + "/workflowrun/query";

    assertThat(values(route, "$.content[*].id")).containsExactly(newer.getId(), older.getId());
    assertThat(values(route + "?order=ASC", "$.content[*].id"))
        .containsExactly(older.getId(), newer.getId());
  }

  @Test
  void actionsReadNewestFirstAndEveryOneWithoutALimit() throws Exception {
    WorkflowEntity workflow = workflow(new Date());
    long now = System.currentTimeMillis();
    for (int i = 0; i < 12; i++) {
      approval(workflow.getId(), new Date(now - (12 - i) * 1_000L));
    }
    String route = "/api/v2/workspace/" + workspace + "/action/query";

    ids(route)
        .andExpect(jsonPath("$.content.length()").value(12))
        .andExpect(jsonPath("$.totalElements").value(12));
    ids(route + "?limit=5")
        .andExpect(jsonPath("$.content.length()").value(5))
        .andExpect(jsonPath("$.totalElements").value(12));
  }

  @Test
  void schedulesReadEveryOneWithoutALimitOldestFirst() throws Exception {
    WorkflowEntity workflow = workflow(new Date());
    long now = System.currentTimeMillis();
    for (int i = 0; i < 12; i++) {
      schedule(workflow.getId(), "s" + i, new Date(now - (12 - i) * 1_000L));
    }
    String route = "/api/v2/workspace/" + workspace + "/schedule/query";

    ids(route)
        .andExpect(jsonPath("$.content.length()").value(12))
        .andExpect(jsonPath("$.content[0].name").value("s0"))
        .andExpect(jsonPath("$.content[11].name").value("s11"));
    ids(route + "?order=DESC&limit=3")
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].name").value("s11"))
        .andExpect(jsonPath("$.totalElements").value(12));
  }

  @Test
  void workspacesReadByNameAscending() throws Exception {
    String label = "list-paging-" + suffix;
    String second = namedWorkspace("list-paging-b-" + suffix, label);
    String first = namedWorkspace("list-paging-a-" + suffix, label);

    assertThat(values("/api/v2/workspace/query?labels=" + label + "%3Dyes", "$.content[*].name"))
        .containsExactly(first, second);
  }

  @Test
  void theAuditTrailReadsTwentyFiveByDefaultAndAtMostOneHundred() throws Exception {
    ids("/api/v2/audit").andExpect(jsonPath("$.size").value(25));
    ids("/api/v2/audit?limit=500").andExpect(jsonPath("$.size").value(100));
  }

  private ResultActions ids(String route) throws Exception {
    return mockMvc
        .perform(get(route).header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isOk());
  }

  private List<String> values(String route, String path) throws Exception {
    return JsonPath.read(ids(route).andReturn().getResponse().getContentAsString(), path);
  }

  private WorkflowEntity workflow(Date created) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName("list-paging-wf-" + UUID.randomUUID().toString().substring(0, 8));
    workflow.setCreationDate(created);
    workflow = mongoTemplate.save(workflow);
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setWorkflowRef(workflow.getId());
    revision.setVersion(1);
    mongoTemplate.save(revision);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflow.getId(),
        workflow.getName(),
        Optional.empty(),
        Optional.empty());
    return workflow;
  }

  private WorkflowRunEntity run(String workflowRef, Date created) {
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    run.setCreationDate(created);
    return workflowRunRepository.save(run);
  }

  private void approval(String workflowRef, Date created) {
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowRef);
    action.setType(ActionType.approval);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(created);
    mongoTemplate.save(action);
  }

  private void schedule(String workflowRef, String name, Date created) {
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setWorkflowRef(workflowRef);
    schedule.setName(name);
    schedule.setCronSchedule("0 0 * * *");
    schedule.setTimezone("UTC");
    schedule.setCreationDate(created);
    mongoTemplate.save(schedule);
  }

  private String namedWorkspace(String name, String label) {
    WorkspaceEntity entity = new WorkspaceEntity();
    entity.setName(name);
    entity.setLabels(Map.of(label, "yes"));
    entity = mongoTemplate.save(entity);
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        entity.getId(),
        entity.getName(),
        Optional.empty(),
        Optional.empty());
    return name;
  }
}
