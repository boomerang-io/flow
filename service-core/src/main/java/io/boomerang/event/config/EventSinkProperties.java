package io.boomerang.event.config;

import io.boomerang.event.enums.EventPayload;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Outbound CloudEvent sink configuration ({@code flow.events.sink.*}). Egress is off by default;
 * {@code payload} chooses how much of the run each status event carries.
 */
@ConfigurationProperties(prefix = "flow.events.sink")
public record EventSinkProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("thin") EventPayload payload,
    @DefaultValue List<String> urls) {

  // A shipped-empty "flow.events.sink.urls=" must mean no sink, never one blank destination.
  public EventSinkProperties {
    urls = urls.stream().filter(StringUtils::hasText).map(String::trim).toList();
  }
}
