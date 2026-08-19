import React from "react";
import cx from "classnames";
import { getBezierPath, useReactFlow, EdgeLabelRenderer } from "reactflow";
import { markerTypes } from "Features/Reactflow/Reactflow";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import { useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEdgeProps } from "Types";
import styles from "./StartNode.module.scss";

export function StartEdge(props: WorkflowEdgeProps) {
  const { mode } = useWorkflowContext();

  if (mode === WorkflowEngineMode.Run) {
    return <StartEdgeRun {...props} />;
  }

  return <StartEdgeEditor {...props} />;
}

// There is a base link component that we are going to want simliar functionality from
function StartEdgeEditor(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style } = props;
  const { mode } = useWorkflowContext();
  const reactFlowInstance = useReactFlow();

  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const mergedStyles = {
    ...style,
    strokeWidth: "2",
  };

  const isEditor = mode === WorkflowEngineMode.Edit;

  return (
    <>
      <path
        id={id}
        className={cx("react-flow__edge-path", styles.edge, { [styles.locked]: !isEditor })}
        d={edgePath}
        markerEnd={`url(#${markerTypes.template}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
          className={cx("nodrag nopan", styles.edgeLabel)}
        >
          {isEditor ? (
            <WorkflowCloseButton className="" onClick={() => reactFlowInstance.deleteElements({ edges: [props] })} />
          ) : null}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

function StartEdgeRun(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style } = props;

  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const mergedStyles = {
    ...style,
    strokeWidth: "2",
  };

  return (
    <>
      <path
        id={id}
        className={cx("react-flow__edge-path", styles.edge, styles.locked)}
        d={edgePath}
        markerEnd={`url(#${markerTypes.template}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
          className={cx("nodrag nopan", styles.edgeLabel)}
        />
      </EdgeLabelRenderer>
    </>
  );
}
