import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { db } from "ApiServer/msw/db";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { WorkspaceContainer } from "Features/App/App";
import { serviceUrl } from "Config/servicesConfig";
import WorkspaceTasks, { action, loader } from "./WorkspaceTasks";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

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

// WorkspaceContainer resolves the active workspace via `resourceWorkspace`, which now does a
// real lookup by name (see handlers.ts's `findWorkspace`) instead of Mirage's old
// always-return-the-canned-fixture behaviour - seed the fixture the WORKSPACE constant above
// names so that lookup actually finds something (same reasoning as WorkspaceDetailed.spec.tsx).
// `serviceUrl.workspace.task.*` itself now has a default handler in handlers.ts (it had none at
// all under Mirage - this spec used to register every one of these routes on its own server
// instance every test), so nothing else needs registering here.
beforeEach(() => {
  db.workspaces.push(structuredClone(workspaceFixture));
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

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("apply");
  });

  test("validates an uploaded file through the mocked API", async () => {
    const result = await submit({ intent: "validateYaml", body: "name: some-task\n" });

    expect(result).toEqual({ ok: true, intent: "validateYaml" });
  });

  test("surfaces a failed validation without throwing", async () => {
    server.use(
      http.post(serviceUrl.workspace.task.postValidateYaml({ workspace: WORKSPACE }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = await submit({ intent: "validateYaml", body: "not: valid: yaml: at all" });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("validateYaml");
  });
});
