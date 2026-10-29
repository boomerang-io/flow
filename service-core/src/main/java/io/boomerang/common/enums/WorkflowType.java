package io.boomerang.common.enums;

/**
 * What a workflow definition is for. {@code workflow} runs in its workspace; a {@code template} is
 * a system-workspace definition other workspaces run by reference, read-only, through a workflow
 * that carries a {@code templateRef}; a {@code starter} is a system-workspace definition a
 * workspace copies to make its own workflow. Absent reads as {@code workflow}. Unrelated to
 * {@link TaskType#template}, which is a catalogue task run as a container.
 */
public enum WorkflowType {
  workflow,
  template,
  starter;

  /** The type a stored or requested value means: absent is a plain workflow. */
  public static WorkflowType of(WorkflowType type) {
    return (type != null) ? type : workflow;
  }

  /** Whether the type is one only the system workspace holds. */
  public boolean isSystemOnly() {
    return this != workflow;
  }
}
