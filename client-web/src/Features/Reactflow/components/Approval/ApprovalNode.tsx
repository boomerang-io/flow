import React from "react";
import { useWorkflowContext } from "Hooks";
import { useTeamContext } from "Hooks";
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
  const { team } = useTeamContext();

  const options =
    team.approverGroups?.map((approverGroup) => ({
      key: approverGroup.id,
      value: approverGroup.name,
    })) ?? [];

  const formInputsToMerge =
    options.length > 0
      ? [{ key: "approverGroupId", options }]
      : [{ key: "approverGroupId", disabled: true, description: "No approver groups configured for this team." }];

  return <TemplateNode {...props} formInputsToMerge={formInputsToMerge} />;
}

function ApprovalNodeRun(props: any) {
  return <TemplateNode {...props} />;
}
