package io.boomerang.dispatcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Tracks the last-seen timestamp for every TaskRun a watch loop is actively polling, so {@link
 * LeaseHeartbeat} can report which ones are still alive in one batch per beat interval.
 */
@Component
public class LeaseRegistry {

  private final Map<String, Instant> lastSeenById = new ConcurrentHashMap<>();

  public void beat(String taskRunId) {
    lastSeenById.put(taskRunId, Instant.now());
  }

  public void remove(String taskRunId) {
    lastSeenById.remove(taskRunId);
  }

  public List<String> aliveWithin(Duration window) {
    Instant cutoff = Instant.now().minus(window);
    return lastSeenById.entrySet().stream()
        .filter(entry -> entry.getValue().isAfter(cutoff))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }
}
