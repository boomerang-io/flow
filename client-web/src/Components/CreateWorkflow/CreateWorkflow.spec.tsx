import React from "react";
import CreateWorkflow from ".";
import { screen, fireEvent } from "@testing-library/react";
import { workspaces, workflows, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import { WorkflowView } from "Constants";

const props = {
  workspace: workspaces.content[0],
  hasReachedWorkflowLimit: false,
  workflows: workflows.content,
  viewType: WorkflowView.Workflow,
};

describe("CreateWorkflow --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflow", () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        }}
      >
        <CreateWorkflow {...props} />{" "}
      </AppContextProvider>
    );
    fireEvent.click(screen.getByText(/Create a new workflow/i));
    expect(baseElement).toMatchSnapshot();
  });
});
