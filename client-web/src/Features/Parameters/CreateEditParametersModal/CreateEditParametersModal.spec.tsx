import React from "react";
import userEvent from "@testing-library/user-event";
import { render, screen, waitFor } from "@testing-library/react";
import { REDACTED } from "Constants";
import CreateEditParametersModal from "./CreateEditParametersModal";

// A secured parameter reads as the marker when set. Editing another field sends the marker back,
// which keeps the stored value; Clear sends a blank, which clears it.
const secured = { id: "p1", name: "githubToken", label: "GitHub token", type: "password", value: REDACTED };

function renderEdit(handleSubmit: (isEdit: boolean, values: any, closeModal: () => void) => Promise<void>) {
  return render(
    <CreateEditParametersModal
      isOpen
      isEdit
      error={false}
      handleClose={() => {}}
      handleSubmit={handleSubmit}
      parameter={secured as any}
      parameters={[secured as any]}
      scope="global"
    />,
  );
}

describe("CreateEditParametersModal --- a secured parameter", () => {
  test("an edit that leaves the value alone sends the marker back", async () => {
    const handleSubmit = vi.fn(async (_isEdit: boolean, _values: any, _closeModal: () => void) => {});
    renderEdit(handleSubmit);

    await userEvent.type(screen.getByPlaceholderText("What it's for"), "rotated monthly");
    await userEvent.click(screen.getByTestId("workspace-parameter-create-edit-submission-button"));

    await waitFor(() => expect(handleSubmit).toHaveBeenCalled());
    expect(handleSubmit.mock.calls[0][1]).toMatchObject({ value: REDACTED, description: "rotated monthly" });
  });

  test("Clear sends a blank", async () => {
    const handleSubmit = vi.fn(async (_isEdit: boolean, _values: any, _closeModal: () => void) => {});
    renderEdit(handleSubmit);

    await userEvent.click(screen.getByTestId("parameter-secret-value-clear"));
    await userEvent.click(screen.getByTestId("workspace-parameter-create-edit-submission-button"));

    await waitFor(() => expect(handleSubmit).toHaveBeenCalled());
    expect(handleSubmit.mock.calls[0][1]).toMatchObject({ value: "" });
  });
});
