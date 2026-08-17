import React from "react";
import cx from "classnames";
import { getBezierPath, useReactFlow, EdgeLabelRenderer } from "reactflow";
import { markerTypes } from "Features/Reactflow/Reactflow";
import TaskLinkExecutionConditionSwitcher from "Components/TaskLinkExecutionConditionSwitcher";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import { useWorkflowContext } from "Hooks";
import { useRunContext } from "Hooks";
import { EXECUTION_CONDITIONS } from "Utils/taskLinkIcons";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEdge, WorkflowEdgeProps } from "Types";
import styles from "./TemplateEdge.module.scss";

export default function TaskTemplateEdge(props: WorkflowEdgeProps) {
  const { mode } = useWorkflowContext();

  if (mode === WorkflowEngineMode.Run) {
    return <TemplateEdgeRun {...props} />;
  }

  return <TemplateEdgeEditor {...props} />;
}

export function TemplateEdgeEditor(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data } = props;
  const reactFlowInstance = useReactFlow();
  const { mode } = useWorkflowContext();
  const isEditor = mode === WorkflowEngineMode.Edit;

  const executionConditionIndex = EXECUTION_CONDITIONS.findIndex(
    (condition) => condition.name === data?.executionCondition,
  );
  const condition = executionConditionIndex >= 0 ? executionConditionIndex : 0;

  const handleChangeCondition = (newCondition: number) => {
    const edges = reactFlowInstance.getEdges() as Array<WorkflowEdge>;
    const newEdges = edges.map((edge) => {
      if (edge.id === props.id) {
        return {
          ...edge,
          data: { ...edge.data, executionCondition: EXECUTION_CONDITIONS[newCondition].name },
        };
      } else {
        return edge;
      }
    }) as Array<WorkflowEdge>;

    reactFlowInstance.setEdges(newEdges);
  };

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
        markerEnd={`url(#${markerTypes.template}`}
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
            <>
              <WorkflowCloseButton className="" onClick={() => reactFlowInstance.deleteElements({ edges: [props] })} />
              <TaskLinkExecutionConditionSwitcher
                onClick={() => handleChangeCondition((condition + 1) % 3)}
                executionCondition={EXECUTION_CONDITIONS[condition]}
              />
            </>
          ) : (
            <>
              <TaskLinkExecutionConditionSwitcher disabled executionCondition={EXECUTION_CONDITIONS[condition]} />
            </>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export function TemplateEdgeRun(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data } = props;
  const { workflowRun, workflow } = useRunContext();

  // The workflow.nodes IDs change with each call, so need to keep it stable
  const sourceNodeName = React.useRef(workflow.nodes.find((node) => node.id === props.source)?.data.name);
  const targetNodeName = React.useRef(workflow.nodes.find((node) => node.id === props.target)?.data.name);

  let status = "";
  if (targetNodeName.current === "end") {
    status = workflowRun.tasks.find((task) => task.name === sourceNodeName.current)?.status ?? "";
  } else {
    status = workflowRun.tasks.find((task) => task.name === targetNodeName.current)?.status ?? "";
  }

  const executionConditionIndex = EXECUTION_CONDITIONS.findIndex(
    (condition) => condition.name === data?.executionCondition,
  );
  const condition = executionConditionIndex >= 0 ? executionConditionIndex : 0;

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
        markerEnd={`url(#${markerTypes.template}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          className={cx("nodrag nopan", styles.edgeLabel)}
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
        >
          <TaskLinkExecutionConditionSwitcher disabled executionCondition={EXECUTION_CONDITIONS[condition]} />
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
