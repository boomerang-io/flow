import React from "react";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { EditorContextProvider } from "State/context";
import { appLink } from "Config/appConfig";
import { workflows as workflowsFixture } from "ApiServer/fixtures";
import { WorkflowStatus, type PaginatedWorkflowResponse, type WorkflowCanvas } from "Types";
import Configure from "./index";

const WORKSPACE = "personal";
const WORKFLOW = "5eb2c4085a92d80001a16d87";

// Hand-built rather than spread from the `workflows` fixture: that fixture is untyped .js and
// several of its fields don't satisfy the real Workflow type. The same technique the sibling
// specs use (see ScheduleCreator.spec.tsx, WorkflowCard.spec.tsx).
const workflow: WorkflowCanvas = {
  id: WORKFLOW,
  name: "configure-me",
  displayName: "Configure Me",
  description: "",
  creationDate: "2026-01-01T00:00:00.000Z",
  status: WorkflowStatus.Active,
  version: 1,
  icon: "workflow",
  params: [],
  tasks: [],
  changelog: { author: "", reason: "", date: "" },
  triggers: {
    event: { enabled: false, conditions: [] },
    github: { enabled: false, conditions: [] },
    manual: { enabled: true, conditions: [] },
    schedule: { enabled: false, conditions: [] },
    webhook: { enabled: false, conditions: [] },
  },
  upgradesAvailable: false,
  workspaces: [],
  edges: [],
  nodes: [],
};

// ConfigureContainer reads `workflowsQueryData` off the editor context (it derives the
// already-taken workflow names for the name-uniqueness check), so the provider is required -
// rendering the component bare is what made the previous version of this spec fail.
function renderConfigure() {
  const settingsRef = React.createRef<any>();
  return global.rtlContextRouterRender(
    // The splat must stop at the workflow segment: Configure renders its own <Routes> whose paths
    // are relative to this match and already start with "configure/", so consuming that segment
    // here would make them resolve to ".../configure/configure/general" and match nothing - which
    // renders the side nav with an empty content region rather than failing loudly.
    <Route
      path="/:workspace/editor/:workflow/*"
      element={
        <EditorContextProvider
          value={{
            availableParameters: [],
            revisionState: workflow,
            workflowsQueryData: workflowsFixture as unknown as PaginatedWorkflowResponse,
          }}
        >
          <Configure workflow={workflow} settingsRef={settingsRef} />
        </EditorContextProvider>
      }
    />,
    // The bare /configure path renders the side nav but no panel - the panels are nested routes,
    // so the general panel has to be addressed directly.
    { route: appLink.editorConfigureGeneral({ workspace: WORKSPACE, workflow: WORKFLOW }) },
  );
}

describe("Configure", () => {
  it("renders the basic information section", async () => {
    renderConfigure();
    expect(await screen.findByText(/Basic Information/i)).toBeInTheDocument();
  });

  it("renders the icon picker and labels sections", async () => {
    renderConfigure();
    expect(await screen.findByText(/Pick an icon/i)).toBeInTheDocument();
    expect(screen.getByText(/^Labels$/)).toBeInTheDocument();
  });
});
