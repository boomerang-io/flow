import React from "react";
import { http, HttpResponse } from "msw";
import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
import { server } from "ApiServer/msw/node";
import { db } from "ApiServer/msw/db";
import { resourceRoute } from "Config/resourceRoutes";
import { serviceUrl } from "Config/servicesConfig";
import type { Task } from "Types";
import TaskUpdateModal from "./TaskUpdateModal";

// The task the shared db seeds from the fixtures - used as both the node's pinned version and
// (bumped) the latest version on offer.
const taskName = "execute-advanced-http-call";

function findFixtureTask(): Task {
  // db.ts seeds its collections as loosely-typed records off the fixtures; narrow through
  // unknown for the component's prop type, the same shape the real /res/task loader serves.
  const task = db.tasks.find((t: { name?: string }) => t.name === taskName);
  if (!task) throw new Error(`fixture task ${taskName} missing`);
  return task as unknown as Task;
}

const node = {
  name: "Execute Advanced HTTP Call 1",
  taskRef: taskName,
  taskVersion: 4,
  upgradesAvailable: true,
  params: [],
  results: [],
};

// The slice's goal made assertable (same pattern as WorkspaceCreateContent.spec.tsx): the
// browser must never call /api/* - the modal's current-version read goes through the
// same-origin /res/task/:name resource route, and the direct GET /api/v2/task/{name} it used to
// make is force-failed, so a regression back to a direct browser call fails these tests.
beforeEach(() => {
  server.use(http.get(serviceUrl.task.getTask({ name: ":name" }), () => HttpResponse.error()));
});

describe("TaskUpdateModal --- RTL", () => {
  it("renders both version panes from the resource route (direct /api blocked)", async () => {
    const latest = { ...findFixtureTask(), version: 5 };
    render(
      <TaskUpdateModal
        availableParameters={[]}
        closeModal={vi.fn()}
        latestTaskTemplate={latest}
        node={node}
        onSave={vi.fn()}
      />,
    );

    expect(await screen.findByText("Current version in this workflow")).toBeInTheDocument();
    expect(screen.getByText("Latest version available")).toBeInTheDocument();
    expect(screen.getByText("Version 4")).toBeInTheDocument();
    expect(screen.getByText("Version 5")).toBeInTheDocument();
    expect(screen.getByText("Update task")).toBeInTheDocument();
  });

  it("renders the inline empty state when the resource route reports ok:false", async () => {
    server.use(http.get(resourceRoute.task({ name: ":name" }), () => HttpResponse.json({ ok: false })));
    render(
      <TaskUpdateModal
        availableParameters={[]}
        closeModal={vi.fn()}
        latestTaskTemplate={{ ...findFixtureTask(), version: 5 }}
        node={node}
        onSave={vi.fn()}
      />,
    );

    expect(await screen.findByText("Something's off here")).toBeInTheDocument();
  });
});
