import React from "react";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowNodeProps } from "Types";
import { TemplateNode } from "../../Template";
import styles from "./DecisionNode.module.scss";

export default function DecisionsNode(props: WorkflowNodeProps) {
  const { mode } = useWorkflowContext();
  if (mode === WorkflowEngineMode.Run) {
    return <DecisionNodeRun {...props} />;
  }
  return <DecisionNodeEditor {...props} />;
}

function DecisionNodeEditor(props: WorkflowNodeProps) {
  return <TemplateNode {...props} className={styles.node} />;
}

function DecisionNodeRun(props: any) {
  return <TemplateNode {...props} className={styles.node} />;
}
