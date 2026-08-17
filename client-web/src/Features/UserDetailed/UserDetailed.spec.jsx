import UserDetailed from ".";
import { Route } from "react-router-dom";
import { waitFor, screen } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { startApiServer } from "ApiServer";

let server;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("UserDetailed --- Snapshot Test", () => {
  it("Capturing Snapshot of UserDetailed", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <Route path={AppPath.User}>
        <UserDetailed />
      </Route>,
      { route: appLink.user({ userId: "5f170b3df6ab327e302cb0a5" }) }
    );
    await screen.findByText("These are Tim Bula's workflows");
    expect(baseElement).toMatchSnapshot();
    await waitFor(() => null);
  });
});
