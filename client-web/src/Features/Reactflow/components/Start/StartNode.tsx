import React from "react";
import cx from "classnames";
import { Handle, Position, NodeProps } from "@xyflow/react";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { useIsValidConnection } from "../shared/finally";
import styles from "./StartNode.module.scss";

export function StartNode(props: NodeProps) {
  const { mode } = useWorkflowContext();
  const { isConnectable } = props;
  const isValidHandle = useIsValidConnection();
  return (
    <div className={cx(styles.node, { [styles.locked]: mode !== WorkflowEngineMode.Edit })}>
      <h2>Start</h2>
      <Handle
        className={cx(styles.port)}
        position={Position.Right}
        isConnectable={isConnectable}
        isValidConnection={isValidHandle}
        type="source"
      />
    </div>
  );
}

