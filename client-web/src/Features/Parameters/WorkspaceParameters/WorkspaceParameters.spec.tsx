import { Route, useFetcher } from "react-router-dom";
import { screen, fireEvent, waitFor, waitForElementToBeRemoved, within } from "@testing-library/react";
import { db } from "ApiServer/msw/db";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { renderWithContext } from "Utils/testing/render";
import WorkspaceParameters, { action, loader } from "./WorkspaceParameters";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

// notify() hands its toast to react-toastify, which needs a container this tree does not mount;
// the mock records the toast each write raises instead.
const { notify } = vi.hoisted(() => ({ notify: vi.fn() }));
vi.mock("@boomerang-io/carbon-addons-boomerang-react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@boomerang-io/carbon-addons-boomerang-react")>()),
  notify,
}));

// This route's own loader reads the workspace record via `resourceWorkspace` (a real lookup by
// name - handlers.ts's `findWorkspace`), so the fixture the WORKSPACE constant names has to be
// seeded. The context workspace comes from the harness's WorkspaceContextProvider override below.
beforeEach(() => {
  db.workspaces.push(structuredClone(workspaceFixture));
  notify.mockClear();
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
  return renderWithContext(
    <Route
      path="/:workspace/parameters"
      loader={loader}
      action={action}
      element={
        <>
          <DeleteFirstParameter />
          <WorkspaceParameters />
        </>
      }
    />,
    { route: `/${WORKSPACE}/parameters`, workspaceValue: { workspace: workspaceFixture } },
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

  // The delete's result is handled by the page, which outlives the row the table drops once the
  // list revalidates, so its toast is still raised.
  test("deleting a parameter from its row raises the success toast", async () => {
    renderWorkspaceParameters();
    await screen.findByText("test value");

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    const confirm = screen.getAllByRole("dialog", { hidden: true }).find((dialog) => /will get no value/.test(dialog.textContent ?? ""))!;
    fireEvent.click(within(confirm).getByRole("button", { name: /Delete/, hidden: true }));

    await waitFor(() => expect(notify).toHaveBeenCalledTimes(1));
    expect(notify.mock.calls[0][0].props).toMatchObject({ kind: "success", title: "Parameter Deleted" });
  });
});
