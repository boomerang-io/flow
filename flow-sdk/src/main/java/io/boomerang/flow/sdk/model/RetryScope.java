package io.boomerang.flow.sdk.model;

/**
 * What a retry runs again: {@code all} is a fresh run of the whole graph; {@code failed} every task
 * that did not succeed and everything after it; {@code nodes} named tasks and everything after
 * them.
 */
public enum RetryScope {
  all,
  failed,
  nodes
}
