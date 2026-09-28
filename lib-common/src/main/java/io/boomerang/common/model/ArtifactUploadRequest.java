package io.boomerang.common.model;

/**
 * A dispatcher's request for an upload link on behalf of an upload task: the artifact's name
 * (unique in the run), the retention the task asked for (null for the workspace's), and the
 * dispatcher's registered id, which the engine fences on like every other TaskRun callback.
 */
public record ArtifactUploadRequest(String name, Integer retentionDays, String dispatcherRef) {}
