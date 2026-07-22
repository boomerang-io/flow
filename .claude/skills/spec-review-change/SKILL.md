---
name: spec-review-change
description: Review a proposed spec change — explore current implementation, ask clarifying questions, critically debate the design, and surface implementation gaps. Use when reviewing a spec (or a v5-enhancement Q-register answer / Living Section) before committing to implementation.
argument-hint: <paste spec text here>
---

# Spec Review: Change Analysis

Review a proposed spec change by comparing it to the current implementation, debating the
design, and surfacing blockers before implementation begins. The spec text is passed as the
skill argument.

This repo is **spec-driven**: `specifications/v5-enhancemnet.md` is the master spec (phases,
Q-register, Living Sections), with `scaling.md` and `service-consolidation.md` as annexes.
`CLAUDE.md` holds the architecture invariants. A "spec change" here is usually a new Q-register
answer, a Living Section, or a phase proposal.

## Phase 1 — Parse the Spec

Read the pasted spec text and extract:

- **Domain**: what system or feature area this covers (e.g. "workflow run lifecycle", "atomic
  claiming / locking", "service consolidation", "agent runtime SPI", "auth/authz").
- **Key entities and flows**: the data structures, state machines, and processes described
  (e.g. `WorkflowRunEntity`, phase vs status, TaskRun reconciliation, the claim query).
- **Design decisions**: explicit choices made in the spec (named DD-01 style or implicit).
- **Scope of change**: what is being added, removed, or changed relative to what likely
  exists today, and which **phase** (0–4) and **Q-register** items it touches.

Summarise these four points in 3–5 bullet points before proceeding. This confirms you have
read the spec correctly.

## Phase 2 — Explore Current Implementation

Spawn an `Explore` agent with a focused search prompt built from the domain and entities
extracted in Phase 1.

Tell the Explore agent to:
- Find backend files across the relevant modules: `lib-common/`, `service-flow/`,
  `service-engine/`, `service-agent/` — entities (`**/entity/`), services, controllers
  (`*ControllerV1.java` / `*ControllerV2.java`), repositories, config.
- Find spec files in `specifications/` that cover the same area, and the relevant
  Q-register items / Living Sections in `v5-enhancemnet.md`.
- Check `CLAUDE.md` for any **architecture invariant** the change touches (status-only
  external field, WorkflowRun as execution record, idempotent transition handlers,
  lib-common purity, no new synchronous flow→engine HTTP calls).
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
- Missing edge cases (what happens when a claim races, a lease expires, an agent crashes
  mid-task, a WorkflowRun is paused then the instance dies).
- Unstated assumptions (does this assume a migration? a `flow.mode` gate? an optimistic
  `@Version` write? a new index?).
- Scope questions (is this replacing X or extending it? what happens to existing data /
  in-flight runs? does it hold in `engine` and `standalone` modes, not just `full`?).

Number each question. Be specific — quote the relevant spec text in each question.

### Design Debate

For each major design decision in the spec, present the strongest counter-argument or
alternative, and name the trade-off. Format:

**Decision**: [what the spec proposes]
**Counter**: [the strongest objection or alternative]
**Trade-off**: [what is gained vs. lost by the spec's choice]

Focus on decisions that could have been made differently, not mechanical implementation
details. For v5 work, always test a change against the ARCHIE lessons (do not over-abstract
ahead of proven need) and the consolidation hypothesis (merge is not yet a decision).

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
be resolved first? Name specific blockers, the phase gate it belongs behind, and any
invariant it must not cross.
