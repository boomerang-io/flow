package io.boomerang.dispatcher.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SDK's own JSON mapping for the wire models, so what goes on the wire never depends on how
 * the host configured its JSON: unknown fields are ignored, nulls are not sent, and a missing value
 * never fails a read.
 */
final class WireFormat {

  static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private WireFormat() {}
}
