package io.boomerang.common.model;

/**
 * Domain event published by the engine's RunWorkflow task after it submits a child WorkflowRun,
 * so the owning Workspace's relationship graph can be updated with the new run. Replaces the former
 * WorkflowClient.createWorkflowRunRelationship() HTTP callback into InternalController - the core
 * module owns the relationship write and listens for this event in-process.
 *
 * @param workflowRef the child Workflow's reference (used to resolve its owning Workspace)
 * @param workflowRunRef the newly-created child WorkflowRun's id
 */
public record ChildWorkflowRunCreated(String workflowRef, String workflowRunRef) {}
