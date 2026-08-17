import React from "react";
import CreateWorkflow from ".";
import { screen, fireEvent } from "@testing-library/react";
import { workspaces, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";

const props = {
  workspace: workspaces[0],
  workspaces: workspaces,
};

describe("CreateWorkflow --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflow", () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        }}
      >
        <CreateWorkflow {...props} />{" "}
      </AppContextProvider>
    );
    fireEvent.click(screen.getByText(/Create a new workflow/i));
    expect(baseElement).toMatchSnapshot();
  });
});
