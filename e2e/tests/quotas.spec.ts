import { expect } from "@playwright/test";
import {
  API_ORIGIN,
  createWorkflow,
  getWorkspace,
  patchWorkspace,
  uniqueName,
  workspaceTest,
  type RestErrorResponse,
  type WorkspaceView,
} from "../support/api";

/*
 * A workspace's monthly run quota, set on the workspace itself (no platform setting changes). With
 * a limit of 2 the third submit is refused with 429 QUOTA_EXCEEDED, and a refused submit creates
 * no run and uses none of the quota: the count stays at 2, and raising the limit to 3 admits
 * exactly one more. Submits use start=false (the runs park at ready), so no dispatcher is needed;
 * the concurrent-run limit (4 by default) leaves room for the three parked runs.
 */

const test = workspaceTest("e2e-quota");

test("a monthly run quota refuses the run over it, and refused submits use none of it", async ({
  request,
  workspace,
}) => {
  const wf = await createWorkflow(request, workspace, uniqueName("e2e-quota-wf"));
  const submit = () =>
    request.post(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${wf.name}/submit?start=false`, { data: {} });
  const runsThisMonth = async () => ((await getWorkspace(request, workspace)) as WorkspaceView).quotas.currentRuns;

  const limited = await patchWorkspace(request, workspace, { quotas: { maxWorkflowRunMonthly: 2 } });
  expect(limited.quotas.maxWorkflowRunMonthly).toBe(2);

  for (let i = 0; i < 2; i++) {
    const res = await submit();
    expect(res.status(), await res.text()).toBe(200);
  }
  for (let i = 0; i < 2; i++) {
    const refused = await submit();
    expect(refused.status(), await refused.text()).toBe(429);
    const body = (await refused.json()) as RestErrorResponse;
    expect(body.reason).toBe("QUOTA_EXCEEDED");
    expect(body.message).toContain("2 / 2");
  }
  expect(await runsThisMonth()).toBe(2);
  const runs = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/query?limit=10`);
  expect(((await runs.json()) as { totalElements: number }).totalElements).toBe(2);

  // Room for one more run means exactly one more.
  await patchWorkspace(request, workspace, { quotas: { maxWorkflowRunMonthly: 3 } });
  expect((await submit()).status()).toBe(200);
  expect((await submit()).status()).toBe(429);
  expect(await runsThisMonth()).toBe(3);
});
