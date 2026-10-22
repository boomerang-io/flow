package io.boomerang.event.enums;

/** Processing state of an inbound event: received on insert, processed once delivered. */
public enum InboxStatus {
  received,
  processed
}
