package io.boomerang.common.enums;

/*
 * Status for Manual Action and Approval Action
 *
 * Shared with the Workflow Service
 *
 * Created with 'submitted'. Moved to 'approved' or 'rejected'.
 */
public enum ActionStatus {
  approved,
  submitted,
  rejected,
  cancelled // NOSONAR
}
