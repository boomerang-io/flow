import React from "react";
import Workspaces, { action, loader } from "Features/Workspaces/Workspaces";
import { Route } from "react-router-dom";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { renderWithContext } from "Utils/testing/render";

// setupTests.tsx freezes the wall clock via `vi.setSystemTime` (no fake timers), and lodash's
// debounce decides whether to invoke by comparing `Date.now()` deltas - with time frozen its
// trailing-edge timer re-schedules forever and the callback never runs. Neutralise the debounce
// (invoke immediately) so the typed-search test below can exercise the real
// change -> navigate -> loader -> API chain.
vi.mock("lodash/debounce", () => ({ default: (fn: (...args: unknown[]) => void) => fn }));

// Route-module test pattern (see GlobalParameters.spec.tsx): attach loader/action to the <Route>
// the same way AppRoutes.tsx does via app/routes/workspaceList.tsx, so renderWithContext
// actually exercises them instead of leaving useLoaderData() undefined.
function renderWorkspaces(route: string = appLink.workspaceList()) {
  return renderWithContext(
    <Route path={AppPath.WorkspaceList} loader={loader} action={action} element={<Workspaces />} />,
    { route },
  );
}

describe("Workspaces --- Snapshot Test", () => {
  it("Capturing Snapshot of Workspaces", async () => {
    const { baseElement } = renderWorkspaces();
    await screen.findByText("Tyson Workspace");
    expect(baseElement).toMatchSnapshot();
  });
});

// Regression for #386: the search box's debounced `query` URL param must reach the API as
// `search=` (the loader forwards it; the MSW /workspace/query handler mirrors the backend's
// anchored, case-insensitive prefix match on name/displayName).
describe("Workspaces --- search sends its term (#386)", () => {
  test("a direct load of ?query= filters the list server-side and seeds the search box", async () => {
    renderWorkspaces(`${appLink.workspaceList()}?query=system`);
    expect(await screen.findByText("System and Administration")).toBeInTheDocument();
    expect(screen.queryByText("Tyson Workspace")).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search workspaces")).toHaveValue("system");
  });

  test("typing in the search box filters the list server-side", async () => {
    renderWorkspaces();
    await screen.findByText("Tyson Workspace");
    fireEvent.change(screen.getByPlaceholderText("Search workspaces"), { target: { value: "system" } });
    // The (neutralised, see the lodash/debounce mock above) search change navigates to
    // ?query=system, which re-runs the loader with search= - the unfiltered list shows every
    // workspace, so the meaningful signal is the non-matching rows disappearing.
    await waitFor(() => expect(screen.queryByText("Tyson Workspace")).not.toBeInTheDocument());
    expect(screen.getByText("System and Administration")).toBeInTheDocument();
  });
});

describe("Workspaces --- RTL", () => {
  test("Create new workspace", async () => {
    renderWorkspaces();
    const createWorkspaceButton = await screen.findByText(/^Create Workspace$/i);
    fireEvent.click(createWorkspaceButton);
    expect(screen.getByText(/^Scope your workflows and parameters to a workspace$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Create$/i)).toBeDisabled();
    const workspaceNameInput = screen.getByLabelText(/^Display Name$/i);
    // A single `fireEvent.change` (one onChange, the final value) rather than
    // `userEvent.type`'s per-keystroke simulation - WorkspaceCreateContent.tsx's Formik
    // `validate` fires (and hits the mocked validate-name endpoint) on every change, so typing
    // character-by-character fires it repeatedly and races those in-flight requests against each
    // other with no guarantee the *last-initiated* one also settles *last*.
    fireEvent.change(workspaceNameInput, { target: { value: "Test workspace" } });
    // WorkspaceCreateContent.tsx's async validate-name check (Formik's Yup `test`, plus the
    // separate `validateWorkspaceNameMutator` react-query mutation that backs it) settles in
    // more than one render pass - the button flips disabled ("Validating...") -> enabled
    // ("Create") -> briefly disabled again before it's truly done, so a single
    // `await waitFor(enabled)` can observe the button enabled on one tick and find it disabled
    // again by the time the very next line runs (confirmed via `screen.debug()`: the DOM read
    // right after a passing `waitFor` still showed "Validating..."). Clicking a disabled button
    // is a no-op in jsdom (the click handler doesn't fire), so retrying the click on every
    // enabled sighting is safe - this polls for "genuinely done" (enabled and stays that way
    // long enough for the click to land) without a side effect inside `waitFor` itself (which
    // the testing-library/no-wait-for-side-effects rule disallows).
    for (let attempt = 0; attempt < 40; attempt++) {
      if (screen.queryByText(/Test workspace/i)) break;
      const createButton = screen.queryByText(/^Create$/i);
      if (createButton && !createButton.hasAttribute("disabled")) {
        fireEvent.click(createButton);
      }
      // eslint-disable-next-line no-await-in-loop
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    expect(await screen.findByText(/Test workspace/i)).toBeInTheDocument();
  });
});
