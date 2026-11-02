package io.boomerang.core.config;

import io.boomerang.core.security.SecurityConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        .build();
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
