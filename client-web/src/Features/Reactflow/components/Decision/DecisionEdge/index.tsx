import React from "react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import { getBezierPath, EdgeLabelRenderer, useReactFlow } from "reactflow";
import { markerTypes } from "Features/Reactflow/Reactflow";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import { useRunContext, useWorkflowContext } from "Hooks";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEdge, WorkflowEdgeProps } from "Types";
import ConfigureSwitchModal from "./ConfigureModal";
import styles from "./DecisionEdge.module.scss";
import ExecutionConditionButton from "./ExecutionConditionButton";

export default function SwitchEdge(props: WorkflowEdgeProps) {
  const { mode } = useWorkflowContext();

  if (mode === WorkflowEngineMode.Run) {
    return <SwitchEdgeRun {...props} />;
  }

  return <SwitchEdgeEditor {...props} />;
}

function SwitchEdgeEditor(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data } = props;
  const { mode } = useWorkflowContext();
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const reactFlowInstance = useReactFlow();

  const mergedStyles = {
    ...style,
    strokeWidth: "2",
  };

  const handleChangeCondition = (decisionCondition: string) => {
    const edges = reactFlowInstance.getEdges() as Array<WorkflowEdge>;
    const newEdges = edges.map((edge) => {
      if (edge.id === props.id) {
        return {
          ...edge,
          data: { ...edge.data, decisionCondition },
        };
      } else {
        return edge;
      }
    }) as Array<WorkflowEdge>;

    reactFlowInstance.setEdges(newEdges);
  };

  return (
    <>
      <path
        id={id}
        className={cx("react-flow__edge-path", styles.editorEdge)}
        d={edgePath}
        markerEnd={`url(#${markerTypes.decision}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          className={cx("nodrag", "nopan", styles.edgeLabel)}
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
        >
          {mode === WorkflowEngineMode.Edit ? (
            <>
              <WorkflowCloseButton
                style={{ path: "var(--flow-switch-primary)" }}
                className=""
                onClick={() => reactFlowInstance.deleteElements({ edges: [props] })}
              />
              <ComposedModal
                confirmModalProps={{
                  title: "Are you sure?",
                  children: "Your changes will not be saved",
                }}
                modalHeaderProps={{
                  title: "Switch",
                  subtitle: "Set up the conditions",
                }}
                modalTrigger={(props) => {
                  return <ExecutionConditionButton onClick={props.openModal} inputText={data?.decisionCondition} />;
                }}
              >
                {({ closeModal }) => {
                  return (
                    <ConfigureSwitchModal
                      closeModal={closeModal}
                      decisionCondition={data?.decisionCondition}
                      onUpdate={handleChangeCondition}
                    />
                  );
                }}
              </ComposedModal>
            </>
          ) : (
            <ExecutionConditionButton disabled inputText={data?.decisionCondition} />
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

function SwitchEdgeRun(props: WorkflowEdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data } = props;
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

  const { workflowRun, workflow } = useRunContext();

  // The workflow.nodes IDs change with each call, so need to keep it stable
  const nodeName = React.useRef(workflow.nodes.find((node) => node.id === props.source)?.data.name);
  const status = workflowRun.tasks.find((task) => task.name === nodeName.current)?.status ?? "";

  return (
    <>
      <path
        id={id}
        className={cx("react-flow__edge-path", styles.runEdge, styles[status])}
        d={edgePath}
        markerEnd={`url(#${markerTypes.decision}`}
        style={mergedStyles}
      />
      <EdgeLabelRenderer>
        <div
          className={cx("nodrag", "nopan", styles.edgeLabel)}
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
          }}
        >
          <ExecutionConditionButton disabled onClick={() => {}} inputText={data?.decisionCondition} />
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
