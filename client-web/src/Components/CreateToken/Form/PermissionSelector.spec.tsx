import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { profile } from "ApiServer/fixtures";
import { TokenType } from "Constants";
import { workflowTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";
import { renderWithContext } from "Utils/testing/render";
import CreateServiceTokenButton from "../CreateToken";

// PermissionSelector has no props-based data path (see CreateToken/index.spec.tsx): the
// catalog it renders the grid from comes from the matched route's loader, so this has to render
// inside a real loader-carrying <Route>, same pattern as that spec.
const ROUTE_PATH = "/:workspace/editor/:workflow/*";
const ROUTE = "/test-workspace/editor/test-workflow/configure/tokens";
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

// buildGrid used to re-implement the "**" wildcard rules PermissionHelper.actionMatches already
// encodes (see the C3/C9 permissionHelper.spec.tsx cases). The "owner" role preset in the token
// catalog fixture (ApiServer/fixtures/tokenCatalog.js) is exactly the bare "**/**" case, so
// checking every cell for it is checked exercises that shared wildcard path end to end.
//
// A second case selecting the non-wildcard "reader" preset (only its exact resource/action pairs
// checked, everything else clear) was tried here too, but switching the role Dropdown's
// selection trips a pre-existing jsdom gap unrelated to this change - Carbon Dropdown's
// onHighlightedIndexChange calls `highlightedItem.scrollIntoView()`, which jsdom does not
// implement and setupTests.tsx does not polyfill (out of scope for C9 to add). The exact-match
// vs "**"-match distinction is covered directly in permissionHelper.spec.tsx's actionMatches
// cases instead.
describe("PermissionSelector --- wildcard grid", () => {
  it("checks every resource/action cell for the owner preset's '**/**' grant", async () => {
    renderCreateToken();
    userEvent.click(await screen.findByTestId(/create-token-button/i));

    // "Owner" is the first rolePresets key in the catalog fixture and is selected by default.
    expect(await screen.findByText("Owner")).toBeInTheDocument();
    userEvent.click(screen.getByText(/Customise permissions/i));

    expect(await screen.findByLabelText("Workflow read")).toBeChecked();
    expect(screen.getByLabelText("Workflow write")).toBeChecked();
    expect(screen.getByLabelText("User delete")).toBeChecked();
    expect(screen.getByLabelText("Webhook action")).toBeChecked();
  });
});
