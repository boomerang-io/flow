import { vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { WorkflowView } from "Constants";
import CreateWorkflowContent from ".";


const mockfn = vi.fn();
const props = {
  closeModal: () => {},
  createError: null,
  createWorkflow: mockfn,
  isLoading: false,
  existingWorkflowNames: [],
  workspaceQuotasEnabled: false,
  viewType: WorkflowView.Workflow,
};

describe("CreateWorkflowContent --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflowContent", () => {
    const { baseElement } = render(<CreateWorkflowContent {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("CreateWorkflowContent --- name check", () => {
  test("says why Create is disabled when the name is already taken", async () => {
    render(<CreateWorkflowContent {...props} existingWorkflowNames={["hello-world"]} />);

    // The name fills in from the display name; the Name field itself is never touched.
    await userEvent.type(screen.getByLabelText(/Display Name/), "Hello World");

    expect(screen.getByLabelText("Name")).toHaveValue("hello-world");
    expect(await screen.findByText(/there’s already a workflow called that in this workspace/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
  });

  test("enables Create for a name that is free", async () => {
    render(<CreateWorkflowContent {...props} existingWorkflowNames={["hello-world"]} />);

    await userEvent.type(screen.getByLabelText(/Display Name/), "Hello There");

    expect(await screen.findByRole("button", { name: "Create" })).toBeEnabled();
    expect(screen.queryByText(/already a workflow/)).not.toBeInTheDocument();
  });
});

// describe("CreateWorkflowContent --- RTL Tests", () => {
//   test("CreateWorkflowContent - test if the isActive Toggle appears", () => {
//     const newProps = { ...props, isEdit: false };
//     const { queryByText } = rtlContextRouterRender(<CreateWorkflowContent {...newProps} />);

//     expect(queryByText(/active/i)).not.toBeInTheDocument();
//   });

//   test("CreateWorkflowContent - test the Submit Button state", () => {
//     const newProps = { ...props, isEdit: false };

//     const { getByLabelText, getByText } = rtlContextRouterRender(<CreateWorkflowContent {...newProps} />);
//     const valueInputText = screen.getByLabelText(/value/i);
//     const labelInputText = screen.getByLabelText(/label/i);
//     const keyInputText = screen.getByLabelText(/key/i);
//     const saveButton = screen.getByText(/create/i);

//     expect(saveButton).toBeDisabled();
//     fireEvent.change(valueInputText, { target: { value: "Value Test" } });
//     fireEvent.change(labelInputText, { target: { value: "Label Test" } });
//     fireEvent.change(keyInputText, { target: { value: "Key Test" } });
//     expect(saveButton).toBeEnabled();
//   });

//   test("CreateWorkflowContent - test if the form submits", () => {
//     const { getByLabelText, getByText } = rtlContextRouterRender(<CreateWorkflowContent {...props} />);
//     const valueInputText = screen.getByLabelText(/value/i);
//     const labelInputText = screen.getByLabelText(/label/i);
//     const keyInputText = screen.getByLabelText(/key/i);
//     const saveButton = screen.getByText(/save/i);

//     expect(valueInputText).toBeInTheDocument();
//     expect(labelInputText).toBeInTheDocument();
//     expect(keyInputText).toBeInTheDocument();

//     fireEvent.change(valueInputText, { target: { value: "Value Test" } });
//     fireEvent.change(labelInputText, { target: { value: "Label Test" } });
//     fireEvent.change(keyInputText, { target: { value: "Key Test" } });
//     fireEvent.click(saveButton);

//     expect(valueInputText).not.toBeInTheDocument();
//     expect(labelInputText).not.toBeInTheDocument();
//     expect(keyInputText).not.toBeInTheDocument();
//   });

//   test("CreateWorkflowContent - test form reqired validations", async () => {
//     const { getByLabelText, findByText, queryByText } = rtlContextRouterRender(
//       <CreateWorkflowContent {...props} />
//     );
//     const valueInputText = screen.getByLabelText(/value/i);
//     const labelInputText = screen.getByLabelText(/label/i);
//     const keyInputText = screen.getByLabelText(/key/i);

//     expect(queryByText(/Enter a value/i)).toBeNull();
//     fireEvent.change(valueInputText, { target: { value: "" } });
//     fireEvent.blur(valueInputText);
//     const mandatoryValueErr = await screen.findByText(/Enter a value/i);
//     expect(mandatoryValueErr).toBeInTheDocument();

//     expect(queryByText(/Enter a label/i)).toBeNull();
//     fireEvent.change(labelInputText, { target: { value: "" } });
//     fireEvent.blur(labelInputText);
//     const mandatoryLabelErr = await screen.findByText(/Enter a label/i);
//     expect(mandatoryLabelErr).toBeInTheDocument();

//     expect(queryByText(/Enter a key/i)).toBeNull();
//     fireEvent.change(keyInputText, { target: { value: "" } });
//     fireEvent.blur(keyInputText);
//     const mandatoryKeyErr = await screen.findByText(/Enter a key/i);
//     expect(mandatoryKeyErr).toBeInTheDocument();
//   });
// });
