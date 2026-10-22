import React from "react";
import cx from "classnames";
import { Handle, IsValidConnection, Position, NodeProps } from "@xyflow/react";
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

// v12 broadens `isValidConnection`'s parameter from just `Connection` to `Connection | Edge`
// (it's also invoked when validating a reconnection of an existing edge) - typing this with
// xyflow's own `IsValidConnection` picks that up instead of re-narrowing it back to `Connection`.
const isValidHandle: IsValidConnection = (connection) => connection.source !== connection.target;
