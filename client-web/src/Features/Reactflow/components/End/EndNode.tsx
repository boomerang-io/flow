import React from "react";
import { Handle, Position, NodeProps, useEdges } from "@xyflow/react";
import cx from "classnames";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { useIsValidConnection, useIsValidFinallyConnection } from "../shared/finally";
import styles from "./EndNode.module.scss";

export default function EndNode(props: NodeProps) {
  const { mode } = useWorkflowContext();
  const { id, isConnectable } = props;
  const isEditor = mode === WorkflowEngineMode.Edit;
  const isValidTarget = useIsValidConnection();
  const isValidFinallyTarget = useIsValidFinallyConnection();
  const finallyCount = useEdges().filter((edge) => edge.source === id).length;
  return (
    <div className={cx(styles.node, { [styles.locked]: !isEditor })}>
      <Handle
        className={styles.port}
        isConnectable={isConnectable}
        isValidConnection={isValidTarget}
        position={Position.Left}
        type="target"
      />
      <div>
        <h2>End</h2>
        <p className={styles.finallyCount} data-testid="finally-count">
          {`${finallyCount} finally ${finallyCount === 1 ? "task" : "tasks"}`}
        </p>
      </div>
      <Handle
        className={cx(styles.port, styles.right)}
        data-testid="finally-port"
        isConnectable={isConnectable}
        isValidConnection={isValidFinallyTarget}
        position={Position.Right}
        type="source"
      />
    </div>
  );
}
