import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import UserList, { loader as userListLoader } from "Features/Users/Users";
import UserDetailed, { loader as userDetailedLoader } from "Features/UserDetailed/UserDetailed";
import { Route } from "react-router-dom";
import { waitFor, screen, fireEvent, within } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { renderWithContext } from "Utils/testing/render";

// setupTests.tsx freezes the wall clock, and lodash's debounce never fires with time frozen, so
// invoke immediately: the typed-search test then runs the real change -> navigate -> loader chain.
vi.mock("lodash/debounce", () => ({ default: (fn: (...args: unknown[]) => void) => fn }));

// The list (AppPath.UserList) and the detail view (AppPath.User, loader-backed) are separate
// top-level routes in AppRoutes.tsx - rendering both here, the same way, lets "click a user ->
// see their detail page" exercise real navigation into a loader route instead of a mock. Both
// routes carry their real `loader` (see GlobalParameters.spec.tsx for the route-module test
// pattern), so renderWithContext actually exercises them instead of leaving
// useLoaderData() undefined.
function renderUsers(route: string) {
  return renderWithContext(
    <>
      <Route path={AppPath.UserList} loader={userListLoader} element={<UserList />} />
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
    fireEvent.click(await screen.findByText(/^Tim Bula$/i));
    expect(await screen.findByText(/^These are Tim Bula's workspaces/i)).toBeInTheDocument();

    fireEvent.click(await screen.findByText(/^Change role$/i));
    expect(screen.getByText(/Admins can do more things/i)).toBeInTheDocument();
    expect(screen.getByText(/^Submit$/i)).toBeDisabled();
    fireEvent.click(await screen.findByText(/^User$/i));
    expect(screen.getByText(/^Submit$/i)).toBeEnabled();
    fireEvent.click(await screen.findByText(/^Submit$/i));
  });

  test("lists each user with their email under the name and their role in words", async () => {
    renderUsers(appLink.userList());
    const row = await screen.findByRole("row", { name: /Tim Bula/ });
    expect(within(row).getByText("Admin")).toBeInTheDocument();
    expect(within(row).getByText("trbula@example.com")).toBeInTheDocument();
  });

  test("View user details", async () => {
    renderUsers(appLink.userList());
    fireEvent.click(await screen.findByText(/^Tim Bula$/i));
    expect(await screen.findByText(/^These are Tim Bula's workspaces/i)).toBeInTheDocument();
  });
});

describe("Users --- search and page size", () => {
  test("a direct load of ?query= filters the list server-side and seeds the search box", async () => {
    renderUsers(`${appLink.userList()}?query=MARCUS`);
    expect(await screen.findByText("Marcus Roy")).toBeInTheDocument();
    expect(screen.queryByText("Tim Bula")).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search users")).toHaveValue("MARCUS");
  });

  test("typing in the search box filters the list server-side, by email too", async () => {
    renderUsers(appLink.userList());
    await screen.findByText("Tim Bula");
    fireEvent.change(screen.getByPlaceholderText("Search users"), { target: { value: "mdroy@" } });
    await waitFor(() => expect(screen.queryByText("Tim Bula")).not.toBeInTheDocument());
    expect(screen.getByText("Marcus Roy")).toBeInTheDocument();
  });

  test("the page size the pagination writes is the one the loader sends", async () => {
    const limits: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.getUsers({ query: "" }), ({ request }) => {
        const limit = new URL(request.url).searchParams.get("limit");
        limits.push(limit);
        const user = { id: "u1", name: "Ada Lovelace", email: "ada@example.com", type: "user", status: "active" };
        return HttpResponse.json({ content: [user], number: 0, size: Number(limit), totalElements: 1 });
      }),
    );
    const { history } = renderUsers(appLink.userList());
    await screen.findByText("Ada Lovelace");
    fireEvent.change(screen.getByLabelText(/items per page/i), { target: { value: "20" } });
    await waitFor(() => expect(limits).toContain("20"));
    expect(history.location.search).toContain("limit=20");
    expect(history.location.search).not.toContain("size=");
  });
});
