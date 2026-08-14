package io.boomerang.event.enums;

/** Delivery state of an outbox row: pending until delivered, dead after retries are exhausted. */
public enum OutboxStatus {
  pending,
  sent,
  dead
}
