import { Response } from "miragejs";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { startApiServer } from "ApiServer";
import { WorkspaceContainer } from "Features/App/App";
import { BASE_URL, serviceUrl } from "Config/servicesConfig";
import WorkspaceTasks, { action, loader } from "./WorkspaceTasks";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js, which
// resourceWorkspace always returns regardless of the :workspace param.

// Route-module test pattern - see GlobalParameters.spec.tsx/AdminTasks.spec.tsx. Wraps the same
// WorkspaceContainer app/routes/manageTasks.tsx does, since WorkspaceTasks reads the active
// workspace off its context (unrelated to the loader/action migration - untouched here).
function renderWorkspaceTasks(route: string = `/${WORKSPACE}/task-manager`) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/task-manager/*"
      loader={loader}
      action={action}
      element={
        <WorkspaceContainer>
          <WorkspaceTasks />
        </WorkspaceContainer>
      }
    />,
    { route },
  );
}

let server: any;

beforeEach(() => {
  server = startApiServer();
  // Unlike the admin task routes, none of serviceUrl.workspace.task.* has a mock registered in
  // ApiServer/index.js at all - register the ones this route's loader/action actually call.
  // "/task/query" is registered before the "/task/:name" wildcard so the literal "query" segment
  // doesn't get swallowed as a task name.
  server.get(`${BASE_URL}/workspace/:workspace/task/query`, () => server.schema.db.task[0]);
  server.get(`${BASE_URL}/workspace/:workspace/task/:name/changelog`, () => [
    { author: "Bob", reason: "Add new task", date: "2023-08-16T22:34:05.234+00:00", version: 1 },
    { author: "Jenny", reason: "Update task", date: "2023-08-17T22:34:05.234+00:00", version: 2 },
  ]);
  server.get(`${BASE_URL}/workspace/:workspace/task/:name`, (schema: any, request: any) =>
    schema.db.task[0].content.find((t: any) => t.name === request.params.name),
  );
  server.put(`${BASE_URL}/workspace/:workspace/task/:name`, (schema: any, request: any) => {
    const contentType = request.requestHeaders["content-type"] ?? request.requestHeaders["Content-Type"];
    if (contentType && contentType.includes("yaml")) {
      return { name: request.params.name, displayName: "YAML Task", version: 9 };
    }
    return JSON.parse(request.requestBody);
  });
  server.post(`${BASE_URL}/workspace/:workspace/task/validate`, () => new Response(200, {}, {}));
});

afterEach(() => {
  server.shutdown();
});

describe("WorkspaceTasks --- loader", () => {
  test("renders the task list in the sidenav", async () => {
    renderWorkspaceTasks();
    expect(await screen.findByText("Execute Advanced HTTP Call")).toBeInTheDocument();
  });

  test("renders the selected task template on the :name/:version route", async () => {
    renderWorkspaceTasks(`/${WORKSPACE}/task-manager/execute-advanced-http-call/4`);
    expect(await screen.findAllByText("Execute Advanced HTTP Call")).not.toHaveLength(0);
  });

  test("renders a not-found state for an unknown task template", async () => {
    renderWorkspaceTasks(`/${WORKSPACE}/task-manager/does-not-exist/1`);
    expect(await screen.findByText("Task Template not found")).toBeInTheDocument();
  });
});

describe("WorkspaceTasks --- action", () => {
  function submit(body: Record<string, string>) {
    return action({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/task-manager`, {
        method: "post",
        body: new URLSearchParams(body),
      }),
    });
  }

  test("applies a task template (json) through the mocked API", async () => {
    const result = await submit({
      intent: "apply",
      name: "execute-advanced-http-call",
      replace: "true",
      body: JSON.stringify({ name: "execute-advanced-http-call", displayName: "Updated Task", version: 5 }),
    });

    expect(result).toEqual({
      ok: true,
      intent: "apply",
      task: { name: "execute-advanced-http-call", displayName: "Updated Task", version: 5 },
    });
  });

  test("applies a task template (yaml) through the mocked API", async () => {
    const result = await submit({
      intent: "applyYaml",
      name: "execute-advanced-http-call",
      replace: "false",
      body: "name: execute-advanced-http-call\ndisplayName: Yaml Import\n",
    });

    expect(result).toEqual({
      ok: true,
      intent: "applyYaml",
      task: { name: "execute-advanced-http-call", displayName: "YAML Task", version: 9 },
    });
  });

  test("surfaces a failed apply without throwing", async () => {
    server.put(`${BASE_URL}/workspace/:workspace/task/:name`, () => new Response(500, {}, {}));

    const result = await submit({
      intent: "apply",
      name: "execute-advanced-http-call",
      replace: "true",
      body: JSON.stringify({ name: "execute-advanced-http-call" }),
    });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("apply");
  });

  test("validates an uploaded file through the mocked API", async () => {
    const result = await submit({ intent: "validateYaml", body: "name: some-task\n" });

    expect(result).toEqual({ ok: true, intent: "validateYaml" });
  });

  test("surfaces a failed validation without throwing", async () => {
    server.post(serviceUrl.workspace.task.postValidateYaml({ workspace: WORKSPACE }), () => new Response(500, {}, {}));

    const result = await submit({ intent: "validateYaml", body: "not: valid: yaml: at all" });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("validateYaml");
  });
});
