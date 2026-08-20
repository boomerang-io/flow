/* eslint-disable */
import { Response } from "miragejs";
import { Route } from "react-router-dom";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { startApiServer } from "ApiServer";
import { serviceUrl } from "Config/servicesConfig";
import Settings, { action, loader } from "./Settings";

// Route-module test pattern (see GlobalParameters.spec.tsx): build the same shape the real
// router config uses (a <Route> carrying loader/action alongside its element) so the loader/
// action actually run.
function renderSettings() {
  return global.rtlContextRouterRender(<Route path="*" loader={loader} action={action} element={<Settings />} />);
}

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("Settings --- Snapshot", () => {
  test("Capturing Snapshot of Settings", async () => {
    const { baseElement } = renderSettings();
    await screen.findByRole("heading", { name: /^Settings$/i });
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Settings --- RTL", () => {
  test("Loads and opens section", async () => {
    renderSettings();
    expect(await screen.findByRole("heading", { name: /^Settings$/i })).toBeInTheDocument();
    const section = await screen.findByRole("heading", { name: "Workers" });
    fireEvent.click(section);
    expect(screen.getByLabelText(/^Enable Debug$/i)).toBeInTheDocument();
  });
});

describe("Settings --- RTL", () => {
  beforeEach(() => {
    server.get(serviceUrl.resourceSettings(), () => {
      return new Response(500, {}, {});
    });
  });
  test("Shows error message on request failure", async () => {
    renderSettings();
    await waitFor(() => {
      expect(screen.getByText("Oops, something went wrong.")).toBeInTheDocument();
    });
  });
});

describe("Settings --- action", () => {
  const settingsGroup = { key: "controller", name: "Workers", description: "", config: [] };

  test("updates settings through the mocked API", async () => {
    const request = new Request("http://localhost/admin/settings", {
      method: "post",
      body: new URLSearchParams({ intent: "update", settingsGroup: JSON.stringify(settingsGroup) }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: true });
  });

  test("surfaces a failed update without throwing", async () => {
    server.put(serviceUrl.resourceSettings(), () => new Response(500, {}, {}));
    const request = new Request("http://localhost/admin/settings", {
      method: "post",
      body: new URLSearchParams({ intent: "update", settingsGroup: JSON.stringify(settingsGroup) }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: false });
  });
});
