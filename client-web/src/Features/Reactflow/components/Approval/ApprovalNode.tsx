import React from "react";
import { useWorkflowContext } from "Hooks";
import { useWorkspaceContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowNodeProps } from "Types";
import { TemplateNode } from "../Template";

export default function ApprovalNode(props: WorkflowNodeProps) {
  const { mode } = useWorkflowContext();
  if (mode === WorkflowEngineMode.Run) {
    return <ApprovalNodeRun {...props} />;
  }

  return <ApprovalNodeEditor {...props} />;
}

function ApprovalNodeEditor(props: WorkflowNodeProps) {
  const { workspace } = useWorkspaceContext();

  const options =
    workspace.approverGroups?.map((approverGroup) => ({
      key: approverGroup.id,
      value: approverGroup.name,
    })) ?? [];

  // Matched to the template's param by `name`, the field a task param is keyed on.
  const formInputsToMerge =
    options.length > 0
      ? [{ name: "approverGroupId", options }]
      : [{ name: "approverGroupId", disabled: true, helperText: "No approver groups configured for this workspace." }];

  return <TemplateNode {...props} formInputsToMerge={formInputsToMerge} />;
}

function ApprovalNodeRun(props: any) {
  return <TemplateNode {...props} />;
}
