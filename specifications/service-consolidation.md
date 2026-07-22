# Service Consolidation — flow + engine Merge Analysis

**Status:** 🔴 Analysis required  
**Owner:** Claude Code  
**Depends on:** `horizontal-scaling.md` (run in parallel)  
**Last updated:** —

---

## Brief

Analyse the current `service-flow` and `service-engine` boundary and produce a concrete Spring
Modulith module proposal for v5. The output of this analysis is a written architecture decision
— not implementation. Do not begin migration until the proposal is reviewed and approved.

### What to do

1. **Map the call graph** between `service-flow` and `service-engine`. For every HTTP call from
   flow to engine, record: the calling method, the endpoint called, the payload, whether the call
   is synchronous (blocks the caller) or fire-and-forget, and whether it could tolerate
   eventual consistency.

2. **Audit `lib-common`**. Identify every class in `lib-common` and classify it as:
   - Truly shared (used by both flow and engine with no modification)
   - Flow-only (happens to live in common but only used by service-flow)
   - Engine-only (same — only used by service-engine)
   - Should be split (used by both but for different concerns that have been conflated)

3. **Identify module boundaries**. Based on the call graph and common audit, propose the
   Spring Modulith module structure. The expected outcome is approximately:

   ```
   io.boomerang.flow
     .api          — REST controllers, request/response DTOs, auth filter, token management
     .team         — User and Team management, workspace relationships
     .engine        — WorkflowRun lifecycle, DAG execution, TaskRun management
     .agent        — Agent interface, Tekton implementation, agent registration
     .schedule     — Quartz integration, schedule triggers
     .event        — CloudEvents publisher/consumer, ApplicationEvent definitions
     .common       — Shared domain model, entities, enums, error handling
   ```

   Each module should have a clearly defined public API surface. Cross-module calls must go
   through the public API — no reaching into another module's internal packages.

4. **Classify every cross-module interaction** as one of:
   - **Direct method call** — same JVM, same transaction context is acceptable
   - **ApplicationEvent** — decoupled but in-process (Spring `ApplicationEventPublisher`)
   - **CloudEvent** — must remain async over the wire (required for `engine` mode where
     the `api`/`team` modules are not loaded)

5. **Mode boundary analysis**. For each module, specify which deployment modes load it:

   | Module     | `full` | `engine` | `standalone` |
   | ---------- | ------ | -------- | ------------ |
   | `api`      | ✅     | ❌       | ✅           |
   | `team`     | ✅     | ❌       | ❌           |
   | `engine`   | ✅     | ✅       | ✅           |
   | `agent`    | ✅     | ✅       | ✅           |
   | `schedule` | ✅     | ✅       | ❌           |
   | `event`    | ✅     | ✅       | ✅           |
   | `common`   | ✅     | ✅       | ✅           |

   Fill in the correct values — the table above is a starting hypothesis, not a decision.
   Use `@ConditionalOnProperty(name = "flow.mode", ...)` for module-level conditional loading.

6. **Engine mode workspace contract**. In `engine` mode there are no workspaces or user
   relationships. Every WorkflowRun implicitly belongs to a `default` workspace. Document:
   - Which API endpoints are still exposed in engine mode
   - How `workspaceId` is resolved (always returns `"default"`)
   - What the migration path is for existing engine-mode deployments (EY SpaceTech)

7. **Flag breaking changes**. Identify anything that changes the public API contract,
   the MongoDB collection schema, or the container packaging that would affect:
   - Existing `full` mode deployments (Helm chart consumers)
   - Existing `engine` mode deployments (EY SpaceTech)
   - The `flow-loader` bootstrap container

8. **Produce the migration plan**. A sequenced list of steps to go from the current two-service
   structure to the merged Modulith application. Each step should be independently deployable
   — no big-bang cutover.

### What not to do

- Do not begin moving code until the proposal is written and reviewed
- Do not delete `service-engine` as a module until the merged service is validated in production
- Do not change the MongoDB collection schema during the analysis phase
- Do not change the external API contract during the analysis phase

---

## Architecture Context

### Why the split was made (v4)

`service-flow` and `service-engine` were separated in v4 for two reasons:

1. **EY SpaceTech deployment**: EY runs the engine headless without the full platform —
   no frontend, no authentication, no workspace/team management. The split allowed them
   to deploy only `service-engine`.

2. **Independent scaling**: The intent was to scale engine instances independently of
   the API layer.

### Why we are reconsidering (v5)

1. **Synchronous HTTP coupling**: `service-flow` calls `service-engine` over HTTP for workflow
   execution. This creates a hard dependency — if the engine is unavailable or slow, the flow
   API degrades. Under horizontal scaling this becomes a load-balancing and retry problem.

2. **Schema sync burden**: Both services share `lib-common` but have diverged in their use of
   shared models. Changes to the domain model require coordinated releases.

3. **Operational complexity**: Two services, two container images, two Helm subcharts,
   two Spring contexts to configure and monitor. For most deployments this overhead is not
   justified by the scaling benefit.

4. **The scaling argument is solvable differently**: Horizontal scaling of a merged service
   with proper distributed locking and CloudEvents partitioning achieves the same outcome
   with less operational complexity. See `specifications/horizontal-scaling.md`.

### The Spring Modulith approach

Spring Modulith enforces module boundaries at compile time via its `@ApplicationModule` model.
Modules communicate through:

- **Direct API calls** for co-located concerns (same deployment, tight coupling is acceptable)
- **`ApplicationEvent`** for decoupled in-process communication (the event bus replaces the HTTP call)
- **CloudEvents** for interactions that must work across deployment boundaries (engine mode)

The key insight: in `full` mode the flow→engine interaction becomes an `ApplicationEvent`.
In `engine` mode the same interaction arrives as a CloudEvent from an external caller.
The engine module does not care which transport delivered the instruction.

---

## Current State — Known Issues

The following are known pain points to validate and address during analysis:

- `lib-common` contains classes that are only used by one service — these should be moved
- The HTTP call from flow to engine on workflow submission is synchronous and blocking
- There is no retry or circuit breaker on the flow→engine HTTP call
- Collection prefix configuration (`flow.mongo.collection.prefix`) must work identically
  in both the split and merged deployment models
- The `flow-loader` bootstrap seeds data that both services depend on — the merged service
  must still be compatible with the existing loader

---

## Proposal (To Be Completed by Claude Code)

> This section is empty. Claude Code will populate it after completing the analysis above.
> The proposal must include:
>
> - Spring Modulith module boundary diagram
> - Complete cross-module interaction classification table
> - Mode loading matrix (filled in, not hypothetical)
> - Engine mode workspace contract
> - Breaking changes list
> - Migration plan with sequenced steps

---

## Decisions

> Record design decisions here as they are made. Use the format:
>
> **DD-01: [Title]**  
> Decision: ...  
> Rationale: ...  
> Rejected alternatives: ...  
> Date: ...
