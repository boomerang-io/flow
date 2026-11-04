package io.boomerang.core.config;

import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.AuthCriteriaAuthorizationManager;
import io.boomerang.core.security.AuthExempt;
import io.boomerang.core.security.SecurityConfiguration;
import io.boomerang.core.security.enums.AuthScope;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * The OpenAPI documents. {@code /api/docs/spec/api-v2} serves the public API alone - the
 * {@code /api/v2} routes a client such as {@code flow-sdk} calls, described in {@code
 * specifications/api-contract.md}. The checked-in {@code contracts/api-v2.yaml} must match what it
 * serves; {@code ApiContractTest} fails on any drift. The whole-API document at {@code
 * /api/docs/spec} is unchanged by the group.
 *
 * <p>Every route needs a Flow token: a bearer token or the {@code x-access-token} header, the two
 * schemes {@code Application} declares. The routes {@code SecurityConfiguration} permits
 * unauthenticated, because each verifies the request itself, declare none. The webhook routes
 * also take the token as the {@code access_token} query parameter, for senders that can set only
 * a URL ({@code AuthenticationFilter}).
 *
 * <p>Each operation also states the permission its handler is checked against, read from its
 * {@code @AuthCriteria} or {@code @AuthExempt} by the rule {@code AuthCriteriaAuthorizationManager}
 * enforces: an {@code x-flow-permission} extension and a sentence in its description.
 *
 * <p>The run models state what the server always sends and what it may send as null, which
 * springdoc cannot infer from the classes: a {@code required} property is on every response that
 * carries the schema, and a nullable one goes out as {@code null} when unset. The lists apply to
 * this group alone: {@code RunParam}, {@code RunResult}, {@code RunAttempt} and {@code TaskRunSpec}
 * also appear in the dispatcher contract, which they leave as it is.
 *
 * <p>An operation whose method name does not say what it acts on names its id on its handler
 * ({@code @Operation(operationId = ...)}): a verb and the resource, unique across the group, so a
 * generated client never meets springdoc's numbered fallback ({@code query_8}).
 *
 * <p>A request parameter declared as {@code Optional<T>} with a {@code defaultValue} gets that
 * default converted to an {@code Optional}, which the document's serialiser cannot write - and one
 * such parameter fails the whole document - so the customizer below unwraps it.
 */
@Configuration
public class OpenApiConfiguration {

  public static final String API_GROUP = "api-v2";

  // The API's own version, apart from the product's: additive changes within 5.x keep it at 2.
  static final String API_VERSION = "2";

  static final String BEARER = "BearerAuth";
  static final String ACCESS_TOKEN_HEADER = "x-access-token";
  static final String ACCESS_TOKEN_QUERY = "access_token";
  static final String PERMISSION_EXTENSION = "x-flow-permission";

  // What a page of results always carries beside its content.
  private static final List<String> PAGE_REQUIRED =
      List.of(
          "content",
          "number",
          "size",
          "numberOfElements",
          "totalElements",
          "totalPages",
          "first",
          "last",
          "empty");

  // The properties each public run model sends on every response, as each path that creates or
  // converts the model leaves it (RunModelRequiredFieldsTest serialises those paths).
  static final Map<String, List<String>> REQUIRED =
      Map.of(
          "WorkflowRun",
          List.of(
              "id",
              "creationDate",
              "status",
              "phase",
              "paused",
              "awaitingApproval",
              "duration",
              "workflowRef",
              "workflowRevisionRef",
              "labels",
              "annotations",
              "params",
              "results"),
          // A queried task run leaves out its params, results, spec and annotations.
          "TaskRun",
          List.of(
              "id",
              "creationDate",
              "status",
              "phase",
              "duration",
              "workflowRef",
              "workflowRunRef",
              "labels"),
          "RunParam",
          List.of("name"),
          "RunResult",
          List.of("name", "description", "value"),
          "RunAttempt",
          List.of("number", "endedAt", "duration", "status"),
          "PageWorkflowRun",
          PAGE_REQUIRED,
          "PageTaskRun",
          PAGE_REQUIRED);

  // The properties a model without NON_NULL writes as null when they are unset.
  static final Map<String, List<String>> NULLABLE =
      Map.of(
          "TaskRunSpec",
          List.of(
              "arguments", "command", "envs", "image", "script", "workingDir", "debug", "deletion"),
          "RunResult",
          List.of("description"));

  // The routes a sender that can set only a URL calls with the token in its query.
  static final Set<String> WEBHOOK_ROUTES = Set.of("/api/v2/webhook", "/api/v2/event", "/api/v2/callback");

  @Bean
  public GroupedOpenApi apiContract() {
    return GroupedOpenApi.builder()
        .group(API_GROUP)
        .pathsToMatch("/api/v2/**")
        .addOpenApiCustomizer(
            openApi ->
                openApi.info(
                    new Info()
                        .title("Boomerang Flow API")
                        .version(API_VERSION)
                        .description(
                            "The public API. The rules every route follows:"
                                + " specifications/api-contract.md.")))
        .addOpenApiCustomizer(OpenApiConfiguration::declareSecurity)
        .addOpenApiCustomizer(OpenApiConfiguration::describeRunModels)
        .addOperationCustomizer(OpenApiConfiguration::describePermission)
        .build();
  }

  // The permission an operation's handler is checked against, or the reason it is exempt, as the
  // x-flow-permission extension and a closing sentence in its description.
  static Operation describePermission(Operation operation, HandlerMethod handler) {
    Method method = handler.getMethod();
    AuthCriteria criteria = AuthCriteriaAuthorizationManager.criteriaFor(method);
    AuthExempt exempt = AuthCriteriaAuthorizationManager.exemptionFor(method);
    String sentence;
    if (criteria != null) {
      String permission = criteria.resource().getLabel() + "/" + criteria.action().getLabel();
      List<String> tokens =
          Arrays.stream(criteria.assignableScopes()).map(AuthScope::getLabel).toList();
      operation.addExtension(PERMISSION_EXTENSION, Map.of("permission", permission, "tokens", tokens));
      sentence =
          "Requires the `" + permission + "` permission, from a " + String.join(", ", tokens)
              + " token.";
    } else if (exempt != null) {
      operation.addExtension(PERMISSION_EXTENSION, Map.of("exempt", exempt.reason()));
      sentence = "No permission check: " + exempt.reason();
    } else {
      return operation;
    }
    operation.setDescription(
        (operation.getDescription() == null || operation.getDescription().isBlank())
            ? sentence
            : operation.getDescription() + "\n\n" + sentence);
    return operation;
  }

  // The run models' required and nullable properties, and the properties TaskRunSpec passes
  // through beyond the ones it declares. A listed property the document lacks fails the document,
  // so a renamed field cannot leave a stale entry behind.
  static void describeRunModels(OpenAPI openApi) {
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    REQUIRED.forEach(
        (name, properties) -> {
          properties.forEach(property -> propertyOf(schemas, name, property));
          schemas.get(name).setRequired(properties);
        });
    NULLABLE.forEach(
        (name, properties) ->
            properties.forEach(property -> allowNull(propertyOf(schemas, name, property))));
    schemas.get("TaskRunSpec").setAdditionalProperties(true);
  }

  private static Schema<?> propertyOf(Map<String, Schema> schemas, String name, String property) {
    Schema<?> schema = schemas.get(name);
    if (schema == null
        || schema.getProperties() == null
        || !schema.getProperties().containsKey(property)) {
      throw new IllegalStateException("The API contract has no property " + name + "." + property);
    }
    return schema.getProperties().get(property);
  }

  // OpenAPI 3.1 states null as a type of its own, and an enum lists it among its values.
  private static void allowNull(Schema<?> property) {
    LinkedHashSet<String> types =
        new LinkedHashSet<>(
            (property.getTypes() != null) ? property.getTypes() : List.of(property.getType()));
    types.add("null");
    property.setTypes(types);
    if (property.getEnum() != null) {
      property.getEnum().add(null);
    }
  }

  // The document's security, as SecurityConfiguration and AuthenticationFilter enforce it: a token
  // on every route by default, none on the routes permitted unauthenticated, and the query token
  // beside the two on the webhook routes.
  static void declareSecurity(OpenAPI openApi) {
    openApi
        .getComponents()
        .addSecuritySchemes(
            ACCESS_TOKEN_QUERY,
            new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.QUERY)
                .name(ACCESS_TOKEN_QUERY)
                .description("A token in the URL, for webhook senders that cannot set a header."));
    SecurityRequirement bearer = new SecurityRequirement().addList(BEARER);
    SecurityRequirement header = new SecurityRequirement().addList(ACCESS_TOKEN_HEADER);
    SecurityRequirement query = new SecurityRequirement().addList(ACCESS_TOKEN_QUERY);
    openApi.setSecurity(List.of(bearer, header));
    Set<String> open =
        Stream.concat(
                SecurityConfiguration.SLACK_ROUTES.stream(),
                Stream.of(SecurityConfiguration.GITHUB_CALLBACK))
            .collect(Collectors.toSet());
    openApi
        .getPaths()
        .forEach(
            (path, item) ->
                item.readOperations()
                    .forEach(
                        operation ->
                            operation.setSecurity(
                                open.contains(path)
                                    ? List.of()
                                    : (WEBHOOK_ROUTES.contains(path)
                                        ? List.of(bearer, header, query)
                                        : null))));
  }

  // The documented default is the value itself, which is what a caller would send.
  @Bean
  ParameterCustomizer optionalDefaultUnwrapper() {
    return (parameter, methodParameter) -> {
      if (parameter != null
          && parameter.getSchema() != null
          && parameter.getSchema().getDefault() instanceof Optional<?> value) {
        parameter.getSchema().setDefault(value.orElse(null));
      }
      return parameter;
    };
  }
}
