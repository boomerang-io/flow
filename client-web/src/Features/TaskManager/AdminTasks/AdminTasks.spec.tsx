import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import AdminTasks, { action, loader } from "./AdminTasks";

// Route-module test pattern - see GlobalParameters.spec.tsx. The real route ("/admin/task-manager/*"
// in app/routes.ts) is a splat: AdminTasks.tsx's loader reads params["*"] itself to figure out
// which task-template sub-page (if any) is being requested, so the test route below has to use
// the same "/*" pattern rather than a bare catch-all for the splat value to line up.
function renderAdminTasks(route: string = "/admin/task-manager") {
  return renderWithContext(
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

// The sidenav's task list is independent of the selected template's task/changelog pair, so all
// three belong in one wave. Only the YAML read (editor sub-route) genuinely depends on the
// selected task having resolved.
describe("AdminTasks --- loader concurrency", () => {
  test("fires the task list alongside the selected task and its changelog", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(serviceUrl.task.queryTasks({ query: "" }), trace.resolver("tasks", { content: [] })),
      http.get(serviceUrl.task.getTaskChangelog({ name: ":name" }), trace.resolver("changelog", [])),
      http.get(serviceUrl.task.getTask({ name: ":name" }), trace.resolver("selectedTask", { name: "a-task" })),
    );

    await loader({
      params: { "*": "a-task/1" },
      request: new Request("http://localhost/admin/task-manager/a-task/1"),
    });

    expect(trace.startedTogether(3)).toBe(true);
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

    expect(result).toEqual({ intent: "applyYaml", task: { name: "execute-advanced-http-call", displayName: "YAML Task", version: 9 } });
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

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({ request })) as unknown as { data: { intent: string } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("apply");
  });

  test("validates an uploaded file through the mocked API", async () => {
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({ intent: "validateYaml", body: "name: some-task\n" }),
    });

    const result = await action({ request });

    expect(result).toEqual({ intent: "validateYaml" });
  });

  test("surfaces a failed validation without throwing", async () => {
    server.use(http.post(serviceUrl.task.postValidateYaml(), () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/task-manager", {
      method: "post",
      body: new URLSearchParams({ intent: "validateYaml", body: "not: valid: yaml: at all" }),
    });

    const result = (await action({ request })) as unknown as { data: { intent: string } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("validateYaml");
  });
});
