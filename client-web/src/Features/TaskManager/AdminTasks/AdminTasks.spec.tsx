import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import AdminTasks, { action, loader } from "./AdminTasks";

// Route-module test pattern - see GlobalParameters.spec.tsx. The real route ("/admin/task-manager/*"
// in app/routes.ts) is a splat: AdminTasks.tsx's loader reads params["*"] itself to figure out
// which task-template sub-page (if any) is being requested, so the test route below has to use
// the same "/*" pattern rather than a bare catch-all for the splat value to line up.
function renderAdminTasks(route: string = "/admin/task-manager") {
  return global.rtlContextRouterRender(
    <Route path="/admin/task-manager/*" loader={loader} action={action} element={<AdminTasks />} />,
    { route },
  );
}

describe("AdminTasks --- loader", () => {
  test("renders the task list in the sidenav", async () => {
    renderAdminTasks();
    expect(await screen.findByText("Execute Advanced HTTP Call")).toBeInTheDocument();
  });

  test("renders the selected task template on the :name/:version route", async () => {
    renderAdminTasks("/admin/task-manager/execute-advanced-http-call/4");
    expect(await screen.findAllByText("Execute Advanced HTTP Call")).not.toHaveLength(0);
  });

  test("renders a not-found state for an unknown task template", async () => {
    renderAdminTasks("/admin/task-manager/does-not-exist/1");
    expect(await screen.findByText("Task Template not found")).toBeInTheDocument();
  });
});

describe("AdminTasks --- action", () => {
  test("applies a task template (json) through the mocked API", async () => {
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({
        intent: "apply",
        name: "execute-advanced-http-call",
        replace: "true",
        body: JSON.stringify({ name: "execute-advanced-http-call", displayName: "Updated Task", version: 5 }),
      }),
    });

    const result = await action({ request });

    expect(result).toEqual({
      ok: true,
      intent: "apply",
      task: { name: "execute-advanced-http-call", displayName: "Updated Task", version: 5 },
    });
  });

  test("applies a task template (yaml) through the mocked API", async () => {
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({
        intent: "applyYaml",
        name: "execute-advanced-http-call",
        replace: "false",
        body: "name: execute-advanced-http-call\ndisplayName: Yaml Import\n",
      }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: true, intent: "applyYaml", task: { name: "execute-advanced-http-call", displayName: "YAML Task", version: 9 } });
  });

  test("surfaces a failed apply without throwing", async () => {
    server.use(http.put(serviceUrl.task.putTask({ name: ":name", replace: false }).split("?")[0], () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({
        intent: "apply",
        name: "execute-advanced-http-call",
        replace: "true",
        body: JSON.stringify({ name: "execute-advanced-http-call" }),
      }),
    });

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("apply");
  });

  test("validates an uploaded file through the mocked API", async () => {
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({ intent: "validateYaml", body: "name: some-task\n" }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: true, intent: "validateYaml" });
  });

  test("surfaces a failed validation without throwing", async () => {
    server.use(http.post(serviceUrl.task.postValidateYaml(), () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({ intent: "validateYaml", body: "not: valid: yaml: at all" }),
    });

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("validateYaml");
  });
});
