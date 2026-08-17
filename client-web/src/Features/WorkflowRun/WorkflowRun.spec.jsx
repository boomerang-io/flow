import React from "react";
import WorkflowExecutionContainer from "./index";
import { startApiServer } from "ApiServer";
import { db } from "ApiServer/fixtures";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";

import { AppPath, appLink } from "Config/appConfig";
import { teams, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";

const workflowId = "5eb2c4085a92d80001a16d87";
const executionId = "5ec51eca5a92d80001a2005d";

let server;

beforeEach(() => {
  server = startApiServer();
  server.db.loadData(db);
});

afterEach(() => {
  server.shutdown();
});

describe("Execution --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowExecutionContainer", async () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          teams,
        }}
      >
        <Route path={AppPath.Execution} component={WorkflowExecutionContainer} />
      </AppContextProvider>,
      { route: appLink.execution({ workflowId, executionId }) }
    );
    await screen.findByText(/Workflow run detail/i);

    expect(baseElement).toMatchSnapshot();
  });
});
