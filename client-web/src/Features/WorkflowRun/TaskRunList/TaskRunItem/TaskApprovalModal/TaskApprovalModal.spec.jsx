import React from "react";
import TaskApprovalModal from "./index";
import { screen, fireEvent } from "@testing-library/react";

const props = {
  approvalId: "1",
  executionId: "1",
  closeModal: () => {},
};

describe("TaskApprovalModal --- Snapshot", () => {
  it("Capturing Snapshot of TaskApprovalModal", () => {
    const { baseElement } = global.rtlQueryRender(<TaskApprovalModal {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("TaskApprovalModal --- RTL", () => {
  it("Formik form submission state", async () => {
    global.rtlQueryRender(<TaskApprovalModal {...props} />);

    const submissionButton = screen.getByText("Submit decisions");
    expect(submissionButton).toBeDisabled();
    const approvalButton = screen.getByText("Approve");
    fireEvent.click(approvalButton);
    expect(submissionButton).toBeEnabled();
  });
});
