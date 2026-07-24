package io.boomerang.common.enums;

/** What tripped a WorkflowRun timeout: the run's own budget, or one of its TaskRuns. */
public enum TimeoutCause {
  workflow,
  task
}
