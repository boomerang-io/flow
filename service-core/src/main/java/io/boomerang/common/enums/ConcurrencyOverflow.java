package io.boomerang.common.enums;

/** What a workflow does with a run over its concurrency limit: park it for a slot, or end it. */
public enum ConcurrencyOverflow {
  queue,
  skip
}
