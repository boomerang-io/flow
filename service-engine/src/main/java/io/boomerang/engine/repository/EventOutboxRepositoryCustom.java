package io.boomerang.engine.repository;

import io.boomerang.engine.entity.EventOutboxEntity;
import java.util.Date;
import java.util.List;

/**
 * Compare-And-Set transitions for outbox rows. A {@code null} return means another dispatcher won
 * the transition and this caller must perform no side effects.
 */
public interface EventOutboxRepositoryCustom {

  /** Return the page of pending rows whose retry backoff has elapsed, oldest first. */
  List<EventOutboxEntity> findDeliverable(Date now, int limit);

  /** Delivery Compare-And-Set: pending becomes sent with the given timestamp. */
  EventOutboxEntity tryMarkSent(String id, Date sentAt);

  /** Failure Compare-And-Set: a pending row is parked until the backoff elapses. */
  EventOutboxEntity tryRequeueDelivery(String id, Date retryAfter, int attempts);

  /** Exhaustion Compare-And-Set: a pending row is marked dead - a status and a log, not a loss. */
  EventOutboxEntity tryMarkDead(String id);
}
