import React from "react";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { EditorContextProvider } from "State/context";
import { appLink } from "Config/appConfig";
import { workflows as workflowsFixture } from "ApiServer/fixtures";
import { WorkflowStatus, type PaginatedWorkflowResponse, type WorkflowCanvas } from "Types";
import { editorLoader } from "../editorRoute";
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
function renderConfigure(route = appLink.editorConfigureGeneral({ workspace: WORKSPACE, workflow: WORKFLOW })) {
  const settingsRef = React.createRef<any>();
  return global.rtlContextRouterRender(
    // The splat must stop at the workflow segment: Configure renders its own <Routes> whose paths
    // are relative to this match and already start with "configure/", so consuming that segment
    // here would make them resolve to ".../configure/configure/general" and match nothing - which
    // renders the side nav with an empty content region rather than failing loudly.
    //
    // The loader is the editor route's own (app/routes/editor.tsx): Configure's GitHub
    // installation is no longer a useQuery of its own, it is read off this route's data through
    // useMatches() - so without the loader attached the Triggers tab renders the "Integration
    // Required" fallback instead.
    <Route
      path="/:workspace/editor/:workflow/*"
      loader={editorLoader}
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
    { route },
  );
}

/*
 * The loader blocks first paint on the whole batch of (mocked) requests the editor needs, so
 * until it resolves the router renders its HydrateFallback (nothing) - RTL's default 1000ms
 * findBy window is not enough on a machine running the full suite across workers, where the
 * previous useQuery version painted immediately. Same constants as Editor.spec.tsx.
 */
const LOADER_WAIT = { timeout: 15000 };
const TEST_TIMEOUT = 30000;

describe("Configure", () => {
  it(
    "renders the basic information section",
    async () => {
      renderConfigure();
      expect(await screen.findByText(/Basic Information/i, undefined, LOADER_WAIT)).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  it(
    "renders the icon picker and labels sections",
    async () => {
      renderConfigure();
      expect(await screen.findByText(/Pick an icon/i, undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByText(/^Labels$/)).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  /*
   * The editor header links to the bare `/configure` path (WorkflowEditor/Header/Header.tsx), so
   * this redirect is the only thing that gets a user onto a panel. React Router v5's
   * `<Redirect from to>` interpolated route params; v7's `<Navigate>` does not, so redirecting to
   * the AppPath PATTERN ("/:workspace/editor/:workflow/configure/general") navigated to that
   * literal URL - the loader then fetched a workflow called ":workflow" and the page errored.
   */
  it(
    "redirects the bare configure path onto the General panel with the real params",
    async () => {
      const { history } = renderConfigure(appLink.editorConfigure({ workspace: WORKSPACE, workflow: WORKFLOW }));

      expect(await screen.findByText(/Basic Information/i, undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(history.location.pathname).toBe(
        appLink.editorConfigureGeneral({ workspace: WORKSPACE, workflow: WORKFLOW }),
      );
    },
    TEST_TIMEOUT,
  );

  // Covers the read that moved out of this component and onto the editor route's loader: with an
  // installation resolved, the GitHub toggle is enabled and the "Integration Required" warning is
  // absent. Fixture: ApiServer/fixtures/installations.js.
  it(
    "enables the GitHub trigger from the installation the loader resolved",
    async () => {
      renderConfigure(appLink.editorConfigureTriggers({ workspace: WORKSPACE, workflow: WORKFLOW }));

      expect(await screen.findByText(/^GitHub$/, undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.queryByText(/Integration Required/i)).not.toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );
});
