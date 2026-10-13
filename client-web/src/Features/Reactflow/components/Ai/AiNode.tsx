import React from "react";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowNodeProps } from "Types";
import { TemplateNode } from "../Template";
import styles from "./AiNode.module.scss";

/**
 * The `ai` task type renders as a Template/Custom node - same component, same generic
 * catalogue-driven config form - and differs only by its accent and the "AI" icon the seeded
 * task carries (Utils/taskIcons). Modelled on CustomTaskNode.
 */
export default function AiNode(props: WorkflowNodeProps) {
  const { mode } = useWorkflowContext();
  if (mode === WorkflowEngineMode.Run) {
    return <AiNodeExecution {...props} />;
  }

  return <AiNodeDesigner {...props} />;
}

function AiNodeDesigner(props: WorkflowNodeProps) {
  return <TemplateNode {...props} className={styles.node} />;
}

function AiNodeExecution(props: WorkflowNodeProps) {
  return <TemplateNode {...props} className={styles.node} />;
}
