import WorkspaceDetailed from ".";
import { Route } from "react-router-dom";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { startApiServer } from "ApiServer";

let server;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("WorkspaceDetailed --- Snapshot Test", () => {
  it("Capturing Snapshot of WorkspaceDetailed", async () => {
    const { baseElement } = rtlContextRouterRender(
      <Route path={AppPath.ManageWorkspace}>
        <WorkspaceDetailed />
      </Route>,
      { route: appLink.manageWorkspace({ workspaceId: "5e7cccb94bbc6e0001c51773" }) }
    );
    await screen.findByText("These are the people who have access to workflows for this Workspace.");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkspaceDetailed --- RTL", () => {
  test("Visit Workspace Details tabs", async () => {
    rtlContextRouterRender(
      <Route path={AppPath.ManageWorkspace}>
        <WorkspaceDetailed />
      </Route>,
      { route: appLink.manageWorkspace({ workspaceId: "5e7cccb94bbc6e0001c51773" }) }
    );
    //Members tab
    await screen.findByText("These are the people who have access to workflows for this Workspace.");
    const addMemberButton = await screen.findByText(/^Add Members$/i);
    fireEvent.click(addMemberButton);
    expect(screen.getByText(/^Search for people to add to this workspace$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Add to workspace$/i)).toBeDisabled();

    expect(screen.getByPlaceholderText(/^Search for a user$/i)).toBeInTheDocument();
    // fetch users is triggering a promise error, need to look for a solution
    // const nameInput = screen.getByPlaceholderText(/^Search for a user$/i);
    // userEvent.type(nameInput, "e");
    // fireEvent.click(await screen.findByText(/^Test User$/i));
    // expect(await screen.findByText(/^Add to workspace$/i)).toBeEnabled();
    fireEvent.click(screen.getByText(/^Cancel$/i));

    //Workflows
    fireEvent.click(screen.getByText("Workflows"));
    expect(screen.getByText("These are the workflows for this Workspace.")).toBeInTheDocument();

    //Settings
    fireEvent.click(screen.getByText("Settings"));
    expect(await screen.findByText("Workspace Name")).toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("open-change-name-modal"));
    expect(screen.getByText("Change workspace name")).toBeInTheDocument();
    userEvent.type(screen.getByLabelText("Name"), " test name");
    fireEvent.click(screen.getByText("Save"));
    expect(await (await screen.findAllByText("WDC2 ISE Dev test name")).length).toBe(3);
  });
});
