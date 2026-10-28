import React from "react";
import { fireEvent, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { profile } from "ApiServer/fixtures";
import { server } from "ApiServer/msw/node";
import { ContextConfig, FlowUser } from "Types";
import { renderWithRouter } from "Utils/testing/render";
import Navbar from "./Navbar";

/*
 * The header's help and profile menus. The shell adds items of its own; these pin that each
 * menu is one list of Flow's items, with no item twice, and that About shows Flow's version.
 */
const contextData = {
  features: {},
  navigation: [],
  platform: {
    appName: "Acme Automation",
    baseEnvUrl: "http://localhost:3000",
    baseServicesUrl: "http://localhost:7700/api/v2",
    displayLogo: false,
    name: "Boomerang Flow",
    platformName: "Boomerang",
    privateWorkspaces: false,
    sendMail: false,
    signOutUrl: "",
    version: "5.0.0-beta.34",
  },
  platformMessage: { kind: "", message: "", title: "" },
} as unknown as ContextConfig;

function renderNavbar() {
  return renderWithRouter(
    <Navbar
      flowNavigationData={[]}
      contextData={contextData}
      userData={profile as unknown as FlowUser}
    />,
  );
}

describe("Navbar menus", () => {
  it("lists Docs, Release Notes and About once each in the help menu, every one with an icon", () => {
    renderNavbar();
    fireEvent.click(screen.getByTestId("header-support-link"));

    const menu = screen.getByRole("menu");
    const items = within(menu).getAllByRole("menuitem");
    // About names the product, not the install's own app name.
    expect(items.map((item) => item.textContent?.trim())).toEqual(["Docs", "Release Notes", "About Boomerang Flow"]);
    // Carbon icons are aria-hidden, so no role query reaches them.
    // eslint-disable-next-line testing-library/no-node-access
    items.forEach((item) => expect(item.querySelector("svg")).not.toBeNull());
    // Carbon puts the menuitem role on the list item and the href on the link inside it.
    expect(within(items[0]).getByRole("link")).toHaveAttribute("href", "https://www.useboomerang.io/flow");
    expect(within(items[1]).getByRole("link")).toHaveAttribute("href", "https://github.com/boomerang-io/flow/releases");
  });

  it("opens About with Flow's own version, and keeps it open once the help menu closes", () => {
    renderNavbar();
    fireEvent.click(screen.getByTestId("header-support-link"));
    fireEvent.click(screen.getByTestId("about-flow"));
    // A browser closes the menu on an item click, unmounting its items.
    fireEvent.click(screen.getByTestId("header-support-link"));

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByText("Version 5.0.0-beta.34")).toBeInTheDocument();
    expect(screen.getByText("Source code on GitHub")).toHaveAttribute("href", "https://github.com/boomerang-io/flow");
  });

  it("names the user once in the profile menu", async () => {
    // Any answer from the shell's launchpad user route makes its own profile row print the name
    // a second time.
    server.use(http.get(`${contextData.platform.baseServicesUrl}/launchpad/user`, () => HttpResponse.json(profile)));
    renderNavbar();
    fireEvent.click(screen.getByTestId("header-profile-link"));

    const menu = screen.getByRole("menu");
    await within(menu).findByText(profile.displayName);
    // Time for the shell's own fetch to answer, so a second name would be on screen to count.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(within(menu).getAllByText(profile.displayName)).toHaveLength(1);
    expect(within(menu).getByRole("menuitem", { name: /Account Settings/ })).toBeInTheDocument();
  });
});
