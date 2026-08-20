import React from "react";
import { workspaces, workflows, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import { WorkflowView } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import WorkflowCard from "./index";

const workspace = workspaces.content[0];
const workflow = workflows.content[0];

const props = {
  workspaceName: workspace.name,
  quotas: workspace.quotas,
  workflow,
  viewType: WorkflowView.Workflow,
  getWorkflowsUrl: serviceUrl.workspace.workflow.getWorkflows({ workspace: workspace.name }),
};

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        }}
      >
        <WorkflowCard {...props} />
      </AppContextProvider>
    );
    expect(baseElement).toMatchSnapshot();
  });
});
