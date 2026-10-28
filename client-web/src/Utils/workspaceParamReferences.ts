import { InputType } from "Constants";
import type { Task, WorkflowNode } from "Types";

/** A task param that reads a workspace parameter the workspace does not provide. */
export interface MissingWorkspaceParam {
  node: string;
  param: string;
  name: string;
}

// $(workspace.params.<name>), or the older $(team.params.<name>), anywhere in a value; the scope matches in any
// case, as it does at run time.
const WORKSPACE_REFERENCE = /\$\((?:workspace|team)\.params\.([\w-]+)/gi;
const WORKSPACE_KEY = "workspace.params.";

/**
 * The workspace parameters a run of these nodes would read that the workspace does not provide, so the run would
 * fail at that task. A node's value wins over its task's default, as at run time, except a password left empty,
 * which falls back to the default; a default counts only while the node pins the task version the editor holds.
 * Names match in any case, as admission does. `availableParameters` holds `workspace.params.<name>` for each
 * workspace parameter with a value, and none while workspace parameters are off.
 */
export function missingWorkspaceParams(
  nodes: Array<WorkflowNode>,
  tasksByName: Record<string, Array<Task>>,
  availableParameters: Array<string>,
): Array<MissingWorkspaceParam> {
  const provided = new Set(
    availableParameters
      .filter((key) => key.toLowerCase().startsWith(WORKSPACE_KEY))
      .map((key) => key.slice(WORKSPACE_KEY.length).toLowerCase()),
  );
  const missing: Array<MissingWorkspaceParam> = [];
  for (const node of nodes) {
    const task = tasksByName[node.data.taskRef]?.[0];
    const declared = task && task.version === node.data.taskVersion ? (task.spec.params ?? []) : [];
    const values = new Map<string, { param: string; value: unknown }>();
    declared.forEach((param) =>
      values.set(param.name.toLowerCase(), { param: param.name, value: param.default ?? param.defaultValue }),
    );
    for (const param of node.data.params ?? []) {
      const spec = declared.find((candidate) => candidate.name.toLowerCase() === param.name.toLowerCase());
      const empty = param.value === undefined || param.value === null || param.value === "";
      if (!(empty && spec?.type === InputType.Password)) {
        values.set(param.name.toLowerCase(), { param: spec?.name ?? param.name, value: param.value });
      }
    }
    for (const { param, value } of values.values()) {
      if (typeof value === "string") {
        for (const reference of value.matchAll(WORKSPACE_REFERENCE)) {
          if (!provided.has(reference[1].toLowerCase())) {
            missing.push({ node: node.data.name, param, name: reference[1] });
          }
        }
      }
    }
  }
  return missing;
}

/** One sentence naming the first three missing references, for a warning at save. */
export function missingWorkspaceParamsMessage(missing: Array<MissingWorkspaceParam>): string {
  const named = missing
    .slice(0, 3)
    .map(({ node, param, name }) => `"${node}" › ${param} reads $(workspace.params.${name})`)
    .join("; ");
  const more = missing.length > 3 ? `; and ${missing.length - 3} more` : "";
  return `${named}${more}. This workspace does not provide ${missing.length > 1 ? "them" : "it"}, so a run fails at that task.`;
}
