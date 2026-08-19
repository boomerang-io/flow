import UserList from ".";
import UserDetailed, { loader as userDetailedLoader } from "Features/UserDetailed/UserDetailed";
import { Route } from "react-router-dom";
import { waitFor, screen, fireEvent } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { startApiServer } from "ApiServer";

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

// The list (AppPath.UserList) and the detail view (AppPath.User, loader-backed) are separate
// top-level routes in AppRoutes.tsx - rendering both here, the same way, lets "click a user ->
// see their detail page" exercise real navigation into a loader route instead of a mock.
function renderUsers(route: string) {
  return global.rtlContextRouterRender(
    <>
      <Route path={AppPath.UserList} element={<UserList />} />
      <Route path={`${AppPath.User}/*`} loader={userDetailedLoader} element={<UserDetailed />} />
    </>,
    { route },
  );
}

describe("Users --- Snapshot Test", () => {
  it("Capturing Snapshot of Users", async () => {
    const { baseElement } = renderUsers(appLink.userList());
    await screen.findByText("Tim Bula");
    expect(baseElement).toMatchSnapshot();
    await waitFor(() => null);
  });
});

describe("Users --- RTL", () => {
  test("Change user role", async () => {
    renderUsers(appLink.userList());
    await screen.findByText(/^View and manage users$/i);
    fireEvent.click(await screen.findByText(/^Tim Bula$/i));
    expect(await screen.findByText(/^These are Tim Bula's workspaces/i)).toBeInTheDocument();

    fireEvent.click(await screen.findByText(/^Change role$/i));
    expect(screen.getByText(/Admins can do more things/i)).toBeInTheDocument();
    expect(screen.getByText(/^Submit$/i)).toBeDisabled();
    fireEvent.click(await screen.findByText(/^User$/i));
    expect(screen.getByText(/^Submit$/i)).toBeEnabled();
    fireEvent.click(await screen.findByText(/^Submit$/i));
  });

  test("View user details", async () => {
    renderUsers(appLink.userList());
    await screen.findByText(/^View and manage users$/i);
    fireEvent.click(await screen.findByText(/^Tim Bula$/i));
    expect(await screen.findByText(/^These are Tim Bula's workspaces/i)).toBeInTheDocument();
  });
});
