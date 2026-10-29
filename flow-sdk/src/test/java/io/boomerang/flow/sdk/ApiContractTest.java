package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.flow.sdk.model.Action;
import io.boomerang.flow.sdk.model.ActionStatus;
import io.boomerang.flow.sdk.model.ActionType;
import io.boomerang.flow.sdk.model.Workspace;
import io.boomerang.flow.sdk.model.WorkspaceStatus;
import io.boomerang.flow.sdk.model.Actioner;
import io.boomerang.flow.sdk.model.RetryFrom;
import io.boomerang.flow.sdk.model.RunAttempt;
import io.boomerang.flow.sdk.model.RunParam;
import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunResult;
import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.TaskEnvVar;
import io.boomerang.flow.sdk.model.TaskRun;
import io.boomerang.flow.sdk.model.TaskRunSpec;
import io.boomerang.flow.sdk.model.TaskType;
import io.boomerang.flow.sdk.model.WorkflowRun;
import io.boomerang.flow.sdk.model.WorkflowRunCount;
import io.boomerang.flow.sdk.model.WorkflowRunInsightSummary;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTasks;
import io.boomerang.flow.sdk.model.WorkflowRunInsightUsage;
import io.boomerang.flow.sdk.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.flow.sdk.model.WorkflowWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The SDK's models, routes and query builders match {@code contracts/api-v2.yaml}, the document
 * the server serves and is itself tested against. Each modelled schema round-trips a sample built
 * from the contract: a field, enum value or parameter the server adds fails here until the SDK
 * carries it or lists it below as deliberately not carried.
 */
@Tag("smoke")
class ApiContractTest {

  // Surefire runs each module from its own directory; the contract lives at the repository root.
  private static final Path CONTRACT = Path.of("..", "contracts", "api-v2.yaml");

  private static final String WORKSPACE = "/api/v2/workspace/{workspace}";
  private static final Instant SAMPLE_INSTANT = Instant.parse("2026-10-01T10:00:00Z");

  private static final Map<String, JavaType> MODELLED = new LinkedHashMap<>();

  static {
    MODELLED.put("WorkflowRun", type(WorkflowRun.class));
    MODELLED.put("TaskRun", type(TaskRun.class));
    MODELLED.put("TaskRunSpec", type(TaskRunSpec.class));
    MODELLED.put("TaskEnvVar", type(TaskEnvVar.class));
    MODELLED.put("RunParam", type(RunParam.class));
    MODELLED.put("RunResult", type(RunResult.class));
    MODELLED.put("RetryFrom", type(RetryFrom.class));
    MODELLED.put("RunAttempt", type(RunAttempt.class));
    MODELLED.put("WorkflowWorkspace", type(WorkflowWorkspace.class));
    MODELLED.put("WorkflowSubmitRequest", type(SubmitRequest.class));
    MODELLED.put("WorkflowRunRetryRequest", type(Retry.class));
    MODELLED.put("WorkflowRunCount", type(WorkflowRunCount.class));
    MODELLED.put("PageWorkflowRun", Runs.RUN_PAGE);
    MODELLED.put("PageTaskRun", TaskRuns.TASK_RUN_PAGE);
    MODELLED.put("Action", type(Action.class));
    MODELLED.put("Actioner", type(Actioner.class));
    MODELLED.put("ActionRequest", type(ActionDecision.class));
    MODELLED.put("PageAction", Actions.ACTION_PAGE);
    MODELLED.put("Workspace", type(Workspace.class));
    MODELLED.put("WorkspaceRequest", type(WorkspaceRequest.class));
    MODELLED.put("WorkflowRunInsightSummary", type(WorkflowRunInsightSummary.class));
    MODELLED.put("Totals", type(WorkflowRunInsightSummary.Totals.class));
    MODELLED.put("Day", type(WorkflowRunInsightSummary.Day.class));
    MODELLED.put("WorkflowRow", type(WorkflowRunInsightSummary.WorkflowRow.class));
    MODELLED.put("WorkflowRunInsightUsage", type(WorkflowRunInsightUsage.class));
    MODELLED.put("ModelUsage", type(WorkflowRunInsightUsage.ModelUsage.class));
    MODELLED.put("WorkflowRunInsightTasks", type(WorkflowRunInsightTasks.class));
    MODELLED.put("Row", type(WorkflowRunInsightTasks.Row.class));
    MODELLED.put("SlowTaskRun", type(WorkflowRunInsightTasks.SlowTaskRun.class));
    MODELLED.put("WorkflowRunInsightWorkflowDetail", type(WorkflowRunInsightWorkflowDetail.class));
    MODELLED.put("TaskRow", type(WorkflowRunInsightWorkflowDetail.TaskRow.class));
    MODELLED.put("Failure", type(WorkflowRunInsightWorkflowDetail.Failure.class));
  }

  // Properties of a modelled schema the SDK deliberately does not carry, with the reason.
  private static final Map<String, Set<String>> NOT_CARRIED =
      Map.of(
          // They restate the request's paging and sort, which the caller already holds.
          "PageWorkflowRun", Set.of("pageable", "sort"),
          "PageTaskRun", Set.of("pageable", "sort"),
          "PageAction", Set.of("pageable", "sort"),
          // Members, quotas, parameters, approver groups and workflows have their own routes; the
          // workspace page is not modelled here because its rows would carry them in the sample.
          "Workspace", Set.of("approverGroups", "members", "parameters", "quotas", "workflows"),
          // A create names the workspace, its display name, its external reference and labels; the
          // caller is its owner and the rest is set through the workspace's own routes.
          "WorkspaceRequest",
          Set.of("approverGroups", "id", "members", "parameters", "quotas", "status", "type"));

  // Bodies the SDK writes and never reads: the properties and enums are checked here, and the
  // JSON each builder writes is pinned in RunsTest.
  private static final Set<String> WRITTEN_ONLY =
      Set.of("WorkflowSubmitRequest", "WorkflowRunRetryRequest", "ActionRequest", "WorkspaceRequest");

  // Each query route the SDK calls, with a builder carrying every filter it offers.
  private static final Map<String, Params> QUERY_ROUTES = new LinkedHashMap<>();

  static {
    RunQuery runs =
        RunQuery.all()
            .labels("a=b")
            .statuses(RunStatus.running)
            .phases(RunPhase.running)
            .runs("r")
            .workflows("w")
            .triggers("manual")
            .from(SAMPLE_INSTANT)
            .to(SAMPLE_INSTANT)
            .page(0, 1)
            .order(Order.ASC);
    QUERY_ROUTES.put(WORKSPACE + "/workflowrun/query", runs.params());
    QUERY_ROUTES.put(WORKSPACE + "/workflowrun/count", runs.countParams());
    QUERY_ROUTES.put(
        WORKSPACE + "/taskrun/query",
        TaskRunQuery.all()
            .labels("a=b")
            .statuses(RunStatus.running)
            .phases(RunPhase.running)
            .runs("r")
            .workflows("w")
            .tasks("t")
            .foreach(false)
            .from(SAMPLE_INSTANT)
            .to(SAMPLE_INSTANT)
            .page(0, 1)
            .order(Order.ASC)
            .params());
    QUERY_ROUTES.put(
        WORKSPACE + "/action/query",
        ActionQuery.all()
            .types(ActionType.approval)
            .statuses(ActionStatus.submitted)
            .workflows("w")
            .from(SAMPLE_INSTANT)
            .to(SAMPLE_INSTANT)
            .page(0, 1)
            .order(Order.ASC)
            .sort("creationDate")
            .params());
    InsightsQuery insights =
        InsightsQuery.period(SAMPLE_INSTANT, SAMPLE_INSTANT)
            .workflows("w")
            .labels("a=b")
            .statuses(RunStatus.failed)
            .triggers("manual")
            .tasks("t")
            .types(TaskType.ai)
            .groupBy("task")
            .bucket(Bucket.day)
            .spanByLabel("k")
            .slowest(1);
    QUERY_ROUTES.put(
        "/api/v2/workspace/query",
        WorkspaceQuery.all()
            .search("ac")
            .labels("a=b")
            .statuses(WorkspaceStatus.active)
            .names("ws")
            .externalRef("ext")
            .page(0, 1)
            .sort("name", Order.ASC)
            .params());
    QUERY_ROUTES.put(WORKSPACE + "/insights", insights.summaryParams());
    QUERY_ROUTES.put(WORKSPACE + "/insights/tasks", insights.tasksParams());
    QUERY_ROUTES.put(WORKSPACE + "/insights/workflow/{workflow}", insights.workflowParams());
  }

  private static JsonNode contract;

  @BeforeAll
  static void readContract() throws Exception {
    contract = YAMLMapper.builder().build().readTree(Files.readString(CONTRACT));
  }

  private static JavaType type(Class<?> model) {
    return WireFormat.JSON.constructType(model);
  }

  private static JsonNode schemas() {
    return contract.get("components").get("schemas");
  }

  /** The properties Jackson writes for a model, by name, with their types. */
  static Map<String, JavaType> properties(Class<?> model) {
    return properties(type(model));
  }

  static Map<String, JavaType> properties(JavaType model) {
    Map<String, JavaType> properties = new TreeMap<>();
    WireFormat.JSON.acceptJsonFormatVisitor(
        model,
        new JsonFormatVisitorWrapper.Base() {
          @Override
          public JsonObjectFormatVisitor expectObjectFormat(JavaType type) {
            return new JsonObjectFormatVisitor.Base(getContext()) {
              @Override
              public void property(BeanProperty property) {
                properties.put(property.getName(), property.getType());
              }

              @Override
              public void optionalProperty(BeanProperty property) {
                properties.put(property.getName(), property.getType());
              }
            };
          }
        });
    return properties;
  }

  private static Set<String> carried(String schema) {
    Set<String> names = new TreeSet<>(schemas().get(schema).get("properties").propertyNames());
    names.removeAll(NOT_CARRIED.getOrDefault(schema, Set.of()));
    return names;
  }

  // A value of every property the schema declares: one element per array, one entry per map,
  // the first enum value, a fixed instant for a date-time.
  private static JsonNode sample(JsonNode schema, Deque<String> open) {
    if (schema.has("$ref")) {
      String name = schema.get("$ref").asString().replaceAll("^.*/", "");
      if (open.contains(name)) {
        return WireFormat.JSON.getNodeFactory().nullNode();
      }
      open.push(name);
      JsonNode value = sample(schemas().get(name), open);
      open.pop();
      return value;
    }
    if (schema.has("enum")) {
      return schema.get("enum").get(0);
    }
    String type = schema.has("type") ? schema.get("type").asString() : "";
    switch (type) {
      case "object" -> {
        ObjectNode object = WireFormat.JSON.createObjectNode();
        if (schema.has("properties")) {
          schema
              .get("properties")
              .properties()
              .forEach(entry -> object.set(entry.getKey(), sample(entry.getValue(), open)));
        } else if (schema.has("additionalProperties")) {
          object.set("key", sample(schema.get("additionalProperties"), open));
        }
        return object;
      }
      case "array" -> {
        ArrayNode array = WireFormat.JSON.createArrayNode();
        array.add(sample(schema.get("items"), open));
        return array;
      }
      case "string" -> {
        boolean instant =
            schema.has("format") && "date-time".equals(schema.get("format").asString());
        return WireFormat.JSON
            .getNodeFactory()
            .textNode(instant ? SAMPLE_INSTANT.toString() : "x");
      }
      case "integer" -> {
        return WireFormat.JSON.getNodeFactory().numberNode(1);
      }
      case "number" -> {
        return WireFormat.JSON.getNodeFactory().numberNode(1.5);
      }
      case "boolean" -> {
        return WireFormat.JSON.getNodeFactory().booleanNode(true);
      }
      default -> {
        return WireFormat.JSON.getNodeFactory().textNode("x");
      }
    }
  }

  @Test
  void everyModelledSchemaIsInTheContract() {
    assertThat(schemas().propertyNames()).containsAll(MODELLED.keySet());
  }

  @Test
  void everyModelCarriesExactlyItsSchemasProperties() {
    MODELLED.forEach(
        (schema, model) ->
            assertThat(properties(model).keySet())
                .as("%s properties of %s", schema, model)
                .isEqualTo(carried(schema)));
  }

  @Test
  void everyModelRoundTripsASampleOfItsSchema() {
    MODELLED.forEach(
        (schema, model) -> {
          if (WRITTEN_ONLY.contains(schema)) {
            return;
          }
          ObjectNode sample = (ObjectNode) sample(schemas().get(schema), new ArrayDeque<>());
          NOT_CARRIED.getOrDefault(schema, Set.of()).forEach(sample::remove);
          byte[] json = WireFormat.JSON.writeValueAsBytes(sample);

          Object read = WireFormat.JSON.readValue(json, model);
          JsonNode written = WireFormat.JSON.readTree(WireFormat.JSON.writeValueAsBytes(read));

          assertThat(written)
              .as("%s read into %s and written again", schema, model)
              .isEqualTo(WireFormat.JSON.readTree(json));
        });
  }

  @Test
  void everyEnumValueInTheContractIsKnownToTheSdk() {
    MODELLED.forEach(
        (schema, model) -> {
          Map<String, JavaType> properties = properties(model);
          schemas()
              .get(schema)
              .get("properties")
              .properties()
              .forEach(
                  entry -> {
                    if (entry.getValue().has("enum")
                        && !NOT_CARRIED.getOrDefault(schema, Set.of()).contains(entry.getKey())) {
                      Class<?> type = properties.get(entry.getKey()).getRawClass();
                      assertThat(type.isEnum())
                          .as("%s.%s is an enum", schema, entry.getKey())
                          .isTrue();
                      List<String> known =
                          Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
                      entry
                          .getValue()
                          .get("enum")
                          .forEach(
                              value ->
                                  assertThat(known)
                                      .as("%s.%s", schema, entry.getKey())
                                      .contains(value.asString()));
                    }
                  });
        });
  }

  @Test
  void everyRouteTheSdkCallsIsInTheContractWithTheSameMethod() {
    List<String> calls = new ArrayList<>();
    RestClient recording =
        RestClient.builder()
            .requestInterceptor(
                (request, body, execution) -> {
                  calls.add(request.getMethod().name() + " " + request.getURI().getPath());
                  return new MockClientHttpResponse(
                      "{}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                })
            .build();
    Flow flow = Flow.builder().baseUrl("http://flow").token("t").restClient(recording).build();

    flow.runs("ws").submit("wf", SubmitRequest.empty());
    flow.runs("ws").get("r");
    flow.runs("ws").start("r");
    flow.runs("ws").pause("r");
    flow.runs("ws").resume("r");
    flow.runs("ws").cancel("r");
    flow.runs("ws").retry("r", Retry.all());
    flow.runs("ws").query(RunQuery.all());
    flow.runs("ws").count(RunQuery.all());
    flow.taskRuns("ws").query(TaskRunQuery.all());
    flow.taskRuns("ws").log("t");
    flow.actions("ws").get("a");
    flow.actions("ws").query(ActionQuery.all());
    flow.actions("ws").decide(ActionDecision.approve("a", null));
    flow.insights("ws").summary(InsightsQuery.all());
    flow.insights("ws").tasks(InsightsQuery.all());
    flow.insights("ws").workflow("wf", InsightsQuery.all());

    assertThat(calls).hasSize(17);
    List<String> routes = routes();
    calls.forEach(
        call ->
            assertThat(routes)
                .as("contract route for %s", call)
                .anyMatch(route -> call.matches(route)));
  }

  @Test
  void everyQueryBuilderSendsExactlyItsRoutesParameters() {
    QUERY_ROUTES.forEach(
        (route, params) -> {
          Set<String> declared = new TreeSet<>();
          contract
              .get("paths")
              .get(route)
              .get("get")
              .get("parameters")
              .forEach(
                  parameter -> {
                    if ("query".equals(parameter.get("in").asString())) {
                      declared.add(parameter.get("name").asString());
                    }
                  });

          assertThat(new TreeSet<>(params.values().keySet())).as(route).isEqualTo(declared);
        });
  }

  // Each contract route as "METHOD path", with its path variables as a one-segment wildcard.
  private static List<String> routes() {
    List<String> routes = new ArrayList<>();
    contract
        .get("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .propertyNames()
                    .forEach(
                        method ->
                            routes.add(
                                method.toUpperCase()
                                    + " "
                                    + path.getKey().replaceAll("\\{[^}]+}", "[^/]+"))));
    return routes;
  }
}
