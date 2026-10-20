package io.boomerang.core.config;

import java.util.Optional;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the generated OpenAPI documents serialisable. A request parameter declared as {@code
 * Optional<T>} with a {@code defaultValue} gets that default converted to an {@code Optional},
 * which the document's serialiser cannot write - and one such parameter fails the whole document.
 */
@Configuration
public class OpenApiConfiguration {

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
