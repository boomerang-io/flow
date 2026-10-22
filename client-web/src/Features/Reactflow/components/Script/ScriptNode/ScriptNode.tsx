import React from "react";
import { WorkflowNodeProps } from "Types";
import { TemplateNode } from "../../Template";
import styles from "./ScripNode.module.scss";
import { ScriptForm } from "./ScriptForm";

export default function CustomTaskNode(props: WorkflowNodeProps) {
  return <TemplateNode {...props} className={styles.node} TaskForm={ScriptForm} />;
}
