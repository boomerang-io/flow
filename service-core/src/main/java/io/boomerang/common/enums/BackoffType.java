package io.boomerang.common.enums;

/** How a task's retry backoff grows: the same wait every time, or doubling to a cap. */
public enum BackoffType {
  fixed,
  exponential
}
