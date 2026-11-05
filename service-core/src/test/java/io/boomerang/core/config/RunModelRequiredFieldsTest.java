package io.boomerang.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every property the served contract marks required is on the wire, and every property sent as
 * {@code null} is one the contract lets be null, for the run models as the engine leaves them: a
 * run just submitted and not started, its task runs just created, both read back and queried, and
 * a superseded attempt. The lists are read from the served document, so a property added to them
 * is held to what the server sends.
 */
class RunModelRequiredFieldsTest extends AbstractEngineIntegrationTest {

  private static final String TASK = "required-fields-test-task";

  @Autowired private WebApplicationContext context;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;

  private MockMvc mockMvc;
  private JsonNode schemas;
  // How many objects of each schema the walk checked, so no assertion passes on nothing.
  private final Map<String, Integer> checked = new TreeMap<>();
  private String submitted;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    schemas =
        read(get("/api/docs/spec/" + OpenApiConfiguration.API_GROUP))
            .get("components")
            .get("schemas");
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    // A task that declares a result, so a task run carries one before anything sets its value.
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.TASK, TASK)) {
      Task task = new Task();
      task.setName(TASK);
      task.setType(TaskType.template);
      task.getSpec().setImage("busybox:latest");
      task.getSpec().setCommand(List.of("echo"));
      ResultSpec result = new ResultSpec();
      result.setName("out");
      task.getSpec().setResults(List.of(result));
      taskService.createGlobal(task);
    }
  }

  @AfterEach
  void endSubmittedRun() {
    if (submitted != null) {
      workflowRunService.cancel(submitted);
      submitted = null;
    }
  }

  @Test
  void aRunJustSubmittedSendsEveryRequiredField() throws Exception {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("required-fields");
    request.setDisplayName("required-fields");
    String workspace = workspaceService.create(request).getName();
    Workflow workflow = runnableWorkflow("required-fields-workflow", TASK);
    AbstractParam param = new AbstractParam();
    param.setName("greeting");
    param.setType("text");
    param.setDefaultValue("hello");
    workflow.setParams(List.of(param));
    workflowService.create(workspace, workflow);

    String runs = "/api/v2/workspace/" + workspace + "/workflowrun";
    JsonNode run =
        read(
            post("/api/v2/workspace/" + workspace + "/workflow/" + workflow.getName() + "/submit")
                .param("start", "false")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    submitted = run.get("id").asString();
    assertSends("WorkflowRun", run, "submitted run");
    assertSends("WorkflowRun", read(get(runs + "/" + submitted)), "run read with its tasks");
    assertSends(
        "PageWorkflowRun",
        read(get(runs + "/query").param("workflowruns", submitted)),
        "queried runs");
    assertSends(
        "PageTaskRun",
        read(
            get("/api/v2/workspace/" + workspace + "/taskrun/query")
                .param("workflowruns", submitted)),
        "queried task runs");

    assertThat(checked)
        .containsKeys(
            "WorkflowRun", "TaskRun", "TaskRunSpec", "RunParam", "RunResult", "PageWorkflowRun",
            "PageTaskRun");
  }

  @Test
  void anAttemptARequeueSupersededSendsEveryRequiredField() {
    RunAttempt attempt = RunAttempt.ended(new TaskRunEntity(), RunStatus.failed, null, null);

    assertSends("RunAttempt", objectMapper.valueToTree(attempt), "superseded attempt");
  }

  @Test
  void theRunModelsDeclareWhatTheyAlwaysSend() {
    OpenApiConfiguration.REQUIRED.forEach(
        (schema, properties) ->
            assertThat(schemas.get(schema).get("required").valueStream().map(JsonNode::asString))
                .as("%s required", schema)
                .containsExactlyInAnyOrderElementsOf(properties));
  }

  private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  // The object sends every property its schema requires; each property it sends is checked
  // against that property's schema in turn.
  private void assertSends(String schema, JsonNode value, String where) {
    JsonNode declared = schemas.get(schema);
    checked.merge(schema, 1, Integer::sum);
    if (declared.has("required")) {
      declared
          .get("required")
          .forEach(
              property ->
                  assertThat(value.has(property.asString()))
                      .as("%s (%s) sends %s", where, schema, property.asString())
                      .isTrue());
    }
    declared
        .get("properties")
        .properties()
        .forEach(
            property -> {
              if (value.has(property.getKey())) {
                assertValue(
                    property.getValue(),
                    value.get(property.getKey()),
                    where + "." + property.getKey());
              }
            });
  }

  private void assertValue(JsonNode declared, JsonNode value, String where) {
    if (value.isNull()) {
      assertThat(allowsNull(declared))
          .as("%s is sent as null, so the contract lets it be null", where)
          .isTrue();
    } else if (declared.has("$ref")) {
      assertSends(declared.get("$ref").asString().replaceAll("^.*/", ""), value, where);
    } else if (declared.has("items")) {
      for (int index = 0; index < value.size(); index++) {
        assertValue(declared.get("items"), value.get(index), where + "[" + index + "]");
      }
    }
  }

  // A schema with no type takes any value; a typed one takes null only when it lists the type.
  private static boolean allowsNull(JsonNode declared) {
    if (!declared.has("type")) {
      return !declared.has("$ref");
    }
    return declared.get("type").isArray()
        && declared.get("type").valueStream().anyMatch(type -> "null".equals(type.asString()));
  }
}
