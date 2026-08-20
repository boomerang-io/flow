import React from "react";
import { Route } from "react-router-dom";
import { screen, fireEvent } from "@testing-library/react";
import TaskApprovalModal from "./TaskApprovalModal";

// Replaces TaskApprovalModal.spec.jsx, which passed props the component never accepted
// (`approvalId`/`executionId` rather than `actionId`) and asserted on a "Submit decisions"
// button that does not exist - the button reads "Submit". Being .jsx, tsc never saw either
// mistake. The component now submits through useFetcher, so it needs a data router.

function renderModal() {
  return global.rtlRouterRender(
    <Route path="*" action={async () => ({ ok: true, intent: "action" })} element={<TaskApprovalModal actionId="1" closeModal={() => {}} />} />,
  );
}

describe("TaskApprovalModal --- Snapshot", () => {
  it("Capturing Snapshot of TaskApprovalModal", () => {
    const { baseElement } = renderModal();
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
});
