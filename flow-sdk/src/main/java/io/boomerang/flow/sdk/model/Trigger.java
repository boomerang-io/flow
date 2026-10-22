package io.boomerang.flow.sdk.model;

/** What started a run, as a submit request may record it. */
public enum Trigger {
  manual,
  schedule,
  webhook,
  event,
  github,
  engine,
  task,
  retry
}
