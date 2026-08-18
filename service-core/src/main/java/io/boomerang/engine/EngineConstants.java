package io.boomerang.engine;

/** Shared operational constants for the engine's execution and sweep machinery. */
public final class EngineConstants {

  private EngineConstants() {}

  /** Grace added on top of a timeout budget so a run at exactly its budget is not reaped. */
  public static final long TIMEOUT_GRACE_MILLIS = 5000L;

  /** Page size for the level-triggered watcher/dispatcher sweeps. */
  public static final int SWEEP_PAGE_SIZE = 50;

  /**
   * Provisional deadline budget for a claimed TaskRun that has not yet reported starting
   * execution. Bounds the claim-to-start window only - real-world dispatch latency (pod
   * scheduling, image pull) comfortably fits inside it, and {@code tryStartExecution} replaces it
   * with the task's own deadline the moment execution is confirmed. A dispatcher that dies
   * between claim and start is reaped by the same timeout sweep that already exists.
   */
  public static final long CLAIM_TIMEOUT_MINUTES = 10L;
}
