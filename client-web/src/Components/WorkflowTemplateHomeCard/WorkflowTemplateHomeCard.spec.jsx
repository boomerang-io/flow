import React from "react";
import { startApiServer } from "ApiServer";
import { workspaces, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import WorkflowCard from "./index";

const props = {
  workspaceId: workspaces[0].id,
  quotas: workspaces[0].workflowQuotas,
  workflow: workspaces[0].workflows,
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
          workspaces,
        }}
      >
        <WorkflowCard {...props} />
      </AppContextProvider>
    );
    expect(baseElement).toMatchSnapshot();
  });
});
