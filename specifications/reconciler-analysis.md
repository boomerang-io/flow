# Q-117 Reconciler Model Analysis — Materialise-all vs Create-on-walk (2026-07-22)

**Status:** ✅ **RULED (2026-07-22): materialise-all + Option C extensions confirmed**
(supersede generations + placeholder-expand fan-out), and **reference + enforced
retention** for definitions. The create-on-walk analysis (§1 Tekton, §2 column B) is
**deliberately preserved** for future reference per maintainer instruction — if Q-117 is
ever revisited, price in the consumer-facing invariants noted in §5.10.
Includes the folded Q-113 sub-question (revision reference vs snapshot).

## 1. Industry findings (cited)

| Engine | Model | Key lessons for Flow |
| --- | --- | --- |
| **Tekton** | Create-on-walk; level-triggered reconcile as a **pure function** of (snapshotted spec, child states); TaskRuns created only when the frontier reaches them | Snapshots the resolved `pipelineSpec` INTO the run (immune to definition edits/deletes). **TEP-0100**: embedding child *statuses* in the parent blew the etcd ceiling → replaced with `childReferences`. Rule: *snapshot the definition; never inline child execution state.* Skipped nodes have no TaskRun but ARE explicitly recorded (`status.skippedTasks` with typed reasons) — absence needs a ledger. Retries reset the SAME TaskRun with attempts archived (`retriesStatus`). |
| **Temporal** | Event-sourced replay; no materialisation | **REJECT** for a data-defined DAG: you'd event-source trivially-materialisable state and inherit history limits (51,200 events/50MB), replay cost, and version-pinning discipline that only pays when *users* write orchestration code. Instructive: durable timers as swept server-side state (validates L-02); typed queues (L-03/L-06); append-only audit beside working state. |
| **Airflow** | **Materialise-all**: every task = a TaskInstance row at DagRun creation; scheduler re-gates over rows each cycle | Flow's closest cousin — proves per-node-row materialise-all at scale. **Dynamic task mapping (AIP-42)**: runtime-unknown fan-out = ONE placeholder row (`map_index=-1`) expanded to N rows when the upstream result lands; cap `max_map_length=1024`; zero-expansion → skipped. Retry mutates the same row — and losing prior attempts hurt enough that 2.10 added `TaskInstanceHistory` (keep attempt history!). Airflow 3: version-stamped reference per run, snapshot opt-in. |
| **Argo** | Create-on-walk **into one object** (`status.nodes` in the CR) | The cautionary tale: etcd cap → compression → **full node-map offload to Postgres** (breaks kubectl, couples to DB, lost rows = unreadable workflows). They rediscovered normalized child records with worse ergonomics. Even Argo freezes referenced templates into the run (`storedWorkflowTemplateSpec`). |
| **Prefect / Dagster** | Fully dynamic / plan-snapshot | Dagster: content-hash-deduplicated job+plan snapshots per run — functionally "an immutable revision collection runs point into" (which Flow already has). |

**Pattern summary:** nobody inlines child execution state in the parent and survives; everybody pins or snapshots referenced definitions per run; create-on-walk always needs an explicit skip ledger (absence is ambiguous); dynamic fan-out has a proven answer *inside* materialise-all (placeholder+expand).

## 2. Options for Flow

Anchors: `DAGUtility.createTaskList` (L100–230) already (a) pre-creates one TaskRun per node, (b) **snapshots the resolved task-template spec + dependency edges into each TaskRun**, and (c) is idempotent create-missing via name lookup. TaskRuns are separate Mongo documents — **the Argo failure mode structurally cannot occur** (16MB is per-document; node count never threatens it; only param/result payloads do, identically in every option).

| Criterion | A: materialise-all (today + re-gate + supersede) | B: create-on-walk | C: A + on-demand supersede generations + placeholder-expand fan-out |
| --- | --- | --- | --- |
| Reconciler | Pure function over (pinned definition, one batched fetch); smallest delta — `createTaskList` is already the create-missing half | New frontier logic + skip ledger + rewrite of every one-TaskRun-per-node consumer (UI included); largest delta | = A, plus an expansion step at gate time |
| Q-114 skipped-vs-missing | Answered for free (explicit `skipped` docs) | Reopens the problem; makes the `canExecuteTask` missing-dep=satisfied bug **lethal** | = A |
| Q-115 supersede | Needs a live-generation discriminator (see §3) | Same need, or delete-and-recreate (loses history — Airflow's regret) | = A; new generations copy spec from the superseded doc |
| Dynamic fan-out (AI tasks) | Needs placeholder+expand anyway | Native (its one strong card) | **By design** (Airflow precedent, capped) |
| Migration risk | Near zero | High | Low (additive over A) |

## 3. RECOMMENDATION (pending ruling)

**Keep materialise-all; adopt Option C's two extensions.** Do not move to create-on-walk.

Consequences if ruled:
- **Supersede model (Q-115)**: `superseded` is an **orthogonal field, never a RunStatus**
  (consistent with pause): `attempt` int (gen 0 at queue), `supersededAt`/`supersededBy`.
  Invariant: **at most one live TaskRun per (workflowRunRef, name)** — partial unique
  index where `supersededAt` absent. Every `findFirstByNameAndWorkflowRunRef` site
  becomes find-live-by-name (deterministic by index). Superseded docs retained as attempt
  history (pruning policy separate).
- **`supersedeFrom(nodeRef)`**: supersede the transitive downstream closure —
  **including `skipped` docs** (a re-run decision may take a different branch) → set
  RUNNING → `reconcile()` re-creates missing live generations at `attempt = prevMax+1`,
  **copying spec from the superseded generation** (never re-resolving templates).
- **Q-114 hardening (sequence FIRST)**: invert `canExecuteTask` — a missing dependency
  TaskRun is a broken invariant (log-and-reconcile), never "satisfied".
- **Dynamic fan-out**: placeholder node expanded at gate time, hard cap, zero-expansion →
  `skipped`; unique index accommodates `(name, mapIndex)`.

## 4. Reference vs snapshot (Q-113 folded) — and a data-loss bug

**🔴 BUG (independent of Q-117):** `engine WorkflowService.delete` (L594–603)
unconditionally cascades actions → task_runs → workflow_runs → revisions → workflow with
**no in-flight-run guard** — running executions (including agent-side Tekton work) are
silently orphaned mid-flight. `TaskService.delete` (L355–358) similarly deletes all task
template revisions unguarded; unpinned workflow tasks resolve **latest** at
materialisation — harmless today only because the spec is snapshotted into the TaskRun at
queue time, but lethal to any v5 path that re-materialises by re-resolving.

> **Maintainer direction (2026-07-22) — WorkflowWatcher handles the delete scenario.**
> Delete becomes a transition, the watcher its executor (CHEER's
> `cancelOrphanedWorkflow` pattern): (1) `delete` = **tombstone only** (soft-delete the
> Workflow; new submits/schedules/triggers stop immediately; nothing destroyed);
> (2) the **watcher sweep** sees unfinalised runs of tombstoned workflows and drives each
> through the normal cancel path (TaskRuns cancelled, agents notified so Tekton work
> terminates, gates released) — level-triggered, idempotent, crash-mid-delete self-heals;
> (3) **hard pruning is a separate retention sweep** that physically removes
> runs/revisions only when all referencing runs are finalised — the Q-117 retention rule
> enforced by design, not by a guard; (4) the watcher keeps an orphan backstop (run
> references a genuinely-missing revision → fail/cancel with clear status, never wedge).
> Delete thereby stops being a special case — one more lifecycle transition on the same
> claim/CAS + sweep machinery as timeout/pause/crash recovery (L-09: one repair path).

**Recommendation: reference + enforced retention. Do not embed a DAG copy in every run.**
Flow is already a partial-snapshot system (per-TaskRun spec + edges); revisions are
immutable-in-practice and content-addressed by the revision collection (the Dagster
shape). Tekton/Argo snapshot into the run because their definitions are mutable/deletable
K8s resources — Mongo revisions need not be. Phase 3 enforcement: (1) revision
immutability becomes law; (2) **deletion guard or soft-delete** (refuse/finalise-first
while unfinalised runs reference revisions; tombstone + retain otherwise) — the current
cascade is a data-loss bug regardless of Q-117; (3) supersede re-creation copies spec,
never re-resolves; task-template delete guarded for versions referenced by unfinalised
runs.

## 5. Open design points for the Phase 3 spec

1. Supersede representation details + legacy-data check for the partial unique index.
2. Skip docs in the supersede closure; deterministic re-skip on unchanged decisions.
3. Attempt-history retention/pruning (TTL vs archive collection).
4. `canExecuteTask` inversion + reconcile invariant asserts — before any supersede code.
5. Dynamic fan-out contract (placeholder semantics, cap, `node[i]` result refs).
6. `runworkflow` under supersede/pause (ties to Q-116 cascade design).
7. Dependencies source of truth: revision authoritative; `TaskRun.dependencies` = cache.
8. Deletion/retention semantics; definition of "finalised" for retention.
9. Payload discipline: result/param claim-check threshold (Temporal 2MB precedent);
   index-covered claim/sweep queries (count-don't-load stays true).
10. UI contract: "render the full run graph from TaskRuns" recorded as a consumer-facing
    invariant of materialise-all.
