#!/usr/bin/env node
// Boomerang Flow throughput harness. Node 20+, no dependencies. See load/README.md.
//
//   node load/run.mjs --runs 200 --concurrency 20 [--profile inline|dispatch]
//
// Creates (or reuses) one workflow, submits RUNS runs with CONCURRENCY submitters in flight,
// waits for every run to reach phase=finalized, and prints submit latency, time-to-finalize,
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
  dispatch  inline plus one 'sleep' template task per run (a real dispatcher must claim it);
            needs service-dispatcher running against a cluster (docker-compose.kube.yml).`;

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
    name: n, type: "setwfproperty", dependencies: deps,
    params: [{ name: "output", value: n }, { name: "value", value }],
  });
  const fan = Array.from({ length: cfg.fanout }, (_, i) => `par-${i + 1}`);
  const tasks = [
    { name: "start", type: "start" },
    set("set-1", [{ taskRef: "start" }], "one"),
    { name: "decide", type: "decision", dependencies: [{ taskRef: "set-1" }], params: [{ name: "value", value: "a" }] },
    set("branch-a", [{ taskRef: "decide", decisionCondition: "a" }], "matched"),
    set("branch-default", [{ taskRef: "decide" }], "default"),
    ...fan.map((n) => set(n, [{ taskRef: "branch-a" }], n)),
  ];
  let last = fan.map((n) => ({ taskRef: n }));
  if (cfg.profile === "dispatch") {
    tasks.push({ name: "sleep-1", type: "template", taskRef: "sleep", dependencies: last, params: [{ name: "duration", value: "1" }] });
    last = [{ taskRef: "sleep-1" }];
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
  return { id: wf.json.id, name: wf.json.name };
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
// Wait phase: one list query per 100 outstanding ids every second, until all are finalized.
const TERMINAL = new Set(["succeeded", "failed", "invalid", "skipped", "cancelled", "timedout"]);
async function waitForRuns(submits) {
  const pending = new Map(submits.filter((s) => s.id).map((s) => [s.id, s]));
  const done = new Map(); // id -> run + { finalizedAt, submittedAt }
  const deadline = Date.now() + cfg.deadline * 1000;
  let lastLog = 0;
  while (pending.size && Date.now() < deadline) {
    const ids = [...pending.keys()];
    for (let i = 0; i < ids.length; i += 100) {
      const chunk = ids.slice(i, i + 100);
      const q = `workflowruns=${chunk.join(",")}&limit=${chunk.length}&page=0`;
      const r = await api("GET", `/api/v2/workspace/${cfg.workspace}/workflowrun/query?${q}`);
      const seenAt = Date.now();
      for (const run of r.json?.content ?? []) {
        const finished = run.phase === "finalized" || (run.phase === "completed" && TERMINAL.has(run.status));
        if (finished && pending.has(run.id)) {
          done.set(run.id, { ...run, finalizedAt: seenAt, submittedAt: pending.get(run.id).submittedAt });
          pending.delete(run.id);
        }
      }
    }
    if (Date.now() - lastLog > 5000) { lastLog = Date.now(); console.log(`  finalized ${done.size}/${done.size + pending.size}`); }
    await new Promise((res) => setTimeout(res, 1000));
  }
  return { done, stuck: [...pending.keys()] };
}

// ---------------------------------------------------------------------------------------------
const pct = (xs, p) => { if (!xs.length) return NaN; const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.ceil(p * s.length) - 1)]; };
const row = (label, xs) => `${label.padEnd(26)} p50 ${pct(xs, .5).toFixed(0)}ms  p95 ${pct(xs, .95).toFixed(0)}ms  p99 ${pct(xs, .99).toFixed(0)}ms  max ${Math.max(...xs).toFixed(0)}ms`;

async function main() {
  console.log(`flow ${cfg.url}  workspace ${cfg.workspace}  profile ${cfg.profile}  runs ${cfg.runs}  concurrency ${cfg.concurrency}  fanout ${cfg.fanout}`);
  const wf = await ensureFixtures();
  console.log(`workflow ${wf.name} (${wf.id})`);

  const t0 = Date.now();
  const submits = await submitAll(wf);
  const tSubmitted = Date.now();
  const { done, stuck } = await waitForRuns(submits);
  const t1 = Date.now();

  const submitOk = submits.filter((s) => s.id);
  const submitS = (tSubmitted - t0) / 1000;
  const wallS = (t1 - t0) / 1000;
  const finalizeMs = [...done.values()].map((d) => d.finalizedAt - d.submittedAt);
  const serverDurationMs = [...done.values()].map((d) => d.duration ?? 0).filter((d) => d > 0);
  const byStatus = {};
  for (const d of done.values()) byStatus[d.status] = (byStatus[d.status] ?? 0) + 1;

  console.log("\n=== results ===");
  console.log(`submitted ${submitOk.length}/${cfg.runs} ok, ${submits.length - submitOk.length} submit errors, submit phase ${submitS.toFixed(1)}s (${(submitOk.length / submitS).toFixed(1)} submits/s)`);
  console.log(row("submit latency", submits.map((s) => s.latencyMs)));
  if (finalizeMs.length) console.log(row("time to finalize (client)", finalizeMs));
  if (serverDurationMs.length) console.log(row("run duration (server)", serverDurationMs));
  console.log(`wall clock ${wallS.toFixed(1)}s  completed ${done.size} runs  ${(done.size / wallS * 60).toFixed(1)} runs/min  ${(done.size * executedTasksPerRun() / wallS).toFixed(1)} task runs/s`);
  console.log(`per status: ${JSON.stringify(byStatus)}  stuck (not finalized by deadline): ${stuck.length}`);
  if (stuck.length) console.log(`  stuck ids: ${stuck.slice(0, 10).join(", ")}${stuck.length > 10 ? " ..." : ""}`);
  if (errors.length) {
    console.log(`non-2xx responses: ${errors.length}`);
    const grouped = {};
    for (const e of errors) { const k = `${e.method} ${e.path.split("?")[0]} -> ${e.status}`; grouped[k] = grouped[k] ?? { n: 0, sample: e.text }; grouped[k].n++; }
    for (const [k, v] of Object.entries(grouped)) console.log(`  ${v.n}x ${k}  ${v.sample}`);
  }
  const allSucceeded = stuck.length === 0 && submitOk.length === cfg.runs && (byStatus.succeeded ?? 0) === cfg.runs;
  process.exit(allSucceeded ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
