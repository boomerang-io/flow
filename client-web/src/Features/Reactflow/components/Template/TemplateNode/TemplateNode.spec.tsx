import React from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

async function renderNode(
  TaskForm?: React.FC<any>,
  { data = nodeData, latest = task, open = true }: { data?: WorkflowNodeData; latest?: Task; open?: boolean } = {},
) {
  vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  vi.spyOn(Element.prototype, "matches").mockImplementation(matchesWithoutFocusVisible);
  const nodeTypes = { ai: (props: WorkflowNodeProps) => <TemplateNode {...props} TaskForm={TaskForm} /> };
  const view = render(
    <div style={{ width: 800, height: 600 }}>
      <EditorContextProvider
        value={{ availableParameters: [], revisionState: {} as WorkflowCanvas, workflowsQueryData: {} as any }}
      >
        <WorkflowProvider value={{ mode: WorkflowEngineMode.Edit, tasks: { [taskRef]: [latest] } }}>
          <ReactFlow defaultNodes={[{ ...initialNode, data }]} nodeTypes={nodeTypes}>
            <NodesProbe />
          </ReactFlow>
        </WorkflowProvider>
      </EditorContextProvider>
    </div>,
  );
  if (open) {
    // Unmeasured in jsdom, the canvas keeps its nodes hidden, so the edit button has no accessible name.
    fireEvent.click(await screen.findByTitle("edit"));
  }
  return view;
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

  it("shows the task's description once in the modal header, on one line that carries the full text as its title", async () => {
    await renderNode();

    const description = task.description as string;
    const dialog = await screen.findByRole("dialog", { hidden: true });
    const subtitle = within(dialog).getByTitle(description);
    expect(subtitle).toHaveTextContent(description);
    expect(within(dialog).getAllByText(description)).toHaveLength(1);
    // The name and the tabs follow the description inside the dialog, the name first.
    const name = within(dialog).getByLabelText("Task name");
    const tabList = within(dialog).getByRole("tablist", { hidden: true });
    expect(subtitle.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(name.compareDocumentPosition(tabList) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
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

/*
 * A `multiselect` param is a list of the chosen option keys: the node saves the array itself, the engine runs it as
 * an `array` param, and the container reads it as JSON. Re-opening the node shows the saved keys as selected tags.
 */
describe("Template task node --- a multiselect parameter", () => {
  const multiselectTask = {
    ...task,
    spec: {
      ...task.spec,
      params: [
        {
          name: "workspaceTools",
          label: "Workspace Tools",
          type: "multiselect",
          options: [
            { key: "read", value: "Read" },
            { key: "edit", value: "Edit" },
            { key: "write", value: "Write" },
            { key: "bash", value: "Bash" },
          ],
        },
      ],
    },
  } as unknown as Task;
  const multiselectNodeData = { ...nodeData, params: [] } as unknown as WorkflowNodeData;

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function chooseOption(label: string) {
    // The menu stays open after a choice, so it is opened only when it is closed.
    const combobox = screen.getByRole("combobox", { hidden: true, name: "Workspace Tools" });
    if (!within(combobox).queryByRole("listbox", { hidden: true })) {
      fireEvent.click(within(combobox).getByRole("button", { hidden: true, name: "open menu" }));
    }
    fireEvent.click(within(combobox).getByRole("option", { hidden: true, name: label }));
  }

  async function apply() {
    const button = await screen.findByRole("button", { hidden: true, name: "Apply" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
  }

  it("saves the keys of the options chosen as an array", async () => {
    await renderNode(undefined, { data: multiselectNodeData, latest: multiselectTask });

    chooseOption("Read");
    chooseOption("Edit");
    await apply();

    await waitFor(() => expect(savedNode()).not.toBe(multiselectNodeData));
    expect(savedNode().params).toEqual([{ name: "workspaceTools", value: ["read", "edit"] }]);
  });

  it("shows the saved keys as selected tags when the node is opened again", async () => {
    const data = {
      ...multiselectNodeData,
      params: [{ name: "workspaceTools", value: ["read", "bash"] }],
    } as unknown as WorkflowNodeData;
    await renderNode(undefined, { data, latest: multiselectTask });

    // With the menu closed, an option's label shows only as the tag of a selected option.
    const dialog = await screen.findByRole("dialog", { hidden: true });
    expect(within(dialog).getByText("Read")).toBeInTheDocument();
    expect(within(dialog).getByText("Bash")).toBeInTheDocument();
    expect(within(dialog).queryByText("Edit")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("Write")).not.toBeInTheDocument();

    // Applying without a change keeps the array as it was.
    await apply();
    await waitFor(() => expect(savedNode()).not.toBe(data));
    expect(savedNode().params).toEqual([{ name: "workspaceTools", value: ["read", "bash"] }]);
  });
});

/*
 * A node that names a task version is pinned to it; one set to "latest" follows the latest. The editor holds each
 * task at its latest version (v5 here), so a Latest node's form is that version's.
 */
describe("Template task node --- the task version", () => {
  const latestTask = { ...task, version: 5 } as Task;
  const latestNodeData = { ...nodeData, taskVersion: "latest" } as WorkflowNodeData;

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function openConfigure() {
    fireEvent.click(screen.getByRole("tab", { hidden: true, name: /Configure/ }));
  }

  function chooseVersion(value: string) {
    fireEvent.change(screen.getByLabelText("Task version"), { target: { value } });
  }

  async function apply() {
    const button = await screen.findByRole("button", { hidden: true, name: "Apply" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
  }

  it("tags a Latest node with the version it runs, on the canvas and in the edit modal's title", async () => {
    await renderNode(undefined, { data: latestNodeData, latest: latestTask });

    expect(screen.getByTestId("version-tag")).toHaveTextContent("Latest (v5)");
    const dialog = await screen.findByRole("dialog", { hidden: true });
    expect(within(dialog).getByRole("heading", { hidden: true, name: "Edit AI Latest (v5)" })).toBeInTheDocument();
  });

  it("tags a pinned node with its own version", async () => {
    await renderNode(undefined, { data: { ...nodeData, taskVersion: 4 }, latest: latestTask });

    expect(screen.getByTestId("version-tag")).toHaveTextContent(/^v4$/);
    const dialog = await screen.findByRole("dialog", { hidden: true });
    expect(within(dialog).getByRole("heading", { hidden: true, name: "Edit AI v4" })).toBeInTheDocument();
  });

  it("never offers a Latest node the upgrade, which a pinned node behind the latest still gets", async () => {
    const upgrade = "Workflow warning button";
    const { unmount } = await renderNode(undefined, {
      data: { ...latestNodeData, upgradesAvailable: true },
      latest: latestTask,
      open: false,
    });
    await screen.findByTestId("version-tag");
    expect(screen.queryByLabelText(upgrade)).not.toBeInTheDocument();
    unmount();

    await renderNode(undefined, {
      data: { ...nodeData, taskVersion: 4, upgradesAvailable: true },
      latest: latestTask,
      open: false,
    });
    await screen.findByTestId("version-tag");
    expect(screen.getByLabelText(upgrade)).toBeInTheDocument();
  });

  it('saves "latest" when a pinned node is switched to Latest', async () => {
    await renderNode(undefined, { data: { ...nodeData, taskVersion: 4, upgradesAvailable: true }, latest: latestTask });
    openConfigure();

    chooseVersion("latest");
    await apply();

    await waitFor(() => expect(savedNode()).not.toBe(nodeData));
    expect(savedNode().taskVersion).toBe("latest");
    expect(savedNode().upgradesAvailable).toBe(false);
  });

  it("pins the version chosen for a Latest node", async () => {
    await renderNode(undefined, { data: latestNodeData, latest: latestTask });
    openConfigure();

    chooseVersion("4");
    await apply();

    await waitFor(() => expect(savedNode().taskVersion).toBe(4));
    expect(savedNode().upgradesAvailable).toBe(true);
  });

  it("keeps the node's version when the form leaves the select alone", async () => {
    await renderNode(undefined, { data: { ...nodeData, taskVersion: 4 }, latest: latestTask });

    await apply();

    await waitFor(() => expect(savedNode()).not.toBe(nodeData));
    expect(savedNode().taskVersion).toBe(4);
  });

  it("links a pinned node behind the latest to the New version modal", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await renderNode(undefined, { data: { ...nodeData, taskVersion: 4 }, latest: latestTask });
    openConfigure();

    expect(screen.getByText("v5 is available.")).toBeInTheDocument();
    expect(screen.getByText("Review the changes before you move this node to it.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { hidden: true, name: "Review changes" }));

    expect(await screen.findByText("New version available")).toBeInTheDocument();
  });

  it("tells a Latest node which fields of the latest it doesn't set, and adds nothing that holds Apply", async () => {
    await renderNode(undefined, { data: latestNodeData, latest: latestTask });

    const apply = await screen.findByRole("button", { hidden: true, name: "Apply" });
    await waitFor(() => expect(apply).toBeEnabled());
    openConfigure();
    expect(screen.getByText(/^v5 has fields this node doesn't set: .*System Prompt/)).toBeInTheDocument();
    expect(apply).toBeEnabled();
  });
});
