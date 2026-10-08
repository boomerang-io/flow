package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.DispatcherRegistrationRequest;
import io.boomerang.dispatcher.sdk.model.HeartbeatRequest;
import io.boomerang.dispatcher.sdk.model.RunParam;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskEnvVar;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import io.boomerang.dispatcher.sdk.model.TaskRunStartRequest;
import io.boomerang.dispatcher.sdk.model.TaskWorkspace;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import io.boomerang.dispatcher.sdk.model.WorkflowWorkspace;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseQuery;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The SDK's wire models and routes match {@code contracts/dispatcher-v1.yaml}, the document the
 * engine serves and is itself tested against. A field, enum value or schema the engine adds fails
 * here until the SDK models it or lists it below as deliberately not modelled.
 */
class DispatcherContractTest {

  // Surefire runs each module from its own directory; the contract lives at the repository root.
  private static final Path CONTRACT = Path.of("..", "contracts", "dispatcher-v1.yaml");

  private static final Map<String, Class<?>> MODELLED = new LinkedHashMap<>();

  static {
    MODELLED.put("DispatcherRegistrationRequest", DispatcherRegistrationRequest.class);
    MODELLED.put("HeartbeatRequest", HeartbeatRequest.class);
    MODELLED.put("RunParam", RunParam.class);
    MODELLED.put("RunResult", RunResult.class);
    MODELLED.put("TaskEnvVar", TaskEnvVar.class);
    MODELLED.put("TaskRun", TaskRun.class);
    MODELLED.put("TaskRunSpec", TaskRunSpec.class);
    MODELLED.put("TaskRunStartRequest", TaskRunStartRequest.class);
    MODELLED.put("TaskRunEndRequest", TaskRunEndRequest.class);
    MODELLED.put("TaskWorkspace", TaskWorkspace.class);
    MODELLED.put("WorkflowRun", WorkflowRun.class);
    MODELLED.put("WorkflowWorkspace", WorkflowWorkspace.class);
    MODELLED.put("WorkspaceReleaseQuery", WorkspaceReleaseQuery.class);
    MODELLED.put("WorkspaceReleaseResponse", WorkspaceReleaseResponse.class);
  }

  // Schemas the SDK deliberately does not model, each with the reason.
  private static final Map<String, String> NOT_MODELLED =
      Map.of(
          "WorkflowRunRequest",
          "A dispatcher starts a provisioned workflow run with an empty request.");

  private static JsonNode contract;

  @BeforeAll
  static void readContract() throws Exception {
    contract = YAMLMapper.builder().build().readTree(Files.readString(CONTRACT));
  }

  private static JsonNode schemas() {
    return contract.get("components").get("schemas");
  }

  // The properties Jackson writes for a model, by name, with their types.
  private static Map<String, JavaType> properties(Class<?> model) {
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

  @Test
  void everyContractSchemaIsModelledOrDeliberatelyNot() {
    Set<String> decided = new TreeSet<>(MODELLED.keySet());
    decided.addAll(NOT_MODELLED.keySet());

    assertThat(new TreeSet<>(schemas().propertyNames())).isEqualTo(decided);
  }

  @Test
  void everyModelCarriesExactlyItsSchemasProperties() {
    MODELLED.forEach(
        (schema, model) ->
            assertThat(properties(model).keySet())
                .as("%s properties of %s", schema, model.getSimpleName())
                .isEqualTo(new TreeSet<>(schemas().get(schema).get("properties").propertyNames())));
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
                    if (entry.getValue().has("enum")) {
                      Class<?> type = properties.get(entry.getKey()).getRawClass();
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
  void everyRouteTheClientCallsIsInTheContractWithTheSameMethod() {
    List<String> calls = new ArrayList<>();
    RestClient recording =
        RestClient.builder()
            .requestInterceptor(
                (request, body, execution) -> {
                  calls.add(request.getMethod().name() + " " + request.getURI().getPath());
                  return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                })
            .build();
    DispatcherClient client = new DispatcherClient(recording, "http://engine", null);

    client.register(new DispatcherRegistrationRequest("kube", "pod-1", List.of()));
    client.pollTasks("d-1", 1, TaskFilter.none().types("template"));
    client.pollWorkflows("d-1");
    client.startTask("t-1", new TaskRunStartRequest("d-1"));
    client.endTask("t-1", new TaskRunEndRequest());
    client.heartbeat("d-1", List.of("t-1"));
    client.startWorkflowRun("w-1");
    client.releasable(new WorkspaceReleaseQuery());

    assertThat(calls).hasSize(8);
    List<String> routes = routes();
    calls.forEach(
        call ->
            assertThat(routes)
                .as("contract route for %s", call)
                .anyMatch(route -> call.matches(route)));
  }

  @Test
  void theTaskPollQueryParametersAreInTheContract() {
    Set<String> parameters = new TreeSet<>();
    contract
        .get("paths")
        .get("/api/v1/dispatcher/{id}/tasks")
        .get("get")
        .get("parameters")
        .forEach(parameter -> parameters.add(parameter.get("name").asString()));

    assertThat(parameters).containsExactlyInAnyOrder("id", "limit", "type", "task", "workflowLabel");
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
