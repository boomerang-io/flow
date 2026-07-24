package io.boomerang.engine.repository;

import io.boomerang.engine.entity.TaskLockEntity;
import java.util.Date;

/** Atomic acquire/release for the user-facing cross-workflow lock, keyed by the lock document. */
public interface TaskLockRepositoryCustom {

  /**
   * Acquire the lock in one atomic step: take it when the key is unheld or its lease has expired,
   * never stealing a live lock held by another task. Returns the acquired document when this task
   * now holds it, or {@code null} when another task holds a live lease.
   */
  TaskLockEntity tryAcquire(String scopedKey, String taskRunRef, String workflowRunRef, Date expiresAt);

  /** Release only when held by this task; idempotent - a missing or expired lock is a no-op. */
  void release(String scopedKey, String taskRunRef);
}
