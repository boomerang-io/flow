package io.boomerang.dispatcher;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the dispatcher protocol on its own: {@code /api/docs/spec/dispatcher-v1} serves only
 * the {@code /api/v1/dispatcher} routes - the contract a dispatcher implements, described in
 * {@code specifications/dispatcher-contract.md}. The checked-in {@code contracts/dispatcher-v1.yaml}
 * must match what this serves; {@code DispatcherContractTest} fails on any drift.
 */
@Configuration
public class DispatcherContractConfiguration {

  static final String GROUP = "dispatcher-v1";

  // The protocol's own version, apart from the product's: additive changes keep it at 1.
  static final String VERSION = "1";

  @Bean
  public GroupedOpenApi dispatcherContract() {
    return GroupedOpenApi.builder()
        .group(GROUP)
        .pathsToMatch("/api/v1/dispatcher/**")
        .addOpenApiCustomizer(
            openApi ->
                openApi.info(
                    new Info()
                        .title("Boomerang Flow dispatcher protocol")
                        .version(VERSION)
                        .description(
                            "What a dispatcher calls on the engine. Semantics and the compatibility"
                                + " policy: specifications/dispatcher-contract.md.")))
        .build();
  }
}
