import { Route } from "react-router-dom";
import { fireEvent, screen } from "@testing-library/react";
import { profile as userFixture } from "ApiServer/fixtures";
import type { FlowUser } from "Types";
import { renderWithContext } from "Utils/testing/render";
import Settings from "./Settings";

function renderSettings() {
  return renderWithContext(
    <Route path="/profile" element={<Settings user={userFixture as unknown as FlowUser} userManagementEnabled />} />,
    { route: "/profile" },
  );
}

describe("UserProfile Settings", () => {
  test("the display-name dialog is about the user's display name, not a workspace", async () => {
    renderSettings();
    fireEvent.click(await screen.findByTestId("open-change-name-modal"));
    expect(screen.getByText("Change your display name")).toBeInTheDocument();
    expect(screen.queryByText("Change workspace name")).not.toBeInTheDocument();
  });

  test("Delete Account carries its own test id", async () => {
    renderSettings();
    expect(await screen.findByTestId("delete-account")).toHaveTextContent("Delete Account");
    expect(screen.queryByTestId("close-workspace")).not.toBeInTheDocument();
  });
});
