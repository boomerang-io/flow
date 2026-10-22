package io.boomerang.config;

import java.util.HashMap;
import java.util.Map;
import io.jaegertracing.internal.samplers.ConstSampler;
import io.jaegertracing.internal.samplers.ProbabilisticSampler;
import io.jaegertracing.internal.samplers.RateLimitingSampler;
import io.jaegertracing.internal.samplers.RemoteControlledSampler;

public enum SamplerType {

  CONST(ConstSampler.TYPE), PROBABILISTIC(ProbabilisticSampler.TYPE), RATE_LIMIT(
      RateLimitingSampler.TYPE), REMOTE_CONTROLLED(RemoteControlledSampler.TYPE);

  private static final Map<String, SamplerType> BY_LABEL = new HashMap<>();

  static {
    for (SamplerType st : values()) {
      BY_LABEL.put(st.value, st);
    }
  }

  private String value;

  SamplerType(String value) {
    this.value = value;
  }

  public String getValue() {
    return this.value;
  }


  public static SamplerType fromValue(String value) {
    SamplerType samplerType = BY_LABEL.get(value);

    if (samplerType == null) {
      throw new IllegalStateException(String.format("Invalid sampling strategy %s", samplerType));
    }

    return samplerType;
  }
}
