package io.boomerang.core.audit;

import java.util.Date;
import java.util.Map;

/**
 * Counts for the window and filters a listing request carries, plus the capture configuration a
 * reader needs to interpret them: an empty trail means something different when capture is off, or
 * when the configured level never records the action being looked for.
 *
 * @param from start of the counted window, as applied (the default when the caller sent none)
 * @param to end of the counted window, or null for "up to now"
 * @param total events matching the filters in the window
 * @param outcomes count per {@link AuditOutcome}; every outcome is present, zero included
 * @param captureEnabled the {@code audit.enabled} setting
 * @param level the configured {@code audit.level} - sites above it never fire
 * @param retentionDays the applied {@code audit.retentionDays}, floored as the TTL is
 */
public record AuditStats(
    Date from,
    Date to,
    long total,
    Map<AuditOutcome, Long> outcomes,
    boolean captureEnabled,
    AuditLevel level,
    int retentionDays) {}
