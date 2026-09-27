import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import { EditorContextProvider, WorkflowProvider, WorkspaceContextProvider } from "State/context";
import { WorkflowEngineMode } from "Constants";
import type { FlowWorkspace, Task, WorkflowCanvas, WorkflowNodeData, WorkflowNodeProps } from "Types";
import ApprovalNode from "./ApprovalNode";

// The seeded Manual Approval template's params (service-loader seed/task-revisions.json).
const taskRef = "manual-approval";
const approvalTemplate = {
  name: taskRef,
  displayName: "Manual Approval",
  description: "Pauses workflow until approval is actioned.",
  icon: "Edit",
  spec: {
    params: [
      {
        name: "approverGroupId",
        label: "Approver Group (optional)",
        type: "select",
        options: [],
        required: false,
      },
      { name: "numberOfApprovals", label: "Number of Approvals (optional)", type: "number", required: false },
    ],
    results: [],
  },
} as unknown as Task;

const node = {
  id: "node-approve",
  type: "approval",
  data: { name: "approve", taskRef, taskVersion: 1, upgradesAvailable: false, params: [], results: [] },
  selected: false,
  dragging: false,
  zIndex: 0,
} as unknown as WorkflowNodeProps & { data: WorkflowNodeData };

function renderApprovalForm(approverGroups: Array<{ id: string; name: string }>) {
  const workspace = { id: "ws-1", name: "Workspace", approverGroups } as unknown as FlowWorkspace;
  render(
    <ReactFlowProvider>
      <WorkspaceContextProvider value={{ workspace }}>
        <EditorContextProvider
          value={{
            availableParameters: [],
            revisionState: {} as WorkflowCanvas,
            workflowsQueryData: {} as any,
          }}
        >
          <WorkflowProvider value={{ mode: WorkflowEngineMode.Edit, tasks: { [taskRef]: [approvalTemplate] } }}>
            <ApprovalNode {...node} />
          </WorkflowProvider>
        </EditorContextProvider>
      </WorkspaceContextProvider>
    </ReactFlowProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "edit" }));
}

// `hidden: true` because react-modal marks the whole <body> aria-hidden while the task's edit modal
// is open (see TaskRunLog.spec.tsx).
describe("Approval task form --- approver groups", () => {
  it("offers the workspace's approver groups in the Approver Group select", async () => {
    renderApprovalForm([
      { id: "group-1", name: "Release managers" },
      { id: "group-2", name: "Security reviewers" },
    ]);

    const select = await screen.findByRole("combobox", { hidden: true, name: /Approver Group/ });
    expect(select).toBeEnabled();
    fireEvent.click(select);
    expect(screen.getByRole("option", { hidden: true, name: "Release managers" })).toBeInTheDocument();
    expect(screen.getByRole("option", { hidden: true, name: "Security reviewers" })).toBeInTheDocument();
    // The groups are laid over a copy: the shared task template keeps its own params.
    expect(approvalTemplate.spec.params?.[0]).toMatchObject({ options: [] });
    expect(approvalTemplate.spec.params?.[0]).not.toHaveProperty("disabled");
  });

  it("disables the select with a note when the workspace has no approver groups", async () => {
    renderApprovalForm([]);

    expect(await screen.findByRole("combobox", { hidden: true, name: /Approver Group/ })).toBeDisabled();
    expect(screen.getByText("No approver groups configured for this workspace.")).toBeInTheDocument();
  });
});
