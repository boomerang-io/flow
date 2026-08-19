import React from "react";
import { startApiServer } from "ApiServer";
import { workspaces, workflowTemplates, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import WorkflowCard from "./index";

const props = {
  template: workflowTemplates.content[0],
  workspaces: workspaces.content,
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
