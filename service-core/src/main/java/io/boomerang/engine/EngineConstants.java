package io.boomerang.engine;

/** Shared operational constants for the engine's execution and sweep machinery. */
public final class EngineConstants {

  private EngineConstants() {}

  /** Grace added on top of a timeout budget so a run at exactly its budget is not reaped. */
  public static final long TIMEOUT_GRACE_MILLIS = 5000L;

  /** Page size for the level-triggered watcher/dispatcher sweeps. */
  public static final int SWEEP_PAGE_SIZE = 50;
}
