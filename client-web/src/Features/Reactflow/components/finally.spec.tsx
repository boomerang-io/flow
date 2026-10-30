import React from "react";
import { render, screen } from "@testing-library/react";
import { Position, ReactFlow } from "@xyflow/react";
import { getEdgeType } from "Features/Reactflow/Reactflow";
import { EditorContextProvider, RunContextProvider, WorkflowProvider } from "State/context";
import { WorkflowEngineMode } from "Constants";
import type {
  Task,
  WorkflowCanvas,
  WorkflowEdge,
  WorkflowNode,
  WorkflowEdgeProps,
  WorkflowNodeProps,
  WorkflowRun,
} from "Types";
import { EndEdge, EndNode } from "./End";
import { TemplateNode } from "./Template";

/*
 * A finally task is an ordinary task linked from End with the finally condition: End counts and
 * offers those links, the link is fixed to that condition, and the task has no output port.
 */
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const taskRef = "task-ref";
const taskTemplate = { name: taskRef, icon: "bot", description: "Does things" } as unknown as Task;
const dimensions = { width: 144, height: 80 };

function node(id: string, type: string, name = id): WorkflowNode {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    ...dimensions,
    data: { name, taskRef, taskVersion: 1, upgradesAvailable: false, params: [], results: [] },
  } as unknown as WorkflowNode;
}

function edge(source: string, target: string, executionCondition: string): WorkflowEdge {
  return {
    id: `${source}-${target}`,
    source,
    target,
    type: source === "end" ? "end" : "template",
    data: { executionCondition, decisionCondition: "" },
  } as WorkflowEdge;
}

const nodeTypes = {
  end: EndNode,
  template: (props: WorkflowNodeProps) => <TemplateNode {...props} />,
};
const edgeTypes = { end: EndEdge };

function renderCanvas(
  nodes: Array<WorkflowNode>,
  edges: Array<WorkflowEdge>,
  mode: "edit" | "run" = WorkflowEngineMode.Edit,
  workflowRun?: unknown,
  children?: React.ReactNode,
) {
  return render(
    <EditorContextProvider
      value={{ availableParameters: [], revisionState: {} as WorkflowCanvas, workflowsQueryData: {} as any }}
    >
      <RunContextProvider
        value={{
          workflow: { nodes, edges } as unknown as WorkflowCanvas,
          workflowRun: (workflowRun ?? { tasks: [] }) as unknown as WorkflowRun,
        }}
      >
        <WorkflowProvider value={{ mode, tasks: { [taskRef]: [taskTemplate] } }}>
          <div style={{ width: 800, height: 600 }}>
            <ReactFlow defaultNodes={nodes} defaultEdges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}>
              {children}
            </ReactFlow>
          </div>
        </WorkflowProvider>
      </RunContextProvider>
    </EditorContextProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverStub);
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("getEdgeType", () => {
  const nodes = [node("start", "start"), node("end", "end"), node("a", "template")];

  it("makes a link from End a finally link", () => {
    expect(getEdgeType({ source: "end", target: "a", sourceHandle: null, targetHandle: null }, nodes)).toEqual({
      type: "end",
      data: { executionCondition: "finally", decisionCondition: "" },
    });
  });

  it("keeps an ordinary link as always, typed by its source", () => {
    expect(getEdgeType({ source: "a", target: "end", sourceHandle: null, targetHandle: null }, nodes)).toEqual({
      type: "template",
      data: { executionCondition: "always", decisionCondition: "" },
    });
  });
});

describe("End node --- finally tasks", () => {
  const nodes = [node("end", "end"), node("a", "template"), node("b", "template")];

  it("counts the links out of End", () => {
    renderCanvas(nodes, []);
    expect(screen.getByTestId("finally-count")).toHaveTextContent("0 finally tasks");
  });

  it("uses the singular for one", () => {
    renderCanvas(nodes, [edge("end", "a", "finally")]);
    expect(screen.getByTestId("finally-count")).toHaveTextContent(/^1 finally task$/);
  });

  it("counts several, in run mode too", () => {
    renderCanvas(nodes, [edge("end", "a", "finally"), edge("end", "b", "finally")], WorkflowEngineMode.Run, {
      tasks: [],
    });
    expect(screen.getByTestId("finally-count")).toHaveTextContent("2 finally tasks");
  });

  it("offers an output port", () => {
    renderCanvas(nodes, []);
    expect(screen.getByTestId("finally-port")).toBeInTheDocument();
  });
});

describe("Finally task node", () => {
  const nodes = [node("end", "end"), node("a", "template")];

  it("badges a task linked from End and gives it no output port", () => {
    renderCanvas(nodes, [edge("end", "a", "finally")]);
    expect(screen.getAllByTestId("finally-badge")).toHaveLength(1);
    expect(screen.queryByTestId("output-port")).not.toBeInTheDocument();
  });

  it("leaves an ordinary task with its badge-less look and its output port", () => {
    renderCanvas(nodes, []);
    expect(screen.queryByTestId("finally-badge")).not.toBeInTheDocument();
    expect(screen.getByTestId("output-port")).toBeInTheDocument();
  });
});

// The edge draws its label through the canvas's label layer, which only measured nodes would
// otherwise fill, so the edge is mounted inside the canvas directly.
function edgeProps(): WorkflowEdgeProps {
  return {
    id: "end-a",
    source: "end",
    target: "a",
    sourceX: 0,
    sourceY: 0,
    targetX: 100,
    targetY: 0,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: { executionCondition: "finally", decisionCondition: "" },
  } as unknown as WorkflowEdgeProps;
}

describe("Finally link", () => {
  it("shows the finally icon and a delete button in the editor", async () => {
    renderCanvas([], [], WorkflowEngineMode.Edit, undefined, <EndEdge {...edgeProps()} />);
    expect(await screen.findByTitle("logic-finally")).toBeInTheDocument();
    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("shows only the finally icon in a run, with no delete button", async () => {
    renderCanvas(
      [],
      [],
      WorkflowEngineMode.Run,
      { tasks: [{ name: "a", status: "succeeded" }] },
      <EndEdge {...edgeProps()} />,
    );
    expect(await screen.findByTitle("logic-finally")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
