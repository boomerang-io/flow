import React from "react";
import { describe, expect, it } from "vitest";
import { Route } from "react-router-dom";
import { waitFor, screen } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { renderWithContext } from "Utils/testing/render";
import UserDetailed, { action, loader } from "./UserDetailed";

const USER_ID = "5f170b3df6ab327e302cb0a5";

function renderUserDetailed(route = appLink.user({ userId: USER_ID })) {
  return renderWithContext(
    <Route path={`${AppPath.User}/*`} loader={loader} action={action} element={<UserDetailed />} />,
    { route },
  );
}

describe("UserDetailed --- Snapshot Test", () => {
  it("Capturing Snapshot of UserDetailed", async () => {
    const { baseElement } = renderUserDetailed();
    await screen.findByText("These are Tim Bula's workspaces");
    expect(baseElement).toMatchSnapshot();
    await waitFor(() => null);
  });
});

describe("UserDetailed --- Labels", () => {
  it("saves labels through the route action rather than a browser mutation", async () => {
    renderUserDetailed(`${appLink.user({ userId: USER_ID })}/labels`);

    // The loader-backed user record drives the page; wait for it before interacting.
    await screen.findByText("These are Tim Bula's labels");

    // Nothing is dirty yet, so Save stays disabled - the same guard the component had before the
    // conversion (it was keyed on Formik's `dirty` and the mutation's isLoading; isLoading is now
    // the fetcher's state). Proves the component still renders and wires its disabled state after
    // losing useMutation. The write itself is covered by UserDetailed.action.node.spec.ts.
    expect(await screen.findByRole("button", { name: /save/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /add a new label/i })).toBeEnabled();
  });
});
