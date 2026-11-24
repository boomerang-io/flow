package io.boomerang.core.audit;

/**
 * The workspace an audit event belongs to: its id, the key every reader of the trail filters and
 * counts on, and its name at the time, for display. Both null for an instance-scoped event.
 */
public record AuditWorkspace(String id, String name) {

  public static final AuditWorkspace NONE = new AuditWorkspace(null, null);
}
