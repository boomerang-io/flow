---
name: spec-maintenance
description: Specification maintenance and evolution workflow. ALWAYS use this skill after completing any feature, task, phase workstream, or Q-register item. Also use when making architectural decisions, adding/changing endpoints, changing data models or entities, altering the workflow-run/task-run lifecycle, or changing anything documented in the specifications/ directory. Triggers on task completion, phase work, architectural changes, or any mention of updating specs/docs. If you've just finished implementing something, read this skill before marking it complete.
---

# Specification Maintenance

Specifications are the source of truth for v5. `specifications/v5-enhancemnet.md` is the
**master spec** — it owns the phase sequencing, the consolidated **Q-register**, and the
**Living Sections** that Claude Code fills in with evidence. `CLAUDE.md` holds the
architecture invariants and the specifications index.

**Key principle**: Only update specs when the change affects the design-level understanding of
the system. Adding a field to an entity → update the relevant spec / data section. Fixing a
log message or a local config default → no spec update needed.

## When to Update Specifications

### MANDATORY updates (do these every time):

1. **After completing any phase workstream, analysis task, or Q-register item**
   - Answer the Q-register question **in place** in `v5-enhancemnet.md`, with evidence
     (file paths, versions, measurements) — not a bare assertion.
   - Fill the matching **Living Section** (Version Matrix, DAG Semantics Inventory,
     Relationship Review, Lessons Verdicts, Consolidation Proposal, Scaling Assessment,
     Runtime Evolution Analysis).
   - Add implementation notes for what was built and any deviation from the plan.

2. **When a data model / entity changes** (new entity, new fields, changed relationships,
   new index)
   - Update the relevant spec section (e.g. the WorkflowRun/TaskRun model, claim/lease
     fields, `pauseRequestedAt`). Note new MongoDB indexes explicitly — the claim query is
     index-sensitive.

3. **When adding or changing an API endpoint**
   - Note the new/changed `*ControllerV1`/`*ControllerV2` route and its `@AuthCriteria`.
   - Remember: **status is the only external-facing field; phase is never exposed.**

4. **When changing the workflow-run / task-run lifecycle, claiming, locking, or queueing**
   - Update the Phase 2B (scaling/locking/queueing) sections and `scaling.md`.
   - Any change to atomic claiming, `@Version` writes, the recovery sweep, or
     `pauseRequestedAt` semantics MUST be reflected — these are the correctness core.

5. **When changing service boundaries or flow↔engine interaction**
   - Update Phase 2A (consolidation) and `service-consolidation.md`.
   - Flag whether a call is (or becomes) an `ApplicationEvent` vs a CloudEvent, and whether
     it holds in `engine`/`standalone` modes. **No new synchronous flow→engine HTTP calls.**

6. **When changing the agent runtime / SPI or adding a runtime**
   - Update the Phase 4 runtime-evolution analysis.

7. **When changing auth, permissions, tokens, or the audit trail**
   - Note changes to `PermissionResource`/`PermissionAction`/`AuthScope`, `@AuthCriteria`
     coverage, or the `audit` module. Cross-check with the `security-audit` skill.

### RECOMMENDED updates:

8. **When making an architectural decision** — add it to the master spec's **§10 Decisions**
   (or the relevant annex), with the rationale and the alternative rejected.
9. **When discovering an edge case** — document it in the relevant section.

## How to Update

### Audience test

Before adding content to a spec, ask: **"Would someone designing the next feature (without
reading the code) need this?"**
- Yes → add it to the spec.
- No, it's implementation dialect → skip it (framework annotations, log lines, local config).

### Mark stability

When adding new sections, mark them **(Stable)** or **(Evolving)**:
- **Stable**: phase intent, architecture invariants, domain model relationships — rarely change.
- **Evolving**: entity field lists, endpoint inventories, version matrices — change with work.

### Format for implementation notes

Add implementation notes as blockquotes at the end of the relevant section:

```markdown
> **Implemented**: `service-engine/src/main/java/io/boomerang/engine/...`
> Deviation: claimed runs carry `claimedBy`/`claimedAt`/epoch (ARCHIE L-12 divergence) —
> anonymous time-based recovery was insufficient for side-effectful tasks.
```

### Format for deviations and discoveries

```markdown
> **Deviation from spec**: chose optimistic `@Version` over a distributed lock for the
> workflow-level transition. See `WorkflowRun...Service.java`.

> **Addition**: recovery sweep requeues claimed-but-timed-out runs; run-level timeout must be
> ≥ the transport timeout of the guarded work, or healthy long calls get reaped.
```

## Update Checklist (run after each task)

- [ ] Identify which spec file(s) / Q-register items / Living Sections are affected
- [ ] Read the relevant sections before editing
- [ ] Answer any Q-register item in place, with evidence
- [ ] Fill the matching Living Section
- [ ] Add implementation notes for what was built; document deviations with rationale
- [ ] Verify no **CLAUDE.md architecture invariant** was silently crossed (status-only,
      WorkflowRun-as-execution-record, idempotent handlers, lib-common purity, no new sync
      flow→engine HTTP)
- [ ] Mark new sections (Stable)/(Evolving)
- [ ] If a repo-wide convention/version/property changed, update `CLAUDE.md` too

## Spec-to-Code Mapping

| Specification / Section                        | Primary Code Locations                                                                 |
| ---------------------------------------------- | -------------------------------------------------------------------------------------- |
| v5 §3 Framework Baseline / Version Matrix       | `pom.xml`, `*/pom.xml`, `.github/workflows/ci-*.yml`                                    |
| v5 §4 DAG Semantics / Lessons                   | `service-engine/**/engine/`, `service-engine/**/aspect/`                               |
| v5 §5.2A Consolidation                          | `service-consolidation.md`, module boundaries, `client/` (flow↔engine calls)           |
| v5 §5.2B Scaling / Locking / Queueing           | `scaling.md`, claiming/recovery code in `service-engine/**/engine/`                    |
| v5 §7 Task Runtime Evolution                    | `service-agent/**/agent/`, `service-agent/**/kube/`, agent SPI                          |
| Data models / entities                          | `**/entity/` in each module, `lib-common/**` shared model                              |
| API design                                      | `service-flow/**/*ControllerV2.java`, `service-engine/**/*ControllerV1.java`           |
| Auth / permissions / audit                      | `service-flow/**/security/`, `service-engine/**/audit/`, `AuditControllerV2.java`      |
| Relationships / access model                    | `service-flow/**/core/RelationshipService.java`                                        |

## CLAUDE.md Updates

If a task reveals a new project-wide convention or pattern, also update `CLAUDE.md`:
- New naming conventions or module boundaries
- New shared utility patterns
- Updated dependency versions (keep the Technology Stack table honest)
- New environment variables or config properties (e.g. `flow.mode`, `@ConditionalOnProperty` gates)
- New or renamed specification files (keep the Specifications Index accurate — note the master
  spec filename is `v5-enhancemnet.md`)
