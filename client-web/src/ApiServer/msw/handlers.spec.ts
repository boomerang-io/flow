// Proof that the MSW handler layer works, driven with plain `fetch()` calls rather than any RTL
// helper. The server lifecycle (listen/resetHandlers+resetDb/close) is centralised in
// src/setupTests.tsx (every spec file goes through it as vitest's global setupFile) - this file
// used to run its own listen()/close() independently, back when it was authored alongside Mirage
// as proof the layer worked in isolation before anything was wired in; now that the switchover is
// done, a second listen() on the same process-wide server throws ("cannot configure an already
// enabled network"), so this relies on the shared lifecycle like every other spec. Covers a
// representative slice of the surface, including the three cases that directly demonstrate the
// bugs this layer deliberately does not carry over from Mirage (see the module doc in
// ./handlers.ts for the full divergence list):
//   - the workspace-list route (a literal path) resolving ahead of the single-workspace route
//     (a :workspace param) at the same depth, rather than being shadowed by it
//   - PATCH .../labels actually persisting instead of throwing on a plain (non-ORM) record
//   - validate-name rejecting only a real collision, not every request
import { describe, expect, it } from "vitest";
import * as fixtures from "ApiServer/fixtures";

// MSW resolves a relative handler pattern like "/api/profile" against the current document's
// origin (jsdom defaults to http://localhost:3000, not bare http://localhost) - resolve requests
// against that same origin rather than a hardcoded one, so this stays correct regardless of what
// that default happens to be.
function apiUrl(path: string): string {
  return new URL(path, window.location.href).toString();
}

describe("MSW handlers", () => {
  it("serves static fixtures directly, e.g. the profile", async () => {
    const response = await fetch(apiUrl("/api/profile"));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual(fixtures.profile);
  });

  it("lists workspaces from the same fixture the Mirage server uses", async () => {
    const response = await fetch(apiUrl("/api/workspace/query"));
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.content).toEqual(fixtures.workspaces.content);
  });

  it("resolves a single workspace by name via the :workspace param route, without the literal /workspace/query route shadowing it", async () => {
    const [first] = fixtures.workspaces.content;
    const response = await fetch(apiUrl(`/api/workspace/${first.name}`));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ id: first.id, name: first.name });
  });

  it("returns the workspace calendar fixture, not a 404 schedule lookup for a schedule literally named 'calendars'", async () => {
    const response = await fetch(apiUrl(`/api/workspace/${fixtures.workspaces.content[0].name}/schedule/calendars`));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual(fixtures.workflowCalendar);
  });

  it("only rejects validate-name on a real collision, unlike the previous Mirage bug that always returned 422", async () => {
    const existingName = fixtures.workspaces.content[0].name;

    const collision = await fetch(apiUrl("/api/workspace/validate-name"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: existingName }),
    });
    expect(collision.status).toBe(422);

    const available = await fetch(apiUrl("/api/workspace/validate-name"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "a-brand-new-workspace-name" }),
    });
    expect(available.status).toBe(200);
  });

  it("persists a PATCH to /labels instead of throwing on a plain record", async () => {
    const workspace = fixtures.workspaces.content[0];
    const response = await fetch(apiUrl(`/api/workspace/${workspace.name}/labels`), {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ team: "platform" }),
    });
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ labels: { team: "platform" } });

    const refetched = await fetch(apiUrl(`/api/workspace/${workspace.name}`));
    await expect(refetched.json()).resolves.toMatchObject({ labels: { team: "platform" } });
  });

  it("creates a global parameter and lists it back", async () => {
    const created = await fetch(apiUrl("/api/parameters"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "a-new-param", value: "hello" }),
    });
    expect(created.status).toBe(201);

    const list = await fetch(apiUrl("/api/parameters"));
    const params = await list.json();
    expect(params).toEqual(expect.arrayContaining([expect.objectContaining({ name: "a-new-param", value: "hello" })]));
  });

  it("lists tasks from a literal /task/query route without the :name param route shadowing it", async () => {
    const response = await fetch(apiUrl("/api/task/query"));
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.content).toEqual(fixtures.task.content);
  });

  it("resets in-memory mutations between tests", async () => {
    const list = await fetch(apiUrl("/api/parameters"));
    const params = await list.json();
    expect(params.find((param: { name: string }) => param.name === "a-new-param")).toBeUndefined();
  });
});
