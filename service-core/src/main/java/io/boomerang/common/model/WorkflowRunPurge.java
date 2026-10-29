package io.boomerang.common.model;

/**
 * What a purge request did. {@code purged} runs are gone with everything they owned;
 * {@code scheduled} runs were cancelled, or a dispatcher still holds a terminated task of theirs,
 * and the purge sweep removes them within minutes; {@code remaining} is how many matching runs a
 * bounded label purge left for the next call. A run that did not exist counts in neither.
 */
public record WorkflowRunPurge(int purged, int scheduled, long remaining) {

  public static final WorkflowRunPurge NONE = new WorkflowRunPurge(0, 0, 0);
}
