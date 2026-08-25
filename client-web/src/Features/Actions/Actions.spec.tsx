import { Route } from "react-router-dom";
import { waitFor } from "@testing-library/react";
import { WorkspaceContainer } from "Features/App/App";
import { appLink } from "Config/appConfig";
import Actions, { loader } from "./Actions";

const WORKSPACE = "tyson-workspace"; // matches ApiServer/fixtures/workspaces.js content[0].

// Route-module test pattern - see Activity.spec.tsx. The real route ("/:workspace/actions/*" in
// app/routes.ts) is a splat: Actions.tsx re-matches the remainder with its own descendant
// <Routes>, so the test route has to use the same "/*" pattern.
function renderActions(route: string = appLink.actions({ workspace: WORKSPACE })) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/actions/*"
      loader={loader}
      element={
        <WorkspaceContainer>
          <Actions />
        </WorkspaceContainer>
      }
    />,
    { route },
  );
}

describe("Actions --- default tab redirect", () => {
  /*
   * The "Actions" item on a workspace card links to the bare `/:workspace/actions` path
   * (Components/WorkspaceCard/WorkspaceCard.tsx), so this redirect is the only thing that gets a
   * user onto a tab. React Router v5's `<Redirect from to>` interpolated route params; v7's
   * `<Navigate>` does not, so redirecting to the AppPath PATTERN
   * ("/:workspace/actions/approvals") navigated to that literal URL - the loader then fetched
   * a workspace called ":workspace" and the page errored.
   */
  test("sends the bare actions path to the approvals tab with the real workspace", async () => {
    const { history } = renderActions();

    await waitFor(() =>
      expect(history.location.pathname).toBe(appLink.actionsApprovals({ workspace: WORKSPACE })),
    );
  });
});
