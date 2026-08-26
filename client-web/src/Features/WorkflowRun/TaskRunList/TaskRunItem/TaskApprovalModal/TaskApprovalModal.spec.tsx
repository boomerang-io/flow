import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { AppPath, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/WorkflowRun/WorkflowRun";
import TaskApprovalModal from "./TaskApprovalModal";

// Replaces TaskApprovalModal.spec.jsx, which passed props the component never accepted
// (`approvalId`/`executionId` rather than `actionId`) and asserted on a "Submit decisions"
// button that does not exist - the button reads "Submit". Being .jsx, tsc never saw either
// mistake. The component now submits through useFetcher, so it needs a data router.
//
// The route below carries the REAL `action` exported by Features/WorkflowRun/WorkflowRun.tsx
// (which is what app/routes/run.tsx wires up in production), not a stub - a stub that always
// resolves `{ ok: true }` proves the modal's success path against a fake, and would keep
// passing if the action stopped issuing the request altogether.

const workspace = "tyson-workspace";
const runId = "5ec51eca5a92d80001a2005d";
const actionId = "action-abc";

function renderModal(closeModal: () => void = () => {}) {
  global.rtlRouterRender(
    <Route
      path={AppPath.Run}
      action={action}
      element={<TaskApprovalModal actionId={actionId} closeModal={closeModal} />}
    />,
    { route: appLink.execution({ workspace, runId }) },
  );
}

/** Captures the decisions PUT the run route's action makes. */
function captureDecisions() {
  const captured: Array<{ body: any }> = [];
  server.use(
    http.put(serviceUrl.workspace.action.putAction({ workspace: ":workspace" }), async ({ request }) => {
      captured.push({ body: await request.json() });
      return HttpResponse.json({});
    }),
  );
  return captured;
}

describe("TaskApprovalModal --- Snapshot", () => {
  it("Capturing Snapshot of TaskApprovalModal", () => {
    const { baseElement } = global.rtlRouterRender(
      <Route
        path={AppPath.Run}
        action={action}
        element={<TaskApprovalModal actionId={actionId} closeModal={() => {}} />}
      />,
      { route: appLink.execution({ workspace, runId }) },
    );
    expect(baseElement).toMatchSnapshot();
  });
});

describe("TaskApprovalModal --- RTL", () => {
  it("enables submission only once a decision is chosen", () => {
    renderModal();

    const submissionButton = screen.getByText("Submit");
    expect(submissionButton).toBeDisabled();

    fireEvent.click(screen.getByText("Approve"));
    expect(submissionButton).toBeEnabled();
  });

  it("submits an approval through the real route action and closes on success", async () => {
    const captured = captureDecisions();
    const closeModal = vi.fn();
    renderModal(closeModal);

    fireEvent.click(screen.getByText("Approve"));
    fireEvent.click(screen.getByText("Submit"));

    await waitFor(() => expect(closeModal).toHaveBeenCalled());
    expect(captured).toHaveLength(1);
    expect(captured[0].body).toEqual([{ id: actionId, approved: true, comments: "" }]);
  });

  it("submits a rejection with its comment", async () => {
    const captured = captureDecisions();
    const closeModal = vi.fn();
    renderModal(closeModal);

    fireEvent.change(screen.getByLabelText(/Comments/), { target: { value: "Not this time" } });
    fireEvent.click(screen.getByText("Reject"));
    fireEvent.click(screen.getByText("Submit"));

    await waitFor(() => expect(closeModal).toHaveBeenCalled());
    expect(captured[0].body).toEqual([{ id: actionId, approved: false, comments: "Not this time" }]);
  });

  it("stays open and shows an error when the submission fails", async () => {
    server.use(
      http.put(serviceUrl.workspace.action.putAction({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );
    const closeModal = vi.fn();
    renderModal(closeModal);

    fireEvent.click(screen.getByText("Approve"));
    fireEvent.click(screen.getByText("Submit"));

    expect(await screen.findByText("Manual Approval Failed")).toBeInTheDocument();
    expect(closeModal).not.toHaveBeenCalled();
  });
});
