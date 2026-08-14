# T4 — DD-02 Merge Execution Plan

**Status: 🔵 ACTIVE (2026-08-14).** The execution sequence for the flow/engine merge.
Architecture record: `consolidation-proposal.md` (approved). Epic backlog: `gap-register.md` §3.
This document records the *sequence, amendments, and progress* — it does not restate the proposal.

**Gate check:** the merge-train precondition ("E8–E10 starts only when E4–E7 green") is satisfied
(E4/E5/E6/E7 shipped). Standing constraints: DD-02 falsifiability F1–F5 stays live (F1 = the
pre-cutover load test is an abort gate); the E8→E10 timebox (deployment merge within 1–2 releases
of boundaries landing, else Q-211 re-opens).

## Amendments to the recorded plan (maintainer-ruled 2026-08-14)

| # | Amendment |
|---|---|
| AM-1 | **No Spring Modulith, no ArchUnit.** Module boundaries are a **principle/convention**, not a framework: single Maven module (`service-core`), flat feature packages under one root — the ARCHIE `service-core` convention (`com.ey.archie.{workspace,agent,library,…}` → ours `io.boomerang.{common,core,workspace,workflow,engine,dispatcher,schedule,event,integrations,api}`). Modulith's other features are redundant here (we have our own outbox; mode gating is plain `@Conditional` and never was a Modulith feature). This amends DD-02's *mechanism* only — one deployable + modes + module boundaries stands. F5 falsifiability is thereby moot. |
| AM-2 | **H3 (dual-serve v1+v2 agent protocol, retire at major) is moot** — `/api/v1/agent` was dropped outright at E7-4 (no v4 adoption); the dispatcher protocol replaced it directly. |
| AM-3 | Proposal step 8's protocol-v2 epoch/lease-renewal did **not** ship — worker leases + fencing are deferred post-merge until a real consumer (lease-reap sweep) exists. I6's "dedicated wire DTOs" overruled: the worker receives the plain run models. |
| AM-4 | E9's pre-filled G2 (`parentRef` + `createdByTaskRunRef`) **conflicts with the earlier ruling** that dropped run-creation dedup fields — at E9's G2 review, propose lineage via the existing `initiatedByRef` + `trigger` instead. |
| AM-5 | The engine-mode static token (Q-207 `flow.security.token`) exists as `flow.dispatcher.token` + `DispatcherAuthFilter` — E8/E10 reuse that filter; the first-class `bfd` token is post-merge (gap-register A3, check ARCHIE first). |

## Sequence

### E8 — boundaries + `service-core` (in progress)
- **E8.0 — prerequisites** ✅ (2026-08-14): J7 done (flattened `WorkflowSchedule` + `WorkflowTemplate`;
  `TaskRun` was E7-2); H4 dedup subset done (`Config`, `WorkflowToken`, dup `KeyValuePair`, dup
  `TriggerConditionOperation` deleted; engine `ParameterUtil` superset-merged into common — the
  erasure-clashing `List<AbstractParam>` overload renamed `abstractParamToRunParam`).
  **Discovered residue:** four flow-local model-extends-entity cases remain (`core/model/User`,
  `UserProfile`, `Setting`; `workflow/model/Action`) — within-module inheritance, flattened when
  their owning pieces restructure (`Action` matters at J3, the single Action owner).
- **E8.1** — `service-flow` → `service-core` git mv (rename-only commit; modifications in
  follow-up commits per the repo git rule), parent pom + CI updates (DD-05).
- **E8.2+** — engine code moves in, module-by-module per the nine-module layout; H5 cycles
  C1–C6; controllers into owning modules then `@ConditionalOnFlowMode` (one gate per module
  root) + per-mode boot tests (H6); J2–J6 restructurings (H8); A5 property unification;
  **E7-5** persisted `agentRef`→`dispatcherRef` / `agents`→`dispatchers` + loader migration
  (a G2 item — present before building); engine composition stays independently bootable.
- **Gates:** G1 = relocation-only for `DAGUtility`/`TaskExecutionService` (zero semantic change;
  review verifies move-only). G2 = the E7-5 rename migration (the register's "None" pre-dates
  pulling E7-5 in).

### E9 — callback inversion
The 3 engine→flow HTTP callbacks (`createschedule`, `submit`, `relationship` in `WorkflowClient`)
become events (`ScheduleRequested`, `ChildWorkflowRunCreated`; dead `submit` deleted) behind an
abstraction — transport stays HTTP until cutover (rollback = config). Plus `InternalController`
dissolves (A4), C10 dedup bindings, B9 stage-2 egress, H7 `RunScopeResolver`.
**Gates:** G1 targeted (`runWorkflow`/`runScheduledWorkflow` in `TaskExecutionService`); G2 per AM-4.

### E10 — merge deployables
One artifact + `flow.mode`; alias images (`flow-service-workflow`=full, `flow-service-engine`=engine)
from the merged binary; Helm chart-major; dispatcher image unchanged. **F1 load test before
cutover — abort gate (H9).**

### E11 — post-merge (ordered)
H12 DD-03 versioning → A2 enforcement flip + A3 first-class `bfd` token + H2 public-`phase`
decision → H11 collection drops (`locks`, `jr_*`, `_sch_*`) → H13 DD-01 Team→Workspace + H14
DD-04 frontend → alias windows expire → H16 standalone/Phase 4. Plus deferred: worker
leases/fencing, DD-07 re-decision.
