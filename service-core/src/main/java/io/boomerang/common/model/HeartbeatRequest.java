package io.boomerang.common.model;

import java.util.List;

/**
 * Dispatcher lease heartbeat: the TaskRun ids this dispatcher's executor threads are still working
 * on. Sent as one batch per dispatcher per beat interval; the engine renews {@code
 * claim.leaseExpiresAt} for the ids it still owns and ignores the rest.
 */
public record HeartbeatRequest(List<String> ids) {}
