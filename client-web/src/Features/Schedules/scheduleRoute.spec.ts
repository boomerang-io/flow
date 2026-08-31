import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { db } from "ApiServer/msw/db";
import { serviceUrl } from "Config/servicesConfig";
import { scheduleAction, SCHEDULE_INTENTS } from "./scheduleRoute";

const WORKSPACE = "ibm-services-engineering";

// Route-action test pattern - see the "CreateWorkflow --- action" block in
// Components/CreateWorkflow/CreateWorkflow.spec.tsx: build the same Request a useFetcher()
// submit produces and call the action directly. serverFetch's axios calls resolve against the
// shared MSW node server exactly as the loaders' do.
function buildRequest(fields: Record<string, string>) {
  return new Request(`http://localhost/${WORKSPACE}/schedules`, {
    method: "post",
    body: new URLSearchParams(fields),
  });
}

const schedule = {
  name: "Nightly Backup",
  type: "cron",
  cronSchedule: "0 0 * * *",
  timezone: "UTC",
  labels: { level: "important" },
  params: [],
  workflowRef: "nightly-backup-workflow",
};

describe("scheduleRoute --- scheduleAction", () => {
  test("createSchedule POSTs the JSON schedule payload", async () => {
    let createdBody: any;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json({ ...createdBody, id: "new-schedule" }, { status: 201 });
      }),
    );

    const result = await scheduleAction({
      request: buildRequest({ intent: "createSchedule", schedule: JSON.stringify(schedule) }),
      params: { workspace: WORKSPACE },
    });

    expect(result).toEqual({ ok: true, intent: "createSchedule" });
    // The labels Record survives the formData JSON round trip - the labelStringsToRecord
    // behaviour pinned in the component specs must still reach the wire through the action.
    expect(createdBody).toMatchObject({ name: "Nightly Backup", labels: { level: "important" } });
  });

  test("updateSchedule PUTs the JSON schedule payload", async () => {
    let updatedBody: any;
    server.use(
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), async ({ request }) => {
        updatedBody = await request.json();
        return HttpResponse.json(updatedBody);
      }),
    );

    const result = await scheduleAction({
      request: buildRequest({
        intent: "updateSchedule",
        schedule: JSON.stringify({ ...schedule, id: "sched-1", description: "edited" }),
      }),
      params: { workspace: WORKSPACE },
    });

    expect(result).toEqual({ ok: true, intent: "updateSchedule" });
    expect(updatedBody).toMatchObject({ id: "sched-1", description: "edited" });
  });

  test("toggleSchedule PUTs the flipped-status schedule the caller built", async () => {
    let updatedBody: any;
    server.use(
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), async ({ request }) => {
        updatedBody = await request.json();
        return HttpResponse.json(updatedBody);
      }),
    );

    const result = await scheduleAction({
      request: buildRequest({
        intent: "toggleSchedule",
        schedule: JSON.stringify({ ...schedule, id: "sched-1", status: "inactive" }),
      }),
      params: { workspace: WORKSPACE },
    });

    expect(result).toEqual({ ok: true, intent: "toggleSchedule" });
    expect(updatedBody).toMatchObject({ id: "sched-1", status: "inactive" });
  });

  test("deleteSchedule DELETEs by id through the shared handler", async () => {
    // The shared MSW handler removes from db.schedules - assert against that rather than a
    // bespoke override, proving the id lands in the URL the real API shape expects.
    const before = db.schedules.length;
    expect(db.schedules.some((s) => s.id === "61d6286bc570b75ec2b47884")).toBe(true);

    const result = await scheduleAction({
      request: buildRequest({ intent: "deleteSchedule", id: "61d6286bc570b75ec2b47884" }),
      params: { workspace: WORKSPACE },
    });

    expect(result).toEqual({ ok: true, intent: "deleteSchedule" });
    expect(db.schedules.length).toBe(before - 1);
    expect(db.schedules.some((s) => s.id === "61d6286bc570b75ec2b47884")).toBe(false);
  });

  test("a failed write surfaces as ok:false with its own intent, not a throw", async () => {
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = await scheduleAction({
      request: buildRequest({ intent: "createSchedule", schedule: JSON.stringify(schedule) }),
      params: { workspace: WORKSPACE },
    });

    expect(result).toEqual({ ok: false, intent: "createSchedule" });
  });

  // Load-bearing guard, not tidiness - same trap editorRoute.ts/tokenRoute.ts document: an
  // unrecognised intent falling into a write branch would JSON.parse(String(null)) and fire a
  // destructive request. It must refuse without touching the API.
  test("an unknown intent is refused without any API call", async () => {
    let called = 0;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), () => {
        called += 1;
        return HttpResponse.json({});
      }),
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), () => {
        called += 1;
        return HttpResponse.json({});
      }),
    );

    const result = await scheduleAction({
      request: buildRequest({ intent: "create" }), // a TOKEN_INTENTS value, not a schedule one
      params: { workspace: WORKSPACE },
    });

    expect(result).toMatchObject({ ok: false, intent: "unknown" });
    expect(called).toBe(0);
  });

  test("SCHEDULE_INTENTS names exactly the four write intents, namespaced against the editor route", () => {
    // The editor route's action namespace already contains bare "create"/"delete"
    // (Components/TokenSection/tokenRoute.ts TOKEN_INTENTS) and "createRevision" - the schedule
    // intents must never collide with them.
    expect([...SCHEDULE_INTENTS]).toEqual(["createSchedule", "updateSchedule", "toggleSchedule", "deleteSchedule"]);
  });
});
