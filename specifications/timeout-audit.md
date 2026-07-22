# Q-124 Timeout Inventory & Invariant Audit (2026-07-22)

**Status:** ✅ Audit complete. Verdict: **the "run timeout ≥ transport timeout of guarded
work" invariant is VIOLATED in 4 of 6 work classes**, holds cleanly in exactly one (agent
long-poll), and is vacuous in one (inline tasks — unbounded beneath the guard). Feeds the
Phase 2B/3 sweep timeout-class design (Q-121/Q-227).

## 1. Key inventory facts

**Run-level guards:**
- Workflow timeout: always set on flow submits (`min(request, team quota)`, fallback 300
  min); **may be unset on engine-direct submits** (= no guard). Enforced by a durable
  JobRunr job at `T*60+5s` (good) plus exact-`T` boundary backstops (inconsistent by up to
  5s).
- Task timeout: from the `boomerang.io/task-timeout` annotation else **0 = none**.
  **BUG:** `DAGUtility:187-199` only applies a revision per-task timeout if it is `<` the
  annotation value — with the annotation absent (0), a positive per-task timeout is
  **always discarded**. Enforcement is the in-memory future (lost on crash).
- Agent-side: `kube.task.timeout=60` min default when TaskRun timeout is 0; Tekton spec
  timeout `T`; watch guard `T+10` min (provisioning grace).
- `sleep` task: `Thread.sleep` with **no bound**; `acquirelock` waits with
  `Integer.MAX_VALUE` attempts (unbounded).

**Transport (the gap):** all three `RestConfig.java` are identical in shape —
`internalRestTemplate` = 60s connect/read (idle-based) but **infinite pool-lease**;
`insecureRestTemplate` / `selfRestTemplate` / `externalRestTemplate` = **NO read timeout
(infinite)**. Engine→agent (`LogClient`) and engine→flow (`WorkflowClient`) ride the
infinite one. The `SOCKET_TIMEOUT/REQUEST_TIMEOUT = Integer.MAX_VALUE` class constants are
dead code — misleading.

**Server/infra:** `spring.mvc.async.request-timeout=600s` on the **agent only**; flow and
engine use Tomcat's **30s** default on their `StreamingResponseBody` log endpoints. Mongo
URIs set no timeouts (socket ∞). Engine long-poll blocks a Tomcat thread 30s per agent per
cycle (2 threads/agent continuously; ~100 agents saturate the default 200).

## 2. Per-work-class verdicts

| Class | Verdict | Failure mode |
| --- | --- | --- |
| A. Agent/Tekton task | **VIOLATED** | Engine guard fires at exactly T while the agent grants T+10 (provisioning grace) → engine reaps healthy in-budget work; with T=0 (common, given the DAGUtility bug) the agent's invisible 60-min default is the only task guard. Queue wait counts against T. |
| B. Engine-inline (sleep, locks…) | **Vacuous / UNBOUNDED beneath** | Auto-ending types get no future; `sleep` holds a pool thread arbitrarily; workflow guard cannot interrupt — the thread wakes later and `end`s against a completed run (zombie completions). |
| C. Workflow overall | **VIOLATED structurally** | Nothing validates workflow T ≥ critical-path Σ task budgets (60+10 per default agent task) — quota-limited workflows routinely reap healthy long tasks. Holds only by configuration accident. |
| D. Log streaming (user→flow→engine→agent→K8s) | **VIOLATED, every leg differently** | Flow/engine 30s async default kills streams; 60s idle cut on the internal leg (quiet tasks die); engine→agent leg infinite (hang risk). The agent's 600s setting proves the problem was hit and fixed on one service of three. |
| E. Agent long-poll | **HOLDS** (only clean chain) | 30s hold < 60s read, 2× margin — but the pair is two unrelated constants in two services. |
| F. Cross-service control calls | **UNBOUNDED-BOTH** (engine→flow, engine→agent) | No transport timeout, no run guard. flow→engine: 60s idle abort under no guard → orphaned-run/duplicate-retry risk. |

## 3. v5 design constraints (for the sweep timeout classes — Q-121/Q-227)

1. **Grace composes downward and is validated at submit**: task-class sweep timeout =
   executor budget + provisioning grace + claim latency; workflow timeout ≥ critical-path
   Σ task guards — reject/clamp at submit; nothing enforces this today.
2. **One durable enforcement mechanism** (the sweep) replacing in-memory futures. Timeout
   classes: *agent-task* (per-run T + grace + ownership metadata), *inline-fast* (small
   hard cap, currently unguarded), *sleep/wait* (durable scheduled resume — never
   `Thread.sleep`; cap at remaining workflow budget), *lock-wait* (bounded attempts),
   *approval/eventwait* (human-paced — excluded from reaping unless a timeout is set).
   Fix the DAGUtility merge bug and the ±5s grace inconsistency while unifying.
3. **Transport timeouts MUST be introduced**: insecure/self/external templates get
   connect ~5–10s / read 30–60s; internal's infinite pool-lease → ~10s; delete the dead
   MAX_VALUE constants. **Streaming gets its own client** (idle-based read ≥ max log quiet
   period, no total cut) and flow/engine async request timeouts raised ≥ the agent's 600s.
   Mongo: `socketTimeoutMS` + `maxTimeMS` on sweep queries so the sweeper can't hang.
4. **Long-poll invariant as a named pair** (hold = read/2, co-located config); move the
   poll to async servlet (DeferredResult) so it stops eating Tomcat threads (capacity
   matters in the merged deployable).
5. **Fencing for stale guards**: retry reuses the same `wfRunId` and completed-run timeout
   jobs still fire — sweep records carry an attempt/fencing epoch so a guard for attempt N
   can never reap attempt N+1 (aligns with Q-129).

Verified-clean: the JobRunr workflow job at `T*60+5s`; fabric8 `Duration.parse(T+"mins")`;
the long-poll chain.
