package io.boomerang.event.config;

import io.boomerang.event.enums.EventPayload;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Outbound CloudEvent sink configuration ({@code flow.events.sink.*}). Egress is off by default;
 * {@code payload} chooses how much of the run each status event carries.
 *
 * <p>A destination is configured either as a bare URL in {@code urls} - any secret then has to
 * ride in the query string - or as a {@code destinations} entry that carries its secret in a
 * request header instead. Both lists are delivered to.
 */
@ConfigurationProperties(prefix = "flow.events.sink")
public record EventSinkProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("thin") EventPayload payload,
    @DefaultValue List<String> urls,
    @DefaultValue List<Destination> destinations) {

  // A shipped-empty "flow.events.sink.urls=" must mean no sink, never one blank destination.
  public EventSinkProperties {
    urls = urls.stream().filter(StringUtils::hasText).map(String::trim).toList();
  }

  /** Every configured sink: the bare URLs first, then the header-authenticated destinations. */
  public List<Destination> sinks() {
    return Stream.concat(urls.stream().map(Destination::bare), destinations.stream()).toList();
  }

  /**
   * One sink. {@code headerValue} is a secret - it is supplied from the environment, sent only on
   * the request to its own sink, and never logged. An entry without one is a plain POST.
   */
  public record Destination(String url, String headerName, String headerValue) {

    private static final String DEFAULT_HEADER_NAME = "Authorization";

    public Destination {
      Assert.hasText(url, "flow.events.sink.destinations[].url must not be empty");
      headerName = StringUtils.hasText(headerName) ? headerName : DEFAULT_HEADER_NAME;
    }

    static Destination bare(String url) {
      return new Destination(url, null, null);
    }

    public boolean authenticated() {
      return StringUtils.hasText(headerValue);
    }
  }
}
