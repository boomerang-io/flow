package io.boomerang.core.audit;

/**
 * Capture verbosity. Each {@link Audited} site declares the minimum level at which it fires; the
 * instance-wide {@code audit.level} setting picks the configured verbosity. Filtering happens at
 * capture time (like Kubernetes audit policy) — raising the level is NOT retroactive.
 *
 * <ul>
 *   <li>{@code DESTRUCTIVE} — deletes, purges, force-removals only.
 *   <li>{@code WRITE} (default) — all mutations: create/update/delete, membership, tokens.
 *   <li>{@code ALL} — adds sensitive READ/export events.
 * </ul>
 */
public enum AuditLevel {
  DESTRUCTIVE(0),
  WRITE(1),
  ALL(2);

  private final int rank;

  AuditLevel(int rank) {
    this.rank = rank;
  }

  /** True when a site declared at this level fires under the configured level. */
  public boolean enabledAt(AuditLevel configured) {
    return this.rank <= configured.rank;
  }

  /** Parse a settings value, falling back to WRITE on anything unrecognised. */
  public static AuditLevel fromString(String value) {
    if (value == null || value.isBlank()) {
      return WRITE;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return WRITE;
    }
  }
}
