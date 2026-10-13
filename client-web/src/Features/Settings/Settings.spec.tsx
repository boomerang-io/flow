/* eslint-disable */
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import Settings, { action, loader } from "./Settings";

// Route-module test pattern (see GlobalParameters.spec.tsx): the same shape the real router config
// uses - the optional `:group` segment, with loader and action alongside the element.
function renderSettings(route = "/admin/settings") {
  return renderWithContext(
    <Route path="/admin/settings/:group?" loader={loader} action={action} element={<Settings />} />,
    { route },
  );
}

describe("Settings --- sections", () => {
  test("opens on the first group by name and lists every group in the side navigation", async () => {
    const { history } = renderSettings();

    // The URL names the group, so a section is linkable.
    await waitFor(() => expect(history.location.pathname).toMatch(/^\/admin\/settings\/.+/));
    expect(await screen.findByTestId("settings-section")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Workers" })).toBeInTheDocument();
  });

  test("selecting a group in the side navigation shows its form", async () => {
    const { history } = renderSettings();
    await screen.findByTestId("settings-section");

    userEvent.click(screen.getByRole("link", { name: "Workers" }));

    await waitFor(() => expect(history.location.pathname).toBe("/admin/settings/controller"));
    expect(await screen.findByRole("heading", { name: "Workers" })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Enable Debug$/i)).toBeInTheDocument();
    // Nothing changed yet, so there is nothing to save.
    expect(screen.getByRole("button", { name: /Save/ })).toBeDisabled();
  });

  test("a group named in the URL opens directly", async () => {
    renderSettings("/admin/settings/controller");
    expect(await screen.findByRole("heading", { name: "Workers" })).toBeInTheDocument();
  });
});

describe("Settings --- error", () => {
  beforeEach(() => {
    server.use(http.get(serviceUrl.resourceSettings(), () => HttpResponse.json({}, { status: 500 })));
  });
  test("shows the error surface when the settings cannot be read", async () => {
    renderSettings();
    await waitFor(() => {
      expect(screen.getByText("Oops, something went wrong.")).toBeInTheDocument();
    });
  });
});

describe("Settings --- action", () => {
  const settingsGroup = { key: "controller", name: "Workers", description: "", config: [] };

  test("updates settings through the mocked API", async () => {
    const request = new Request("http://localhost/admin/settings/controller", {
      method: "post",
      body: new URLSearchParams({ intent: "update", settingsGroup: JSON.stringify(settingsGroup) }),
    });

    const result = await action({ request });

    expect(result).toEqual({});
  });

  test("surfaces a failed update without throwing", async () => {
    server.use(http.put(serviceUrl.resourceSettings(), () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/settings/controller", {
      method: "post",
      body: new URLSearchParams({ intent: "update", settingsGroup: JSON.stringify(settingsGroup) }),
    });

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({ request })) as unknown as { data: unknown };

    expect(isActionError(result.data)).toBe(true);
  });
});
