import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ReactFlow, useNodes } from "@xyflow/react";
import { aiTask } from "ApiServer/fixtures";
import { EditorContextProvider, WorkflowProvider } from "State/context";
import { WorkflowEngineMode } from "Constants";
import type { Task, WorkflowCanvas, WorkflowNode, WorkflowNodeData, WorkflowNodeProps } from "Types";
import TemplateNode from "./TemplateNode";

/*
 * What Apply writes back to the node. A task's params are what the form edits; its results are
 * either the template's (shown read-only, never saved as a param) or, for custom and script
 * tasks, the user's own, handed over separately.
 */
const task = aiTask as unknown as Task;
const taskRef = "ai";

const nodeData = {
  name: "ask",
  taskRef,
  taskVersion: 1,
  upgradesAvailable: false,
  params: [
    { name: "endpoint", value: "https://models.example.com" },
    { name: "token", value: "secret" },
    { name: "model", value: "a-model" },
    { name: "prompt", value: "Summarise" },
  ],
  results: [{ name: "summary", description: "kept" }],
} as unknown as WorkflowNodeData;

const initialNode = { id: "node-ask", type: "ai", position: { x: 0, y: 0 }, data: nodeData } as unknown as WorkflowNode;

let latestNodes: Array<WorkflowNode> = [];
function NodesProbe() {
  latestNodes = useNodes() as Array<WorkflowNode>;
  return null;
}

// jsdom has no ResizeObserver, which <ReactFlow> needs to mount; node edits only apply through a
// mounted <ReactFlow>, so the spec renders the real canvas around the one node.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

// jsdom cannot parse the ':focus-visible' selector the canvas checks when a node takes focus.
const matches = Element.prototype.matches;
function matchesWithoutFocusVisible(this: Element, selector: string) {
  return selector === ":focus-visible" ? false : matches.call(this, selector);
}

async function renderNode(TaskForm?: React.FC<any>) {
  vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  vi.spyOn(Element.prototype, "matches").mockImplementation(matchesWithoutFocusVisible);
  const nodeTypes = { ai: (props: WorkflowNodeProps) => <TemplateNode {...props} TaskForm={TaskForm} /> };
  render(
    <div style={{ width: 800, height: 600 }}>
      <EditorContextProvider
        value={{ availableParameters: [], revisionState: {} as WorkflowCanvas, workflowsQueryData: {} as any }}
      >
        <WorkflowProvider value={{ mode: WorkflowEngineMode.Edit, tasks: { [taskRef]: [task] } }}>
          <ReactFlow defaultNodes={[initialNode]} nodeTypes={nodeTypes}>
            <NodesProbe />
          </ReactFlow>
        </WorkflowProvider>
      </EditorContextProvider>
    </div>,
  );
  // Unmeasured in jsdom, the canvas keeps its nodes hidden, so the edit button has no accessible name.
  fireEvent.click(await screen.findByTitle("edit"));
}

function savedNode() {
  return latestNodes.find((node) => node.id === initialNode.id)!.data;
}

// `hidden: true` because react-modal marks the whole <body> aria-hidden while the edit modal is open.
describe("Template task node --- what Apply saves", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("saves the default form's params with no results param and keeps the node's results", async () => {
    await renderNode();

    const apply = await screen.findByRole("button", { hidden: true, name: "Apply" });
    await waitFor(() => expect(apply).toBeEnabled());
    fireEvent.click(apply);

    await waitFor(() => expect(savedNode()).not.toBe(nodeData));
    const saved = savedNode();
    expect(saved.params.map((param) => param.name)).not.toContain("results");
    expect(saved.params).toContainEqual({ name: "model", value: "a-model" });
    expect(saved.results).toEqual([{ name: "summary", description: "kept" }]);
  });

  it("stores the results a custom or script form hands over as the node's results, never as a param", async () => {
    const userResults = [{ name: "out", description: "user defined" }];
    function UserResultsForm(props: { onSave: (inputs: Record<string, unknown>, results?: unknown) => void }) {
      return (
        <button type="button" onClick={() => props.onSave({ taskName: "ask", model: "b-model" }, userResults)}>
          Save user results
        </button>
      );
    }
    await renderNode(UserResultsForm);

    fireEvent.click(await screen.findByRole("button", { hidden: true, name: "Save user results" }));

    await waitFor(() => expect(savedNode().results).toEqual(userResults));
    expect(savedNode().params).toEqual([{ name: "model", value: "b-model" }]);
  });
});
