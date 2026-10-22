import React from "react";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowNodeProps } from "Types";
import { TemplateNode } from "../Template";
import { RunScheduledWorkflowForm } from "./RunScheduledWorkflowForm";

export default function RunScheduledWorkflowNode(props: WorkflowNodeProps) {
  const { mode } = useWorkflowContext();
  if (mode === WorkflowEngineMode.Run) {
    return <RunScheduledWorkflowRun {...props} />;
  }
  return <RunScheduledWorkflowEditor {...props} />;
}

function RunScheduledWorkflowEditor(props: WorkflowNodeProps) {
  return <TemplateNode {...props} TaskForm={RunScheduledWorkflowForm} />;
}

function RunScheduledWorkflowRun(props: any) {
  return <TemplateNode {...props} TaskForm={RunScheduledWorkflowForm} />;
}
