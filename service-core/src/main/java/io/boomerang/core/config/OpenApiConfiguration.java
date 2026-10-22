package io.boomerang.core.config;

import io.swagger.v3.oas.models.info.Info;
import java.util.Optional;
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
 * <p>A request parameter declared as {@code Optional<T>} with a {@code defaultValue} gets that
 * default converted to an {@code Optional}, which the document's serialiser cannot write - and one
 * such parameter fails the whole document - so the customizer below unwraps it.
 */
@Configuration
public class OpenApiConfiguration {

  public static final String API_GROUP = "api-v2";

  // The API's own version, apart from the product's: additive changes within 5.x keep it at 2.
  static final String API_VERSION = "2";

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
        .build();
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
