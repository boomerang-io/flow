import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { serviceUrl } from "Config/servicesConfig";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import WorkspaceTasks, { action, loader } from "./WorkspaceTasks";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

// Route-module test pattern - see GlobalParameters.spec.tsx/AdminTasks.spec.tsx. WorkspaceTasks
// reads the active workspace off its context, so the harness's WorkspaceContextProvider is
// overridden with the full workspace fixture the WORKSPACE constant names - production supplies
// it from app/routes/workspaceLayout.tsx's loader.
function renderWorkspaceTasks(route: string = `/${WORKSPACE}/task-manager`) {
  return renderWithContext(
    <Route
      path="/:workspace/task-manager/*"
      loader={loader}
      action={action}
      element={<WorkspaceTasks />}
    />,
    { route, workspaceValue: { workspace: workspaceFixture } },
  );
}

// `serviceUrl.workspace.task.*` has a default handler in handlers.ts (it had none at all under
// Mirage - this spec used to register every one of these routes on its own server instance every
// test), so nothing needs registering here.
// Mirrors AdminTasks.spec.tsx: the sidenav's task list is independent of the selected template's
// task/changelog pair, so all three belong in one wave. Only the YAML read (editor sub-route)
// genuinely depends on the selected task having resolved.
describe("WorkspaceTasks --- loader concurrency", () => {
  test("fires the task list alongside the selected task and its changelog", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(
        serviceUrl.workspace.task.queryTasks({ workspace: ":workspace", query: "" }),
        trace.resolver("tasks", { content: [] }),
      ),
      http.get(
        serviceUrl.workspace.task.getTaskChangelog({ workspace: ":workspace", name: ":name" }),
        trace.resolver("changelog", []),
      ),
      http.get(
        serviceUrl.workspace.task.getTask({ workspace: ":workspace", name: ":name" }),
        trace.resolver("selectedTask", { name: "a-task" }),
      ),
    );

    await loader({
      params: { workspace: WORKSPACE, "*": "a-task/1" },
      request: new Request(`http://localhost/${WORKSPACE}/task-manager/a-task/1`),
    });

    expect(trace.startedTogether(3)).toBe(true);
  });
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
      intent: "applyYaml",
      task: { name: "execute-advanced-http-call", displayName: "YAML Task", version: 9 },
    });
  });

  test("surfaces a failed apply without throwing", async () => {
    server.use(
      http.put(
        serviceUrl.workspace.task.putTask({ workspace: ":workspace", name: ":name", replace: false }).split("?")[0],
        () => HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = await submit({
      intent: "apply",
      name: "execute-advanced-http-call",
      replace: "true",
      body: JSON.stringify({ name: "execute-advanced-http-call" }),
    });

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const errorResult = result as unknown as { data: { intent: string } };
    expect(isActionError(errorResult.data)).toBe(true);
    expect(errorResult.data.intent).toBe("apply");
  });

  test("validates an uploaded file through the mocked API", async () => {
    const result = await submit({ intent: "validateYaml", body: "name: some-task\n" });

    expect(result).toEqual({ intent: "validateYaml" });
  });

  test("surfaces a failed validation without throwing", async () => {
    server.use(
      http.post(serviceUrl.workspace.task.postValidateYaml({ workspace: WORKSPACE }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = (await submit({ intent: "validateYaml", body: "not: valid: yaml: at all" })) as unknown as {
      data: { intent: string };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("validateYaml");
  });
});
