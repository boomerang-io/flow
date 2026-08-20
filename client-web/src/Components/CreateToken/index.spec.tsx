import React from "react";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { profile } from "ApiServer/fixtures";
import { TokenType } from "Constants";
import { workflowTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";
import CreateServiceTokenButton from "./CreateToken";

// The `getTokensUrl` prop is gone: the form posts the shared "create" intent to the matched
// route's action and the list refreshes by revalidating that route's loader, so this spec has to
// render inside a real loader/action-carrying <Route> (the same shape the router config uses)
// rather than rtlContextRouterRender's catch-all. The loader is what supplies PermissionSelector
// with its server-driven catalog, which a `key`-type token renders.
const ROUTE_PATH = "/:workspace/editor/:workflow/*";
const ROUTE = "/test-workspace/editor/test-workflow/configure/tokens";

// The app populates AppContext.workspaces from the flat user.teams array
// (see Features/App/App.tsx) -- not the paginated workspaces query response.
const contextValue = { workspaces: profile.teams };

function renderCreateToken() {
  return global.rtlContextRouterRender(
    <Route
      path={ROUTE_PATH}
      loader={workflowTokensLoader}
      action={tokenAction}
      element={<CreateServiceTokenButton type={TokenType.Key} />}
    />,
    { contextValue, route: ROUTE },
  );
}

describe("CreateServiceTokenButton --- Snapshot", () => {
  it("Capturing Snapshot of CreateServiceTokenButton", async () => {
    const { baseElement } = renderCreateToken();
    await screen.findByText(/Create Token/i);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("CreateServiceTokenButton --- RTL", () => {
  it("Open token creation modal", async () => {
    renderCreateToken();
    const button = await screen.findByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create new token/i)).not.toBeInTheDocument();
    userEvent.click(button);
    expect(screen.getByText(/Create new token/i)).toBeInTheDocument();
  });

  it("Renders the server-driven permission grid for a non-user token", async () => {
    renderCreateToken();
    userEvent.click(await screen.findByTestId(/create-token-button/i));

    // Role presets and the resource x action grid both come from GET /token/catalog, which the
    // route loader fetched - so they are on screen without a loading pass. "Owner" is the first
    // rolePresets key in the catalog fixture, selected by default; asserting on it (rather than
    // just the grid disclosure) is what proves real catalog data arrived rather than the
    // "Unable to load the permission catalog" fallback.
    expect(await screen.findByText("Owner")).toBeInTheDocument();
    expect(screen.getByText(/Customise permissions/i)).toBeInTheDocument();
  });

  it("Fill out form", async () => {
    renderCreateToken();
    const button = await screen.findByTestId(/create-token-button/i);
    expect(screen.queryByText(/Create new token/i)).not.toBeInTheDocument();
    userEvent.click(button);

    expect(screen.getByText(/Create new token/i)).toBeInTheDocument();

    const nameInput = screen.getByLabelText(/^Name$/i);
    userEvent.type(nameInput, "my-test-token");

    const descriptionInput = screen.getByTestId("token-description");
    userEvent.type(descriptionInput, "Token test description");

    const createButton = screen.getByTestId(/create-token-submit/i);

    expect(createButton).toBeEnabled();
    userEvent.click(createButton);
    expect(await screen.findByText(/Token successfully created/i)).toBeInTheDocument();
  });
});
