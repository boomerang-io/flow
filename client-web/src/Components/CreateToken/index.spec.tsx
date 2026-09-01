import React from "react";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route } from "react-router-dom";
import { profile } from "ApiServer/fixtures";
import { TokenType } from "Constants";
import { workflowTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";
import { renderWithContext } from "Utils/testing/render";
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
  return renderWithContext(
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
    expect(await screen.findByText(/Create new token/i)).toBeInTheDocument();
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

    expect(await screen.findByText(/Create new token/i)).toBeInTheDocument();

    // ModalFlow mounts the modal's header and its step body in separate commits: a run that
    // caught the tree in between found the <h2>Create new token</h2> and the close button on
    // screen with the whole <ModalBody> - and therefore this field - still absent, so a
    // synchronous getByLabelText here failed with "Unable to find a label with the text of:
    // /^Name$/i". The body is asynchronous, so the query has to be too.
    const nameInput = await screen.findByLabelText(/^Name$/i);
    userEvent.type(nameInput, "my-test-token");

    const descriptionInput = screen.getByTestId("token-description");
    userEvent.type(descriptionInput, "Token test description");

    const createButton = screen.getByTestId(/create-token-submit/i);

    // The submit button is `disabled={!isValid || isCreating}` (Form/index.tsx) and Formik's
    // validation - Yup, `validateOnMount` - is asynchronous, so `isValid` reflects the name typed
    // above only once that validation resolves. The synchronous assertion this replaces was
    // passing for the wrong reason: Formik's `errors` starts out `{}`, which makes `isValid` true
    // and the button briefly enabled BEFORE "Name is required" ever lands, so the check could
    // succeed against pre-validation state. Making the queries above properly async moved the
    // click past that window and turned this line red every time - which is how the wrong reason
    // surfaced. Waiting asserts the same thing against settled validation.
    await waitFor(() => expect(createButton).toBeEnabled());
    userEvent.click(createButton);
    expect(await screen.findByText(/Token successfully created/i)).toBeInTheDocument();
  });
});
