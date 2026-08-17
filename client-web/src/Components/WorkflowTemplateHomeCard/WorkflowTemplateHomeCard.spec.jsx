import React from "react";
import { startApiServer } from "ApiServer";
import { teams, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import WorkflowCard from "./index";

const props = {
  teamId: teams[0].id,
  quotas: teams[0].workflowQuotas,
  workflow: teams[0].workflows,
};

let server;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          teams,
        }}
      >
        <WorkflowCard {...props} />
      </AppContextProvider>
    );
    expect(baseElement).toMatchSnapshot();
  });
});
