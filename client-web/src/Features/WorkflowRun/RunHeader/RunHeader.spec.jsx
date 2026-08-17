import React from "react";
import ExecutionHeader from "./index";
import { teams, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
const props = {
  workflowExecution: {
    version: 1,
    status: "success",
    data: {
      teamName: "CAI Offerings",
      initiatedByUserName: "Tim Bula",
      trigger: "manual",
      creationDate: "2019-09-03T15:00:00.049+0000",
    },
  },
  workflow: {
    isFetching: false,
    data: {
      name: "Sparkle Flow with extra glitter and donuts on the side",
    },
  },
};

describe("ExecutionHeader --- Snapshot", () => {
  it("Capturing Snapshot of ExecutionHeader", () => {
    const { baseElement } = global.rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          teams,
        }}
      >
        <ExecutionHeader {...props} />
      </AppContextProvider>
    );
    expect(baseElement).toMatchSnapshot();
  });
});
