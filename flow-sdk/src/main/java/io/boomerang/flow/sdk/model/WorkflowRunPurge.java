package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The outcome of a purge: {@code purged} runs removed now, {@code scheduled} runs left to the
 * watcher's sweep because a dispatcher still holds a task of theirs, and {@code remaining} matches
 * the call did not reach, so a purge by labels is repeated until it is 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRunPurge(int purged, int scheduled, long remaining) {

  /** Whether another call would still find runs to purge. */
  public boolean hasMore() {
    return remaining > 0;
  }
}
