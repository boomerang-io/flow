# 0085 — Artifacts are uploaded by built-in tasks and expire into a kept record

**Status:** accepted · **Date:** 2026-09-28

## Context

Results cap at 1 MB, so large task output needs somewhere else to live. Decision 0045 proposed an object
store whose files are deleted when the run ends and declared on the task like results. People need to download
what a run produced, see it after it is gone, and stay inside storage limits.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Declared artifacts, deleted at run end (0045) | Artifacts only pass data between tasks | Nothing to download after the run; nothing to govern |
| B. Built-in upload and download tasks; a record per artifact in its own collection; the file expires, the record stays | People download and audit run output | One new collection; files deleted by a sweep |
| C. B, with the artifact list on `WorkflowRun` | Artifacts are only seen on their run | Workspace list and storage total scan every run; expiry rewrites runs |

## Decision

B. An artifact is created only by an upload task, never declared. Its record is `ArtifactEntity` in
`artifacts`, reached through its run (`workflowRunRef`, `taskRunRef`, `workflowRef`); no field is added to the
run and no quota rides on it. Links are one object, one method, 15 minutes; the task never holds store
credentials and never calls the engine. Retention, the largest artifact and the storage quota follow GitHub
Actions: admin settings plus a workspace quota; a task may only shorten its retention. When retention ends the
file is deleted and the record turns `expired` (`workflow/ArtifactService.java:324`). An embedding product
enforces its own tenants' quotas before it submits a run.

## Consequences

- Supersedes 0045. The store is any S3-compatible service; Azure Blob needs a second `ArtifactStore`.
- Limits are checked after upload, because the link is issued before the file exists, so an upload that
  breaks one is deleted and its task fails. Revisit if a task can report its size before uploading.
