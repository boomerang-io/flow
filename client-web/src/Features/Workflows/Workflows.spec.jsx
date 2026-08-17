import { screen } from "@testing-library/react";
import { vi } from "vitest";
import { Route } from "react-router-dom";
import WorkflowsHome from "./index";
import { startApiServer } from "ApiServer";
import { teams, profile } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { AppContextProvider } from "State/context";

const props = {
  teamsState: {
    isFetching: false,
    status: "success",
    error: "",
    data: [],
  },
  history: {},
  importWorkflow: {},
  importWorkflowActions: {},
  onBoard: {
    show: false,
  },
};

let server;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("WorkflowsHome --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHome", async () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          communityUrl: "www.ibm.com",
          setIsTutorialActive: () => {},
          user: profile,
          teams,
        }}
      >
        <Route path={AppPath.WorkflowsTeams}>
          <WorkflowsHome {...props} />
        </Route>
      </AppContextProvider>,
      { route: appLink.workflowsTeams() }
    );
    await screen.findByText("These are your");
    expect(baseElement).toMatchSnapshot();
  });
});
