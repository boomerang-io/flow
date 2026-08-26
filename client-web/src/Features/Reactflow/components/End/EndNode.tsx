import React from "react";
import cx from "classnames";
import { Handle, IsValidConnection, Position, NodeProps } from "@xyflow/react";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import styles from "./EndNode.module.scss";

export default function EndNode(props: NodeProps) {
  const { mode } = useWorkflowContext();
  const { isConnectable } = props;
  return (
    <div className={cx(styles.node, { [styles.locked]: mode !== WorkflowEngineMode.Edit })}>
      <Handle
        className={styles.port}
        isConnectable={isConnectable}
        isValidConnection={isValidHandle}
        position={Position.Left}
        type="target"
      />
      <h2>End</h2>
    </div>
  );
}
// See StartNode.tsx for why this is typed via `IsValidConnection` rather than `Connection`.
const isValidHandle: IsValidConnection = (connection) => connection.source !== connection.target;
