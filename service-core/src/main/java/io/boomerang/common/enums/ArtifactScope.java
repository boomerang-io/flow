package io.boomerang.common.enums;

/*
 * How long an artifact is meant to matter. A run-scoped artifact belongs to the run that uploaded
 * it, can only be downloaded by that run, and expires on its retention date. A workflow-scoped
 * artifact is the workflow's kept state: any later run of the workflow can download it by name,
 * it has no expiry, and a later verified upload of the same name replaces it. A log is a task
 * run's finished log as its dispatcher uploaded it: run retention, never listed with the run's
 * artifacts or counted toward storage, read only through the task run's log route. Absent on a
 * stored record reads as run.
 */
public enum ArtifactScope {
  run,
  workflow,
  log
}
