import { vi } from "vitest";
import { screen } from "@testing-library/react";
import Configure from "./index";
import { workspaces, profile, summaries } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import { startApiServer } from "ApiServer";
import { appLink } from "Config/appConfig";
import { FlagsProvider } from "flagged";

const mockfn = vi.fn();

const props = {
  history: {},
  params: { workflowId: "5eb2c4085a92d80001a16d87" },
  summaryData: summaries[0],
  workspaces,
  updateSummary: mockfn,
};
let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("Inputs --- RTL", () => {
  it("Main Sections Render", async () => {
    rtlContextRouterRender(
      <FlagsProvider
        features={{
          WorkspaceManagementEnabled: true,
          WorkspaceQuotasEnabled: true,
          SettingsEnabled: true,
          UserManagementEnabled: true,
          GlobalParametersEnabled: true,
          WorkflowTokensEnabled: true,
          TaskManagerEnabled: true,
          EditVerifiedTasksEnabled: true,
          WorkflowTriggersEnabled: true,
          WorkspaceParametersEnabled: true,

          ActivityEnabled: true,
          InsightsEnabled: true,
        }}
      >
        <AppContextProvider
          value={{
            isTutorialActive: false,
            setIsTutorialActive: () => {},
            user: profile,
            workspaces,
          }}
        >
          <Configure {...props} />
        </AppContextProvider>
      </FlagsProvider>,
      { route: appLink.editorConfigure({ workflowId: "5eb2c4085a92d80001a16d87" }) }
    );
    await screen.findByText(/General info/i);
    expect(screen.getByText(/General info/i)).toBeInTheDocument();
    expect(
      screen.getByText(
        /Workspaces allow your workflow to declare storage options to be used at execution time. This will be limited by the Storage Capacity quota which will error executions if you exceed the allowed maximum./
      )
    ).toBeInTheDocument();
  });
});
