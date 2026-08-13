# Repository Insights Engagement — Inputs to a Future v5 Phase

**Status:** 🟡 **INPUTS — proposed, not ruled** (captured 2026-08-09). These are requirements
from a client engagement evaluating Flow v5 as its orchestrator; most generalize into real
engine features. They are recorded here as inputs to a **future refactor phase** — each design
decision below goes through the normal proposed→confirmed discipline (wizard walkthrough → DD
in master spec §10) when that phase is actually worked. Nothing here is yet a ruled decision.

**Provenance / why it matters:** the engagement is an evidence-pipeline product (repo
acquisition → tool analysis → AI risk appraisal) choosing between **Flow v5** (primary) and
**Conductor OSS** (named fallback). The competitive frame shapes priority: "LLM as a task
type" is now table stakes (Conductor ships it across 14+ providers + MCP), so Flow's
differentiator must be **governed agency** — propose/dispose + a custody ledger + a
zone-pull executor SPI — none of which the surveyed engines have as first-class features.

**Stability:** (Evolving) — these are pre-design inputs; the schemas/SPI shapes will change as
the phase is designed.

---

## Phase placement (where each item lands)

| # | Input | Lands in / extends | New or extends |
|---|---|---|---|
| 1 | Pull-based executor SPI (zone queues, outbound-only, payload cap) | **Phase 4** runtime SPI + **DD-06** dispatcher | extends (adds zone-tag + payload-cap requirements) |
| 2 | Evidence block in the task-result contract | **new** — task-result data model + run ledger | new data-model + feature |
| 3 | Executor portfolio (K8s Jobs default, VM, MicroVM, Tekton demoted, CoCo flag) | **Phase 4** runtime evolution | extends (portfolio + isolation tiers) |
| 4 | Workspace non-retention as a documented engine guarantee | existing finalize/teardown model | promote existing behaviour to a guarantee |
| 5 | Thin LLM module + propose/dispose node type | **new** — governed-agency feature (LLM task type) | new feature (the differentiator) |
| 6 | Review Embabel (JVM agent framework) | **action item / spike** | investigation |
| 7 | Competitive framing (Conductor, agentgateway, agent-sandbox) | positioning (pitch, not code) | context |
| 8 | Sequencing (Phase A no-LLM core; Phase B = items 5–6) | phase ordering | planning |

**Sequencing gift (item 8):** the engagement's Phase A exercises the engine core, zone queues,
and executors with **zero LLM surface**; Phase B lands items 5–6. So the risky primitives
(governed agency) get designed while the safe ones (executor SPI, custody ledger) are already
in production — matches the v5 "prove the substrate before the AI features" instinct.

---

## 1. Executor SPI: pull, not push *(highest priority)*

The security model only holds if executors **poll** the engine for tasks tagged to their trust
zone, outbound-only. A push SPI (engine calls K8s/VM APIs directly) makes the engine the
credential-aggregation point — the exact thing a risk reviewer circles. Requested shape:

- Zone-local **executor agents** long-poll per-zone task queues; the engine accepts no inbound
  from zones and holds no zone credentials.
- Task assignment by **queue/zone tag**; an executor only ever sees its own zone's tasks.
- **Payload cap** on task inputs/outputs (small — single-digit KB), forcing pass-by-reference
  (blob URIs, IDs) structurally rather than by convention.

*Alignment with current v5:* the claim-based agent/dispatcher model is ALREADY pull-based
(agents long-poll `getTaskQueue`, outbound-only) — this input mostly ADDS: (a) zone/queue-tag
routing on the claim query (the typed queue classes already exist; add a zone tag dimension),
(b) a hard payload cap enforced at submit/claim. DD-06 (agent→dispatcher) is the rename that
carries this. **Design when Phase 4/E7 is worked.**

## 2. Evidence block in the task-result contract

Each task result carries a mandatory attestation the engine stores **verbatim** in the run
ledger:

```
evidence: { imageDigest, runtimeClass | vmIdentity, node,
            startedAt, finishedAt, exitCode, inputHash, outputHash }
```

The engine *assembles* a chain-of-custody ledger from executor attestations rather than
asserting one. Sellable audit feature well beyond this engagement. *Touches the TaskRun result
model (new nested block) + the audit/ledger surface — G2 data-model gate applies when built.*

## 3. Executor portfolio *(Tekton demoted, as planned)*

- **Kubernetes Jobs** executor as default — plain Jobs; isolation per task via RuntimeClass
  (runc → gVisor → Kata); namespace = zone; NetworkPolicy from the zone spec.
- **Ephemeral VM** executor (cloud instance APIs / libvirt) — VM per job, destroyed at
  completion.
- **MicroVM-direct** executor (Firecracker/libkrun) — "on-prem VMs, no K8s", hardware boundary.
- **Tekton** executor retained as compatibility/migration path.
- **Confidential computing** (CoCo runtime class / confidential VM SKUs) as a **flag on the
  task spec**, not a separate executor.
- Tool manifests declare their required **isolation tier**; the engine schedules accordingly.

*Aligns with Phase 4 (Tekton behind the SPI, local Docker runtime). Extends it to a portfolio +
an isolation-tier scheduling dimension.*

## 4. Workspace lifecycle as an engine guarantee

Run-scoped workspace volumes, GC'd at run completion, no snapshots — the current
Tekton/Boomerang behaviour, **promoted to a documented engine guarantee** ("non-retention
mode"). Clients with data-residency anxiety buy this sentence. *This is largely a documentation
+ guarantee-hardening task over the existing finalize/teardown model — note it as an invariant.*

## 5. LLM module: thin, and one genuinely new primitive

Keep the engine-native LLM task type to three responsibilities:

1. **Record every call** in the run ledger: model id, version/weights ref, prompt (or hash),
   params, response ref, tokens. Model provenance as an engine feature.
2. **Budgets as policy**: per-run token/cost/call ceilings, enforced by the engine.
3. **Propose/dispose as a first-class node type**: an LLM task cannot execute anything — it may
   only **append** tasks drawn from a **whitelisted menu**, which the engine then schedules as
   ordinary sandboxed steps. The agent gets a menu, never a shell.

Everything else — agent loops, planning, memory — is BYO framework *inside* a task. The engine
is the referee, not an agent framework. *This is the differentiator and a new node type; ties
to the placeholder-expand/fan-out reconciler mechanics (an LLM node that appends nodes is a
controlled fan-out). Design carefully — it's the risky primitive.*

## 6. Review Embabel *(action item)*

**Embabel** — Rod Johnson's (Spring creator) JVM agent framework. 1.0 GA, open source, built on
**Spring AI**, Kotlin core / Java-friendly. Distinctive: planning via **Goal-Oriented Action
Planning — deterministic code, not an LLM call** — explainable, predictable; plus Utility and
Supervisor planners and a state-machine model over typed `@Action` methods.

Why it matters: JVM + Spring AI slots into the modulith with zero impedance (the obvious first
"supported agent framework" inside the LLM task type); its typed-action model + deterministic
planner are **adjacent to propose/dispose** (study before finalizing the node-type design — also
see Dagger's Env-scoped tool exposure, same "tools as a type system" idea); positioning: "Flow +
Embabel" is a coherent JVM-native answer to the Python agent stacks.

**Suggested spike:** run an Embabel agent inside a Flow task; have it emit task proposals to a
stubbed propose/dispose node; verify its planner trace can be captured into the run ledger.
Verify license + governance health.

## 7. Competitive framing *(pitch, not code)*

- **Conductor OSS** ships native LLM task types (14+ providers) + MCP tool calling — "LLM as a
  task type" is table stakes. Flow's claim must be **governed agency**: propose/dispose + custody
  ledger + zone-pull executor SPI.
- **agentgateway** (Linux Foundation): policy/audit data plane for MCP/A2A tool traffic. Consider
  **integrating** rather than building MCP-level policy into the engine — engine governs what
  *executes*; gateway governs what agents may *call*.
- **agent-sandbox** (kubernetes-sigs): warm-pool sandbox CRDs over gVisor/Kata. Watching brief —
  relevant only if long-lived interactive agent sessions enter the roadmap; its stateful model
  conflicts with non-retention pipelines.

---

## Action items tracked out of this

- [ ] Embabel spike (item 6) + license/governance check.
- [ ] When Phase 4/E7 (dispatcher SPI) is designed: fold in zone-tag routing + payload cap (item 1).
- [ ] G2 data-model design for the evidence block (item 2) and the LLM-call ledger (item 5.1).
- [ ] Promote workspace non-retention to a CLAUDE.md invariant (item 4).
- [ ] Design the propose/dispose node type (item 5.3) — study Embabel/Dagger first.
