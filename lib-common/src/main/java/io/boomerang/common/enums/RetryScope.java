package io.boomerang.common.enums;

/**
 * What a retry re-runs. {@code all} is a fresh run of the whole graph; {@code failed} re-runs
 * every task that did not succeed and everything after it; {@code nodes} adds named tasks and
 * everything after them. A closed enum, so a task named "failed" can never be read as a scope.
 */
public enum RetryScope {
  all,
  failed,
  nodes
}
