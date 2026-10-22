package io.boomerang.common.enums;

/*
 * Enum containing all Workflow Schedule Status'
 *
 * If you add or remove a status you need to ensure linked code is updated, including;
 * - ScheduleServiceImpl
 * - WorkflowScheduleServiceImpl
 */
public enum WorkflowScheduleStatus {
  active,
  inactive,
  trigger_disabled,
  error,
  completed,
  deleted // NOSONAR
}
