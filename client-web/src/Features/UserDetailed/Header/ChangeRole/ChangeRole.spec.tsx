import React from "react";
import { describe, expect, it, vi } from "vitest";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { profile as userFixture } from "ApiServer/fixtures";
import { PlatformRole } from "Types";
import type { FlowUser } from "Types";
import { renderWithContext } from "Utils/testing/render";
import ChangeRole from ".";

// Starts as Admin so that picking "User" is an actual change (the Submit button is disabled while
// the selection still equals the user's current role).
// The shared fixture is the raw wire payload (its `type` is a plain string), so it needs the
// two-step cast to land on FlowUser - the same shape the loader hands the component at runtime.
const user = { ...(userFixture as unknown as FlowUser), name: "Test User", type: PlatformRole.Admin };

/**
 * Renders ChangeRole under a real data router whose action is a spy, so what the component
 * SUBMITS is observable - the component now writes through a bare useFetcher(), which resolves to
 * the nearest matched route's action (Features/UserDetailed/UserDetailed.tsx in the app).
 */
function renderWithAction() {
  const submitted: Array<Record<string, string>> = [];
  const action = vi.fn(async ({ request }: { request: Request }) => {
    const formData = await request.formData();
    submitted.push(Object.fromEntries(formData) as Record<string, string>);
    return { intent: "changeRole" };
  });
  const view = renderWithContext(
    <Route path="*" element={<ChangeRole user={user} closeModal={() => vi.fn()} />} action={action} />,
  );
  return { ...view, submitted };
}

describe("ChangeRole --- Snapshot Test", () => {
  it("Capturing Snapshot of ChangeRole", async () => {
    const { baseElement } = renderWithAction();
    // Guards against a snapshot that enshrines a crashed render: the radio group must be there.
    expect(screen.getByLabelText("User")).toBeInTheDocument();
    expect(baseElement).toMatchSnapshot();
    await waitFor(() => null);
  });
});

describe("ChangeRole --- Submission", () => {
  it("submits the role the user actually selected, not a fixed one", async () => {
    const { submitted } = renderWithAction();

    // Carbon v11's RadioButtonGroup calls onChange(newSelection, name, evt) - the component reads
    // the first argument, so the selection has to reach the request body.
    await userEvent.click(screen.getByLabelText("User"));
    await userEvent.click(screen.getByRole("button", { name: "Submit" }));

    await waitFor(() => expect(submitted).toHaveLength(1));
    expect(submitted[0]).toEqual({ intent: "changeRole", type: PlatformRole.User });
    // The id of the user being changed is NOT submitted - the action reads it off the :userId
    // route param, so a submission cannot retarget the write.
    expect(submitted[0]).not.toHaveProperty("userId");
  });

  it("keeps Submit disabled while the selection still matches the current role", async () => {
    renderWithAction();
    expect(screen.getByRole("button", { name: "Submit" })).toBeDisabled();

    await userEvent.click(screen.getByLabelText("User"));
    expect(screen.getByRole("button", { name: "Submit" })).toBeEnabled();
  });
});
