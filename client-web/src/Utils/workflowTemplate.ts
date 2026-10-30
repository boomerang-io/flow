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

/** Tasks, parameters and run options belong to the template, so the editor shows them read-only. */
export function isReadOnlyTasks(workflow?: TemplateAware | null): boolean {
  return isTemplate(workflow) || isFromTemplate(workflow);
}
