package io.boomerang.event.model;

/** How many outbox rows a replay put back in the queue. */
public record OutboxReplayResponse(long replayed) {}
