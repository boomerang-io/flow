import { Route, useFetcher } from "react-router-dom";
import { screen, fireEvent, waitForElementToBeRemoved } from "@testing-library/react";
import { db } from "ApiServer/msw/db";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { WorkspaceContainer } from "Features/App/App";
import WorkspaceParameters, { action, loader } from "./WorkspaceParameters";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

// WorkspaceContainer resolves the active workspace by name (handlers.ts's `findWorkspace`), so the
// fixture the WORKSPACE constant names has to be seeded - same as WorkspaceTasks.spec.tsx.
beforeEach(() => {
  db.workspaces.push(structuredClone(workspaceFixture));
});

/*
 * Submits the delete the table's own ActionsMenu submits, without driving Carbon's nested
 * overflow-menu -> confirm-modal flow, which fights the library's aria-hidden/portal handling in
 * jsdom rather than testing this route (GlobalParameters.spec.tsx makes the same call). It renders
 * INSIDE the route element, so a bare useFetcher resolves to this route's action exactly as the
 * real trigger does - the action, the write and the revalidation that follows are all real.
 */
function DeleteFirstParameter() {
  const fetcher = useFetcher();
  return (
    <button
      data-testid="submit-delete"
      onClick={() => fetcher.submit({ intent: "delete", name: "test", label: "Test" }, { method: "post" })}
    >
      delete
    </button>
  );
}

function renderWorkspaceParameters() {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/parameters"
      loader={loader}
      action={action}
      element={
        <WorkspaceContainer>
          <DeleteFirstParameter />
          <WorkspaceParameters />
        </WorkspaceContainer>
      }
    />,
    { route: `/${WORKSPACE}/parameters` },
  );
}

describe("WorkspaceParameters", () => {
  test("renders the parameters resolved by the loader", async () => {
    renderWorkspaceParameters();
    expect(await screen.findByText("test value")).toBeInTheDocument();
  });

  /*
   * The table used to read `useWorkspaceContext().workspace.parameters` - WorkspaceContainer's own
   * react-query cache - while the writes had moved to this route's action. A fetcher settle
   * revalidates loaders, not react-query, and this route had no loader, so a create/edit/delete
   * left the row on screen (with a success toast) until the user navigated away and back. The old
   * `queryClient.invalidateQueries` was dropped in the conversion and `refetchOnWindowFocus: false`
   * (app/root.tsx) removed the last accidental refresh.
   */
  test("refreshes the table after a write, without navigating away", async () => {
    renderWorkspaceParameters();
    const row = await screen.findByText("test value");

    fireEvent.click(screen.getByTestId("submit-delete"));

    await waitForElementToBeRemoved(row);
    expect(screen.queryByText("test value")).not.toBeInTheDocument();
  });
});
