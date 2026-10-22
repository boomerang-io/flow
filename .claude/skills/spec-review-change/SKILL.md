---
name: spec-review-change
description: Review a proposed spec change — explore current implementation, ask clarifying questions, critically debate the design, and surface implementation gaps. Use when reviewing a proposed change to a reference doc or a proposed decision record before committing to implementation.
argument-hint: <paste spec text here>
---

# Spec Review: Change Analysis

Review a proposed spec change by comparing it to the current implementation, debating the
design, and surfacing blockers before implementation begins. The spec text is passed as the
skill argument.

This repo is **spec-driven**: `specifications/<subsystem>.md` describes how each subsystem works today and
`specifications/decisions/` records why (one numbered record per decision) — see CLAUDE.md.
`CLAUDE.md` holds the architecture invariants. A "spec change" here is a proposed edit to a reference doc or a proposed decision record.

## Phase 1 — Parse the Spec

Read the pasted spec text and extract:

- **Domain**: what system or feature area this covers (e.g. "workflow run lifecycle", "atomic
  claiming / locking", "the dispatcher contract", "parameters", "auth/authz").
- **Key entities and flows**: the data structures, state machines, and processes described
  (e.g. `WorkflowRunEntity`, phase vs status, TaskRun reconciliation, the claim query).
- **Design decisions**: the choices the spec makes, stated or implied.
- **Scope of change**: what is being added, removed, or changed relative to what likely
  exists today, and which reference doc and decision records it touches.

Summarise these four points in 3–5 bullet points before proceeding. This confirms you have
read the spec correctly.

## Phase 2 — Explore Current Implementation

Spawn an `Explore` agent with a focused search prompt built from the domain and entities
extracted in Phase 1.

Tell the Explore agent to:
- Find backend files across the relevant modules: `service-core/` (feature packages `core`,
  `workspace`, `workflow`, `engine`, `dispatcher`, `schedule`, `event`, `integrations`,
  `migration`, and the domain model in `common`), `dispatcher-sdk/`, `service-dispatcher/` —
  entities (`**/entity/`), services, controllers (`*ControllerV1.java` / `*ControllerV2.java`),
  repositories, config. Include `client-web/` when the change reaches the UI, and
  `contracts/dispatcher-v1.yaml` when it reaches the dispatcher wire.
- Find the reference docs in `specifications/` that cover the same area, and the decision
  records in `specifications/decisions/` that already rule on it.
- Check `CLAUDE.md` for any **architecture invariant** the change touches (`status` as the
  external field with `phase` kept only for the dispatcher, WorkflowRun as execution record,
  idempotent transition handlers, typed fields over annotations, `io.boomerang.common` purity,
  the engine never calling the platform side synchronously, no Kubernetes dependency in
  `service-core`).
- Report: file paths + a one-line summary of what each file currently does.

After the agent returns, list the files found with their one-line summaries.

## Phase 3 — Supplement Check

Present the file list to the user and ask:

> "Here are the files I found related to this spec. Are there any other files, background
> context, or prior decisions I should consider before the review?"

Wait for the user's response. If they provide additional context, incorporate it. If they
say "no" or "proceed", continue.

## Phase 4 — Critical Review

Deliver the review in four clearly labelled sections. Be direct and specific — reference
actual file paths, line-level behaviour, and concrete spec text.

### Clarifying Questions

List 5–8 targeted questions about:
- Ambiguities in the spec (terms used without definition, behaviours left unspecified).
- Missing edge cases (what happens when a claim races, a lease expires, a dispatcher crashes
  mid-task, a WorkflowRun is paused then the instance dies).
- Unstated assumptions (does this assume a migration? a new property or stored setting, and in
  which tier? a compare-and-set transition? a new index?).
- Scope questions (is this replacing X or extending it? what happens to existing data /
  in-flight runs / a v3 database being upgraded? does it hold with security off and on a
  single-workspace install, not just the default shape?).

Number each question. Be specific — quote the relevant spec text in each question.

### Design Debate

For each major design decision in the spec, present the strongest counter-argument or
alternative, and name the trade-off. Format:

**Decision**: [what the spec proposes]
**Counter**: [the strongest objection or alternative]
**Trade-off**: [what is gained vs. lost by the spec's choice]

Focus on decisions that could have been made differently, not mechanical implementation
details. Always test a change against the `CLAUDE.md` rule not to build ahead of proven need
(concurrency caps, retry classes and a separate reconciler were each considered and not built),
and against the existing data model (an added field, wrapper or collection carries the burden
of proof).

### Implementation Gaps

Table comparing spec claims to current reality:

| Spec Claim | Current Reality | Gap / Risk |
|------------|----------------|------------|
| [what the spec says exists or will happen] | [what the code actually does today, with file reference] | [the delta and its risk] |

Include a row for every meaningful claim in the spec. Mark gaps as:
- `MISSING` — spec describes something that doesn't exist yet
- `CONFLICT` — spec contradicts how the current code works
- `INVARIANT` — spec would violate a CLAUDE.md architecture invariant (must resolve)
- `ALIGNED` — spec matches current implementation (no change needed)
- `UNCLEAR` — can't confirm without more context

### Verdict

2–3 sentences: Is this spec ready to hand to implementation, or are there blockers that must
be resolved first? Name specific blockers, the review gate it needs (the `DAGUtility` /
`TaskExecutionService` method list, or a data-model change for discussion), and any invariant
it must not cross.
