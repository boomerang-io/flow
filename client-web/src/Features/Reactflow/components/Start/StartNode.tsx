import React from "react";
import cx from "classnames";
import { Connection, Handle, Position, NodeProps } from "reactflow";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import styles from "./StartNode.module.scss";

export function StartNode(props: NodeProps) {
  const { mode } = useWorkflowContext();
  const { isConnectable } = props;
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

function isValidHandle(connection: Connection) {
  return connection.source !== connection.target;
}
