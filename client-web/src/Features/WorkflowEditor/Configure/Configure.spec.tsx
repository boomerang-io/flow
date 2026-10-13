import React from "react";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router-dom";
import { EditorContextProvider } from "State/context";
import { appLink } from "Config/appConfig";
import { workflows as workflowsFixture } from "ApiServer/fixtures";
import { db } from "ApiServer/msw/db";
import { WorkflowStatus, type PaginatedWorkflowResponse, type WorkflowCanvas } from "Types";
import { editorAction, editorLoader } from "../editorRoute";
import { formatConfigureValues } from "../Editor";
import { renderWithContext } from "Utils/testing/render";
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
  edges: [],
  nodes: [],
};

// ConfigureContainer reads `workflowsQueryData` off the editor context (it derives the
// already-taken workflow names for the name-uniqueness check), so the provider is required -
// rendering the component bare is what made the previous version of this spec fail.
function renderConfigure(
  route = appLink.editorConfigureGeneral({ workspace: WORKSPACE, workflow: WORKFLOW }),
  workflowOverrides: Partial<WorkflowCanvas> = {},
) {
  const settingsRef = React.createRef<any>();
  const workflowUnderTest = { ...workflow, ...workflowOverrides };
  const view = renderWithContext(
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
      action={editorAction}
      element={
        <EditorContextProvider
          value={{
            availableParameters: [],
            revisionState: workflowUnderTest,
            workflowsQueryData: workflowsFixture as unknown as PaginatedWorkflowResponse,
          }}
        >
          <Configure workflow={workflowUnderTest} settingsRef={settingsRef} />
        </EditorContextProvider>
      }
    />,
    // The bare /configure path renders the side nav but no panel - the panels are nested routes,
    // so the general panel has to be addressed directly.
    { route },
  );
  return { ...view, settingsRef };
}

const WORKSPACE_ROUTE = appLink.editorConfigureWorkspace({ workspace: WORKSPACE, workflow: WORKFLOW });
const ARTIFACTS_ROUTE = appLink.editorConfigureArtifacts({ workspace: WORKSPACE, workflow: WORKFLOW });

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

/*
 * Flow 5 keeps one shared filesystem per run, declared as a single `workspace` object on the
 * workflow (absent = none). The form keeps an `enabled` toggle of its own; Editor.tsx's
 * formatConfigureValues is what turns the form state into the API shape on save, so each case
 * below runs the form values through it - that is the value the PUT would carry.
 */
describe("Configure > Workspace", () => {
  it(
    "starts off for a workflow with no workspace and emits `workspace: null` so the server clears it",
    async () => {
      const { settingsRef } = renderConfigure(WORKSPACE_ROUTE);

      expect(await screen.findByRole("heading", { name: "Workspace" }, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByRole("switch", { name: "Shared workspace for each run" })).not.toBeChecked();
      expect(screen.queryByLabelText("Mount path")).not.toBeInTheDocument();
      expect(screen.queryByText(/Workflow storage was removed/)).not.toBeInTheDocument();

      expect(formatConfigureValues(settingsRef.current.values)).toMatchObject({ workspace: null });
      expect(formatConfigureValues(settingsRef.current.values)).not.toHaveProperty("workspaces");
    },
    TEST_TIMEOUT,
  );

  it(
    "toggling it on reveals Size and Mount path inline and emits the `workspace` object",
    async () => {
      const { settingsRef } = renderConfigure(WORKSPACE_ROUTE);
      await screen.findByRole("heading", { name: "Workspace" }, LOADER_WAIT);

      fireEvent.click(screen.getByRole("switch", { name: "Shared workspace for each run" }));

      expect(await screen.findByLabelText("Mount path")).toBeInTheDocument();
      expect(screen.getByText(/Size in gigabytes/)).toBeInTheDocument();
      expect(screen.getByText("Where every task sees the workspace")).toBeInTheDocument();
      // Size defaults to 1 GB; an empty mount path is left to the server's default.
      await waitFor(() => expect(formatConfigureValues(settingsRef.current.values).workspace).toEqual({ size: 1 }));

      fireEvent.change(screen.getByLabelText("Mount path"), { target: { value: "/data" } });
      await waitFor(() =>
        expect(formatConfigureValues(settingsRef.current.values).workspace).toEqual({ size: 1, mountPath: "/data" }),
      );

      fireEvent.click(screen.getByRole("switch", { name: "Shared workspace for each run" }));
      await waitFor(() => expect(formatConfigureValues(settingsRef.current.values).workspace).toBeNull());
      expect(screen.queryByLabelText("Mount path")).not.toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  it(
    "starts on, with the declared size and mount path, for a workflow that has a workspace",
    async () => {
      const { settingsRef } = renderConfigure(WORKSPACE_ROUTE, { workspace: { size: 4, mountPath: "/workspace" } });
      await screen.findByRole("heading", { name: "Workspace" }, LOADER_WAIT);

      expect(screen.getByRole("switch", { name: "Shared workspace for each run" })).toBeChecked();
      expect(screen.getByLabelText("Mount path")).toHaveValue("/workspace");
      expect(formatConfigureValues(settingsRef.current.values).workspace).toEqual({ size: 4, mountPath: "/workspace" });
    },
    TEST_TIMEOUT,
  );

  it(
    "warns a workflow upgraded from v3 with Workflow storage that it must be saved again",
    async () => {
      renderConfigure(WORKSPACE_ROUTE, { legacyWorkflowStorage: true });

      expect(await screen.findByText("Workflow storage was removed in Flow 5", undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByText(/This workflow can't run until it is saved again/)).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  it(
    "the side nav links to Workspace and Artifacts, not the old Workspaces panel",
    async () => {
      renderConfigure(WORKSPACE_ROUTE);
      await screen.findByRole("heading", { name: "Workspace" }, LOADER_WAIT);

      expect(screen.getByRole("link", { name: "Workspace" })).toHaveAttribute("href", WORKSPACE_ROUTE);
      expect(screen.getByRole("link", { name: "Artifacts" })).toHaveAttribute("href", ARTIFACTS_ROUTE);
      expect(screen.queryByRole("link", { name: "Workspaces" })).not.toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );
});

/*
 * The editor loader reads this workflow's kept artifacts (scope `workflow`, available) only when
 * the Artifacts panel is addressed, filtered by the resolved compose's name - the MSW compose
 * handler serves workflowCompose.js's first entry ("Test Google Sheets as JWT"), which is the
 * `workflowRef` artifacts.js's kept artifact (artifact-4) carries.
 */
describe("Configure > Artifacts", () => {
  it(
    "lists the artifacts this workflow keeps between runs and nothing else",
    async () => {
      renderConfigure(ARTIFACTS_ROUTE);

      expect(await screen.findByText("dependency-cache.tar", undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByText("100.0 MB")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "View run" })).toHaveAttribute(
        "href",
        appLink.execution({ workspace: WORKSPACE, runId: "5f8e19ee8f268161b4beb244" }),
      );
      // Run-scoped artifacts of other workflows never show here.
      expect(screen.queryByText("build-output.tar.gz")).not.toBeInTheDocument();
      expect(screen.queryByText("release-notes.md")).not.toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  it(
    "deletes a kept artifact through the editor route's action and shows the empty state",
    async () => {
      renderConfigure(ARTIFACTS_ROUTE);
      await screen.findByText("dependency-cache.tar", undefined, LOADER_WAIT);

      await userEvent.click(screen.getByTestId("delete-kept-artifact-artifact-4"));
      expect(await screen.findByText("Are you sure?")).toBeInTheDocument();
      // Carbon's danger buttons prepend a hidden "danger" description span, and react-modal puts
      // aria-hidden on the app while the modal is open - same quirk SchedulePanelList.spec.tsx notes.
      // Scoped to the dialog: the row's own trigger is a danger button named "Delete" too.
      const dialog = screen.getByRole("dialog", { hidden: true });
      await userEvent.click(within(dialog).getByRole("button", { name: /danger Delete/, hidden: true }));

      expect(await screen.findByText("No kept artifacts yet.", undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(db.artifacts.some((artifact) => artifact.id === "artifact-4")).toBe(false);
    },
    TEST_TIMEOUT,
  );

  it(
    "shows the empty state when the workflow keeps nothing",
    async () => {
      db.artifacts = db.artifacts.filter((artifact) => artifact.scope !== "workflow");
      renderConfigure(ARTIFACTS_ROUTE);

      expect(await screen.findByText("No kept artifacts yet.", undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByText(/Add an Upload Artifact task with scope Workflow/)).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );
});
