import type { Workflow, WorkflowSummary } from "Types";

type TemplateAware = Pick<Workflow | WorkflowSummary, "type" | "templateRef">;

/** A shared template the workspace has not made its own yet. */
export function isTemplate(workflow?: TemplateAware | null): boolean {
  return workflow?.type === "template";
}

/** A workflow the workspace owns that follows a template. */
export function isFromTemplate(workflow?: TemplateAware | null): boolean {
  return Boolean(workflow?.templateRef);
}

/** The workspace that owns and edits the templates and starters. */
export const SYSTEM_WORKSPACE = "system";

export function isSystemWorkspace(workspace?: string | null): boolean {
  return workspace === SYSTEM_WORKSPACE;
}

/**
 * Tasks, parameters and run options belong to the template, so the editor shows them read-only.
 * The system workspace owns its templates and starters, so nothing is read-only there.
 */
export function isReadOnlyTasks(workflow?: TemplateAware | null, workspace?: string | null): boolean {
  if (isSystemWorkspace(workspace)) {
    return false;
  }
  return isTemplate(workflow) || isFromTemplate(workflow);
}
