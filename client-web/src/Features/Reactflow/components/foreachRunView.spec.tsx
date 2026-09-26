/* eslint-disable testing-library/no-node-access, testing-library/no-container --
 * A node's and an edge's run status is expressed only as a class on the node's root element and
 * the edge's svg path, which have no role or text for Testing Library queries to reach. */
import React from "react";
import { render, screen } from "@testing-library/react";
import { Position, ReactFlowProvider } from "@xyflow/react";
import { RunContextProvider, WorkflowProvider } from "State/context";
import { WorkflowEngineMode } from "Constants";
import { RunStatus, Task, WorkflowCanvas, WorkflowEdgeProps, WorkflowNodeData, WorkflowNodeProps } from "Types";
import { foreachWorkflowRun } from "Utils/testing/fixtures/foreachRun";
import { TemplateEdge, TemplateNode } from "./Template";
import DecisionEdge from "./Decision/DecisionEdge";

/*
 * The run diagram draws one node per task and colours edges by task status. For a for-each task
 * that must be the parent task run - never one of its items, which share the same `tasks` list
 * and carry their own, different statuses.
 */
const taskRef = "515c8b05-ceb0-470a-a58e-b8740b332a6a";
const taskTemplate = { name: taskRef, icon: "bot", description: "Locate things" } as unknown as Task;

const locateNode: { id: string; data: WorkflowNodeData } = {
  id: "node-locate",
  data: {
    name: "locate",
    taskRef,
    taskVersion: 1,
    upgradesAvailable: false,
    params: [],
    results: [],
    foreach: { items: "$(tasks.stage.results.repos)" },
  },
};
const stageNode = { ...locateNode, id: "node-stage", data: { ...locateNode.data, name: "stage", foreach: undefined } };
const endNode = { ...locateNode, id: "node-end", data: { ...locateNode.data, name: "end", foreach: undefined } };

const workflow = { nodes: [stageNode, locateNode, endNode], edges: [] } as unknown as WorkflowCanvas;

function renderRun(children: React.ReactNode) {
  return render(
    <ReactFlowProvider>
      <WorkflowProvider value={{ mode: WorkflowEngineMode.Run, tasks: { [taskRef]: [taskTemplate] } }}>
        <RunContextProvider value={{ workflow, workflowRun: foreachWorkflowRun }}>{children}</RunContextProvider>
      </WorkflowProvider>
    </ReactFlowProvider>,
  );
}

function nodeProps(node: { id: string; data: WorkflowNodeData }) {
  return { ...node, type: "templateTask", selected: false, dragging: false, zIndex: 0 } as unknown as WorkflowNodeProps;
}

function edgeProps(source: string, target: string) {
  return {
    id: `${source}-${target}`,
    source,
    target,
    sourceX: 0,
    sourceY: 0,
    targetX: 100,
    targetY: 0,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: { executionCondition: "always", decisionCondition: "" },
  } as unknown as WorkflowEdgeProps;
}

describe("Run diagram --- for each", () => {
  it("draws the for-each node from its parent task run, with the items' progress in its badge", () => {
    const { container } = renderRun(<TemplateNode {...nodeProps(locateNode)} />);

    const node = container.firstElementChild as HTMLElement;
    // The parent is running; its items are succeeded, running and failed - none may leak through.
    expect(node.className).toMatch(/_running_/);
    expect(node.className).not.toMatch(/_failed_|_succeeded_/);
    expect(node.className).toMatch(/_foreach_/);
    expect(screen.getByTestId("foreach-badge")).toHaveTextContent("For each · 1 of 3 succeeded");
  });

  it("draws an ordinary node without a for-each badge", () => {
    const { container } = renderRun(<TemplateNode {...nodeProps(stageNode)} />);

    expect((container.firstElementChild as HTMLElement).className).toMatch(/_succeeded_/);
    expect(screen.queryByTestId("foreach-badge")).not.toBeInTheDocument();
  });

  it("colours a template edge into a for-each task by the parent's status", () => {
    const { container } = renderRun(
      <svg>
        <TemplateEdge {...edgeProps("node-stage", "node-locate")} />
      </svg>,
    );

    const path = container.querySelector("path") as SVGPathElement;
    expect(path.getAttribute("class")).toMatch(/_running_/);
  });

  it("colours a template edge out of a for-each task into end by the parent's status", () => {
    const { container } = renderRun(
      <svg>
        <TemplateEdge {...edgeProps("node-locate", "node-end")} />
      </svg>,
    );

    const path = container.querySelector("path") as SVGPathElement;
    expect(path.getAttribute("class")).toMatch(/_running_/);
    expect(path.getAttribute("class")).not.toMatch(new RegExp(`_${RunStatus.Failed}_`));
  });

  it("colours a decision edge out of a for-each task by the parent's status", () => {
    const { container } = renderRun(
      <svg>
        <DecisionEdge {...edgeProps("node-locate", "node-end")} />
      </svg>,
    );

    const path = container.querySelector("path") as SVGPathElement;
    expect(path.getAttribute("class")).toMatch(/_running_/);
  });
});
