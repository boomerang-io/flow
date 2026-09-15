package io.boomerang.event.enums;

/**
 * How much of a run an outbound status CloudEvent carries. {@code thin} is the identity and
 * lifecycle only - the consumer reads the run back over the API for anything else; {@code full}
 * carries the whole public run model, params and results included.
 */
public enum EventPayload {
  thin,
  full
}
