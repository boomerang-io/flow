package io.boomerang.core.audit;

/**
 * Result of an audited attempt. One event is recorded per attempt — including failed and denied
 * ones, which are the most security-valuable. Maps to OCSF status (success/failure) plus
 * disposition (allowed/denied) on export.
 */
public enum AuditOutcome {
  SUCCESS,
  FAILED,
  DENIED
}
