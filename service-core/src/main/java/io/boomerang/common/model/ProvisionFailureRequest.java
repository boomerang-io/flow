package io.boomerang.common.model;

/**
 * A dispatcher's report that it could not provision a workflow run it claimed: {@code message} is
 * the cause, shown on the run. The engine holds the claim and retries after a delay, or fails the
 * run on its last attempt.
 */
public record ProvisionFailureRequest(String message) {}
