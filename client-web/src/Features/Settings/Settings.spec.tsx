/* eslint-disable */
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import Settings, { action, loader } from "./Settings";

// Route-module test pattern (see GlobalParameters.spec.tsx): build the same shape the real
// router config uses (a <Route> carrying loader/action alongside its element) so the loader/
// action actually run.
function renderSettings() {
  return renderWithContext(<Route path="*" loader={loader} action={action} element={<Settings />} />);
}

describe("Settings --- Snapshot", () => {
  test("Capturing Snapshot of Settings", async () => {
    const { baseElement } = renderSettings();
    await screen.findByRole("heading", { name: /^Settings$/i });
    // Carbon's ComboBox derives its input's `title` from a ref it reads during render
    // (@carbon/react ComboBox.js:589, `title: textInput?.current?.value`), so the attribute is
    // absent on the first pass and only appears once something re-renders the field - here
    // SettingsSection's Formik, asynchronously. The recorded snapshot carries
    // `title="Always"` on the job.deletion.policy combobox, so capturing on the page heading
    // alone raced that second pass and lost it under worker load. This is the only `title="Always"`
    // in the tree.
    await screen.findByTitle("Always");
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
    server.use(http.get(serviceUrl.resourceSettings(), () => HttpResponse.json({}, { status: 500 })));
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

    expect(result).toEqual({});
  });

  test("surfaces a failed update without throwing", async () => {
    server.use(http.put(serviceUrl.resourceSettings(), () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/settings", {
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
