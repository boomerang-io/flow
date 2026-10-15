package io.boomerang.common.enums;

/*
 * How long an artifact is meant to matter. A run-scoped artifact belongs to the run that uploaded
 * it, can only be downloaded by that run, and expires on its retention date. A workflow-scoped
 * artifact is the workflow's kept state: any later run of the workflow can download it by name,
 * it has no expiry, and a later verified upload of the same name replaces it. Absent on a stored
 * record reads as run.
 */
public enum ArtifactScope {
  run,
  workflow
}
