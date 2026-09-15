#!/usr/bin/env node
// Boomerang Flow throughput harness. Node 20+, no dependencies. See load/README.md.
//
//   node load/run.mjs --runs 200 --concurrency 20 [--profile inline|dispatch]
//
// Creates (or reuses) one workflow, submits RUNS runs with CONCURRENCY submitters in flight,
// waits for every run to reach a terminal status, and prints submit latency, time to complete,
// throughput and per-status counts. Exits non-zero unless every run succeeded.

const HELP = `Usage: node load/run.mjs [options]

  --url <origin>         service-core origin           (env FLOW_URL, default http://localhost:7700)
  --token <bfg_...>      global API token              (env FLOW_TOKEN, required)
  --workspace <name>     workspace, created if absent  (env WORKSPACE, default load)
  --runs <n>             total submissions             (env RUNS, default 50)
  --concurrency <n>      submitters in flight          (env CONCURRENCY, default 5)
  --profile <p>          inline | dispatch             (env PROFILE, default inline)
  --fanout <n>           parallel setwfproperty tasks  (env FANOUT, default 4)
  --deadline <seconds>   give up waiting for runs      (env DEADLINE, default 900)
  --help                 this text

Profiles:
  inline    start -> setwfproperty -> decision -> setwfproperty x FANOUT -> end. Every task runs
            inside the engine; no dispatcher is needed. Measures the engine itself.
  dispatch  inline plus one 'execute-shell' script task per run (a real dispatcher must claim
            it); needs service-dispatcher against a cluster (docker-compose.kube.yml).`;

const arg = (flag, env, fallback) => {
  const i = process.argv.indexOf(`--${flag}`);
  return i > -1 ? process.argv[i + 1] : (process.env[env] ?? fallback);
};
if (process.argv.includes("--help")) { console.log(HELP); process.exit(0); }

const cfg = {
  url: arg("url", "FLOW_URL", "http://localhost:7700").replace(/\/$/, ""),
  token: arg("token", "FLOW_TOKEN", ""),
  workspace: arg("workspace", "WORKSPACE", "load"),
  runs: Number(arg("runs", "RUNS", 50)),
  concurrency: Number(arg("concurrency", "CONCURRENCY", 5)),
  profile: arg("profile", "PROFILE", "inline"),
  fanout: Number(arg("fanout", "FANOUT", 4)),
  deadline: Number(arg("deadline", "DEADLINE", 900)),
};
if (!cfg.token) { console.error("FLOW_TOKEN (or --token) is required.\n\n" + HELP); process.exit(2); }
if (!["inline", "dispatch"].includes(cfg.profile)) { console.error(`Unknown profile ${cfg.profile}`); process.exit(2); }

// ---------------------------------------------------------------------------------------------
// HTTP: one helper, records every non-2xx.
const errors = [];
async function api(method, path, body) {
  const t0 = performance.now();
  let status = 0, text = "";
  try {
    const res = await fetch(cfg.url + path, {
      method,
      headers: { Authorization: `Bearer ${cfg.token}`, "Content-Type": "application/json", Accept: "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(60_000), // a hung socket must not defeat --deadline
    });
    status = res.status;
    text = await res.text();
  } catch (e) {
    text = String(e?.cause?.code ?? e.message);
  }
  const ms = performance.now() - t0;
  const ok = status >= 200 && status < 300;
  if (!ok) errors.push({ method, path, status, text: text.slice(0, 200) });
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON body: keep text */ }
  return { ok, status, json, text, ms };
}

// ---------------------------------------------------------------------------------------------
// Fixtures: workspace and workflow, both looked up by name and created only when absent.
function workflowDefinition(name) {
  const set = (n, deps, value) => ({
    name: n, type: "setwfproperty", taskRef: "set-result-parameter", dependencies: deps,
    params: [{ name: "output", value: n }, { name: "value", value }],
  });
  const fan = Array.from({ length: cfg.fanout }, (_, i) => `par-${i + 1}`);
  const tasks = [
    { name: "start", type: "start" },
    set("set-1", [{ taskRef: "start" }], "one"),
    { name: "decide", type: "decision", taskRef: "switch", dependencies: [{ taskRef: "set-1" }], params: [{ name: "value", value: "a" }] },
    set("branch-a", [{ taskRef: "decide", decisionCondition: "a" }], "matched"),
    set("branch-default", [{ taskRef: "decide" }], "default"),
    ...fan.map((n) => set(n, [{ taskRef: "branch-a" }], n)),
  ];
  let last = fan.map((n) => ({ taskRef: n }));
  if (cfg.profile === "dispatch") {
    tasks.push({ name: "shell-1", type: "script", taskRef: "execute-shell", dependencies: last,
      params: [{ name: "shell", value: "sh" }, { name: "script", value: "echo load" }] });
    last = [{ taskRef: "shell-1" }];
  }
  tasks.push({ name: "end", type: "end", dependencies: last });
  return { name, displayName: name, description: `load harness (${cfg.profile}, fanout ${cfg.fanout})`, tasks };
}
// Tasks the engine actually executes per run: everything except start, end and the unmatched
// decision branch (branch-default never queues).
const executedTasksPerRun = () => workflowDefinition("x").tasks.length - 3;

async function ensureFixtures() {
  const ws = await api("GET", `/api/v2/workspace/${cfg.workspace}`);
  if (!ws.ok) {
    errors.pop(); // an absent workspace is expected on first use
    const me = await api("GET", "/api/v2/profile");
    if (!me.ok) errors.pop(); // a global token has no profile; owner-less create is fine for it
    const members = me.ok && me.json?.id ? [{ id: me.json.id, email: me.json.email, role: "owner" }] : undefined;
    const created = await api("POST", "/api/v2/workspace", { name: cfg.workspace, displayName: cfg.workspace, members });
    if (!created.ok) throw new Error(`create workspace failed: ${created.status} ${created.text}`);
    console.log(`created workspace ${cfg.workspace}`);
  }
  const name = `load-${cfg.profile}-fanout${cfg.fanout}`;
  let wf = await api("GET", `/api/v2/workspace/${cfg.workspace}/workflow/${name}`);
  if (!wf.ok) {
    errors.pop();
    wf = await api("POST", `/api/v2/workspace/${cfg.workspace}/workflow`, workflowDefinition(name));
    if (!wf.ok) throw new Error(`create workflow failed: ${wf.status} ${wf.text}`);
    console.log(`created workflow ${name}`);
  }
  return { name: wf.json.name, version: wf.json.version };
}

// ---------------------------------------------------------------------------------------------
// Submit phase: CONCURRENCY workers pull from one counter until RUNS submissions are done.
async function submitAll(wf) {
  const submits = []; // { id, latencyMs, submittedAt, status }
  let next = 0;
  async function worker() {
    while (next < cfg.runs) {
      next++;
      const t = Date.now();
      const r = await api("POST", `/api/v2/workspace/${cfg.workspace}/workflow/${wf.name}/submit`, {});
      submits.push({ id: r.ok ? r.json.id : null, latencyMs: r.ms, submittedAt: t, status: r.status });
      if (submits.length % 50 === 0) console.log(`  submitted ${submits.length}/${cfg.runs}`);
    }
  }
  await Promise.all(Array.from({ length: cfg.concurrency }, worker));
  return submits;
}

// ---------------------------------------------------------------------------------------------
// Wait phase: one list query per 100 outstanding ids every second. A run is done when its status
// is terminal - what the API reports as the outcome, and the last thing that changes about a run.
const TERMINAL = new Set(["succeeded", "failed", "invalid", "skipped", "cancelled", "timedout"]);
async function waitForRuns(submits) {
  const runs = new Map(submits.filter((s) => s.id).map((s) => [s.id, { submittedAt: s.submittedAt }]));
  const outstanding = () => [...runs].filter(([, r]) => !r.completedAt).map(([id]) => id);
  const deadline = Date.now() + cfg.deadline * 1000;
  let lastLog = 0;
  let perRun = false; // fallback when the server ignores the workflowruns= filter
  while (outstanding().length && Date.now() < deadline) {
    const ids = outstanding();
    const pages = perRun ? ids.map((id) => [id]) : Array.from({ length: Math.ceil(ids.length / 100) }, (_, i) => ids.slice(i * 100, i * 100 + 100));
    for (const chunk of pages) {
      const r = perRun
        ? await api("GET", `/api/v2/workspace/${cfg.workspace}/workflowrun/${chunk[0]}?withTasks=false`)
        : await api("GET", `/api/v2/workspace/${cfg.workspace}/workflowrun/query?workflowruns=${chunk.join(",")}&limit=${chunk.length}&page=0`);
      const seenAt = Date.now();
      const page = perRun ? [r.json].filter(Boolean) : (r.json?.content ?? []);
      if (!perRun && page.some((run) => !runs.has(run.id))) {
        console.log("  server ignores workflowruns=; polling each run with GET instead (older build)");
        perRun = true;
        break;
      }
      for (const run of page) {
        const rec = runs.get(run.id);
        if (!rec) continue;
        if (TERMINAL.has(run.status) && !rec.completedAt) Object.assign(rec, { completedAt: seenAt, status: run.status, duration: run.duration });
      }
    }
    if (Date.now() - lastLog > 5000) {
      lastLog = Date.now();
      const c = [...runs.values()].filter((r) => r.completedAt).length;
      console.log(`  completed ${c}/${runs.size}`);
    }
    await new Promise((res) => setTimeout(res, 1000));
  }
  return runs;
}

// ---------------------------------------------------------------------------------------------
const pct = (xs, p) => { if (!xs.length) return NaN; const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.ceil(p * s.length) - 1)]; };
const row = (label, xs) => `${label.padEnd(26)} p50 ${pct(xs, .5).toFixed(0)}ms  p95 ${pct(xs, .95).toFixed(0)}ms  p99 ${pct(xs, .99).toFixed(0)}ms  max ${Math.max(...xs).toFixed(0)}ms`;

async function main() {
  console.log(`flow ${cfg.url}  workspace ${cfg.workspace}  profile ${cfg.profile}  runs ${cfg.runs}  concurrency ${cfg.concurrency}  fanout ${cfg.fanout}`);
  const wf = await ensureFixtures();
  console.log(`workflow ${wf.name} v${wf.version}`);

  const t0 = Date.now();
  const submits = await submitAll(wf);
  const tSubmitted = Date.now();
  const runs = await waitForRuns(submits);

  const submitOk = submits.filter((s) => s.id);
  const submitS = (tSubmitted - t0) / 1000;
  const all = [...runs.values()];
  const completed = all.filter((r) => r.completedAt);
  const lastCompletedAt = Math.max(t0, ...completed.map((r) => r.completedAt));
  const completeS = (lastCompletedAt - t0) / 1000; // wall clock from first submit to last terminal status
  const byStatus = {};
  for (const r of completed) byStatus[r.status] = (byStatus[r.status] ?? 0) + 1;

  console.log("\n=== results ===");
  console.log(`submitted ${submitOk.length}/${cfg.runs} ok, ${submits.length - submitOk.length} submit errors, submit phase ${submitS.toFixed(1)}s (${(submitOk.length / submitS).toFixed(1)} submits/s)`);
  console.log(row("submit latency", submits.map((s) => s.latencyMs)));
  if (completed.length) console.log(row("time to complete (client)", completed.map((r) => r.completedAt - r.submittedAt)));
  if (completed.length) console.log(row("run duration (server)", completed.map((r) => r.duration ?? 0).filter((d) => d > 0)));
  console.log(`completed ${completed.length} runs in ${completeS.toFixed(1)}s  ${(completed.length / completeS * 60).toFixed(1)} runs/min  ${(completed.length * executedTasksPerRun() / completeS).toFixed(1)} task runs/s`);
  const notCompleted = all.length - completed.length;
  console.log(`per status: ${JSON.stringify(byStatus)}  not complete by deadline: ${notCompleted}`);
  if (notCompleted) console.log(`  incomplete ids: ${[...runs].filter(([, r]) => !r.completedAt).slice(0, 10).map(([id]) => id).join(", ")}`);
  if (errors.length) {
    console.log(`non-2xx responses: ${errors.length}`);
    const grouped = {};
    for (const e of errors) { const k = `${e.method} ${e.path.split("?")[0]} -> ${e.status}`; grouped[k] = grouped[k] ?? { n: 0, sample: e.text }; grouped[k].n++; }
    for (const [k, v] of Object.entries(grouped)) console.log(`  ${v.n}x ${k}  ${v.sample}`);
  }
  const allSucceeded = notCompleted === 0 && submitOk.length === cfg.runs && (byStatus.succeeded ?? 0) === cfg.runs;
  process.exit(allSucceeded ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
