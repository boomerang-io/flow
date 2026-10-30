import React from "react";
import cx from "classnames";
import { getBezierPath, useReactFlow, EdgeLabelRenderer } from "@xyflow/react";
import { markerTypes } from "Features/Reactflow/Reactflow";
import TaskLinkExecutionConditionSwitcher from "Components/TaskLinkExecutionConditionSwitcher";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import { useWorkflowContext } from "Hooks";
import { useRunContext } from "Hooks";
import { FINALLY_CONDITION } from "Utils/taskLinkIcons";
import { findTaskRunByName } from "Utils/taskRunHelper";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEdge, WorkflowEdgeProps, WorkflowNode } from "Types";
import styles from "./EndEdge.module.scss";

// The link from End to a finally task. It cannot change condition, so the label is only ever the
// disabled finally icon (and the delete button while editing).
export default function EndEdge(props: WorkflowEdgeProps) {
  const { mode } = useWorkflowContext();

  if (mode === WorkflowEngineMode.Run) {
    return <EndEdgeRun {...props} />;
  }

  return <EndEdgeEditor {...props} />;
}

export function EndEdgeEditor(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style } = props;
  const reactFlowInstance = useReactFlow<WorkflowNode, WorkflowEdge>();
  const { mode } = useWorkflowContext();
  const isEditor = mode === WorkflowEngineMode.Edit;

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
        className={cx("react-flow__edge-path", styles.editorEdge, { [styles.locked]: !isEditor })}
        d={edgePath}
        markerEnd={`url(#${markerTypes.end}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          className={cx("nodrag nopan", styles.edgeLabel)}
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
        >
          {isEditor ? (
            <WorkflowCloseButton className="" onClick={() => reactFlowInstance.deleteElements({ edges: [props] })} />
          ) : null}
          <TaskLinkExecutionConditionSwitcher disabled executionCondition={FINALLY_CONDITION} />
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export function EndEdgeRun(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style } = props;
  const { workflowRun, workflow } = useRunContext();

  // The workflow.nodes IDs change with each call, so need to keep it stable
  const targetNodeName = React.useRef(workflow.nodes.find((node) => node.id === props.target)?.data.name);
  const status = findTaskRunByName(workflowRun.tasks, targetNodeName.current)?.status ?? "";

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
        className={cx("react-flow__edge-path", styles.runEdge, styles[status])}
        d={edgePath}
        markerEnd={`url(#${markerTypes.end}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          className={cx("nodrag nopan", styles.edgeLabel)}
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
        >
          <TaskLinkExecutionConditionSwitcher disabled executionCondition={FINALLY_CONDITION} />
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
