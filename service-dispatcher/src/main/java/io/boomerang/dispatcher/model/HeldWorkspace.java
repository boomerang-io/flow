package io.boomerang.dispatcher.model;

/**
 * One workspace this dispatcher holds storage for, read back from the runtime's own labels. Either
 * field can be null when the storage carries incomplete labels; the reconciler ignores those.
 */
public record HeldWorkspace(String workspaceRef, String workspaceType) {}
