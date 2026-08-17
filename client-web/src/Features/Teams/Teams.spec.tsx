import React from "react";
import Teams from ".";
import { Route } from "react-router-dom";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { startApiServer } from "ApiServer";

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("Teams --- Snapshot Test", () => {
  it("Capturing Snapshot of Teams", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <Route path={AppPath.TeamList}>
        <Teams />
      </Route>,
      { route: appLink.teamList() }
    );
    await screen.findByText("WDC2 ISE QA");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Teams --- RTL", () => {
  test("Create new team", async () => {
    global.rtlContextRouterRender(
      <Route path={AppPath.TeamList}>
        <Teams />
      </Route>,
      { route: appLink.teamList() }
    );
    const createTeamButton = await screen.findByText(/^Create Team$/i);
    fireEvent.click(createTeamButton);
    expect(screen.getByText(/^Scope your workflows and parameters to a team$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Save$/i)).toBeDisabled();
    const teamNameInput = screen.getByLabelText(/^Name$/i);
    userEvent.type(teamNameInput, "Test team");
    expect(screen.getByText(/^Save$/i)).toBeEnabled();
    fireEvent.click(screen.getByText(/^Save$/i));
    expect(await screen.findByText(/Test team/i)).toBeInTheDocument();
  });
});
