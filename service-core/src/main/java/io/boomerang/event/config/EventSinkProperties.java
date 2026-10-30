package io.boomerang.event.config;

import io.boomerang.event.enums.EventPayload;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.event.entity.EventOutboxEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 *
 * <p>Events can be filtered, top level for every sink and again per destination; an empty list is
 * no filter on that dimension, and a destination with none of its own inherits the top level.
 * {@code types} is {@code workflowrun} and/or {@code taskrun}. {@code statuses} is any run status
 * name, or {@code terminal} for succeeded, failed, cancelled, timedout and invalid; it matches the
 * status as emitted, so a run with finally tasks takes its terminal status while its phase is still
 * {@code running}. {@code phases} is any run phase name, and {@code completed} is the true end of a
 * run. A destination's {@code labels} are {@code key=value} pairs the run must all carry.
 */
@ConfigurationProperties(prefix = "flow.events.sink")
public record EventSinkProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("thin") EventPayload payload,
    @DefaultValue List<String> urls,
    @DefaultValue List<Destination> destinations,
    @DefaultValue List<String> types,
    @DefaultValue List<String> statuses,
    @DefaultValue List<String> phases) {

  private static final Set<String> TERMINAL =
      Set.of("succeeded", "failed", "cancelled", "timedout", "invalid");

  // A shipped-empty "flow.events.sink.urls=" must mean no sink, never one blank destination.
  public EventSinkProperties {
    urls = urls.stream().filter(StringUtils::hasText).map(String::trim).toList();
    types = normalised(types);
    statuses = normalised(statuses);
    phases = normalised(phases);
    validate("flow.events.sink", types, statuses, phases);
  }

  /**
   * Every configured sink with its effective filter: the bare URLs first, then the
   * header-authenticated destinations.
   */
  public List<Destination> sinks() {
    return Stream.concat(
            urls.stream().map(Destination::bare), destinations.stream())
        .map(d -> d.unfiltered() ? d.filteredBy(types, statuses, phases) : d)
        .toList();
  }

  /**
   * Whether any configured sink wants an event reaching this status and phase, so a transition
   * nobody wants is never stored. Labels are not known here; a destination filtered on them still
   * admits, and the delivery check drops the event for that destination.
   */
  public boolean admits(String refType, RunStatus toStatus, RunPhase toPhase) {
    List<Destination> sinks = sinks();
    return sinks.isEmpty()
        ? matches(types, statuses, phases, refType, toStatus, toPhase)
        : sinks.stream().anyMatch(d -> d.admitsEvent(refType, toStatus, toPhase));
  }

  private static List<String> normalised(List<String> values) {
    return values.stream().filter(StringUtils::hasText).map(v -> v.trim().toLowerCase()).toList();
  }

  private static void validate(
      String key, List<String> types, List<String> statuses, List<String> phases) {
    types.forEach(
        t ->
            Assert.isTrue(
                EventOutboxEntity.REF_TYPE_WORKFLOWRUN.equals(t)
                    || EventOutboxEntity.REF_TYPE_TASKRUN.equals(t),
                key + ".types: unknown type '" + t + "', expected workflowrun or taskrun"));
    statuses.forEach(
        s ->
            Assert.isTrue(
                "terminal".equals(s) || Arrays.stream(RunStatus.values()).anyMatch(v -> v.name().equals(s)),
                key + ".statuses: unknown status '" + s + "'"));
    phases.forEach(
        p ->
            Assert.isTrue(
                Arrays.stream(RunPhase.values()).anyMatch(v -> v.name().equals(p)),
                key + ".phases: unknown phase '" + p + "'"));
  }

  private static boolean matches(
      List<String> types,
      List<String> statuses,
      List<String> phases,
      String refType,
      RunStatus status,
      RunPhase phase) {
    return (types.isEmpty() || types.contains(refType))
        && (statuses.isEmpty()
            || statuses.contains(status.name())
            || (statuses.contains("terminal") && TERMINAL.contains(status.name())))
        && (phases.isEmpty() || phases.contains(phase.name()));
  }

  /**
   * One sink. {@code headerValue} is a secret - it is supplied from the environment, sent only on
   * the request to its own sink, and never logged. An entry without one is a plain POST.
   */
  public record Destination(
      String url,
      String headerName,
      String headerValue,
      @DefaultValue List<String> types,
      @DefaultValue List<String> statuses,
      @DefaultValue List<String> phases,
      @DefaultValue List<String> labels) {

    private static final String DEFAULT_HEADER_NAME = "Authorization";

    @ConstructorBinding
    public Destination {
      Assert.hasText(url, "flow.events.sink.destinations[].url must not be empty");
      headerName = StringUtils.hasText(headerName) ? headerName : DEFAULT_HEADER_NAME;
      String key = "flow.events.sink.destinations[]";
      types = normalised(types);
      statuses = normalised(statuses);
      phases = normalised(phases);
      labels = labels.stream().filter(StringUtils::hasText).map(String::trim).toList();
      validate(key, types, statuses, phases);
      labels.forEach(
          l ->
              Assert.isTrue(
                  l.indexOf('=') > 0, key + ".labels: '" + l + "' is not key=value"));
    }

    public Destination(String url, String headerName, String headerValue) {
      this(url, headerName, headerValue, List.of(), List.of(), List.of(), List.of());
    }

    static Destination bare(String url) {
      return new Destination(url, null, null);
    }

    /** Whether this destination has no filter of its own, so it takes the top-level one. */
    private boolean unfiltered() {
      return types.isEmpty() && statuses.isEmpty() && phases.isEmpty();
    }

    private Destination filteredBy(
        List<String> types, List<String> statuses, List<String> phases) {
      return new Destination(url, headerName, headerValue, types, statuses, phases, labels);
    }

    /** Whether the event passes this destination's type, status and phase filter. */
    public boolean admitsEvent(String refType, RunStatus toStatus, RunPhase toPhase) {
      return matches(types, statuses, phases, refType, toStatus, toPhase);
    }

    /** Whether the event passes this destination's whole filter, labels included. */
    public boolean admits(
        String refType, RunStatus toStatus, RunPhase toPhase, Map<String, String> runLabels) {
      return admitsEvent(refType, toStatus, toPhase)
          && labels.stream()
              .allMatch(
                  l -> {
                    int at = l.indexOf('=');
                    return runLabels != null
                        && l.substring(at + 1).equals(runLabels.get(l.substring(0, at).trim()));
                  });
    }

    public boolean authenticated() {
      return StringUtils.hasText(headerValue);
    }
  }
}
