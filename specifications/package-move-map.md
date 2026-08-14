# E8.2c Package Move Map (service-core → nine-module flat layout)

**Status: 🔵 ACTIVE (2026-08-14).** The class→package map driving the E8.2c restructure.
Produced from a full import-matrix scout of the merged `service-core` (246 classes, 30 packages).
Target layout (ARCHIE flat-feature convention): `io.boomerang.{core, workspace, workflow, engine,
dispatcher, schedule, event, integrations, api}` + `Application`; lib-common stays the separate
shared-contract Maven module.

## Phase ordering (cleanest-first)

1. **P1 — zero-risk deletions + C5** (~20 classes): delete the dead `io.boomerang.audit` package
   (unreferenced duplicate of `core.audit`; VERIFY no runtime writer of its `@Document('audit')`
   mapping first), `EventQueueEntity`+`EventQueueRepository` (v4 legacy, superseded by
   ActionEntity), `SpringAsyncConfig` (duplicate @EnableAsync), `MongoHealthConfig` (byte-identical
   duplicate HealthIndicator), `workflow.model.WebhookType` (0 refs), `TaskRunServiceTest`
   (@Disabled empty placeholder), `AuditControllerV2` (fully commented-out stub with live mapping).
   Move `SettingConfig` + `AESAlgorithm` → `core.model` (C5; kills 5 of 9 core→workflow imports).
2. **P2 — mechanical moves (no merges)**: security→`core.security` (C4 fold; but
   `DispatcherSecurityConfiguration`+`DispatcherAuthFilter`→`dispatcher`); config splits
   (`MongoConfiguration`/`RestConfig`/`AsyncConfiguration`/`MongoHealthConfiguration`→`core.config`,
   `AsyncConfig`/`SchedulingConfig`→`engine.config`, `CloudEventHandlerConfiguration`→`event.config`,
   Yaml*→`workflow.config`); engine splits (Dispatcher*→`dispatcher`, CloudEventsBridge/
   EventSinkService/OutboxDispatcher/EventInbox*/EventOutbox*/EventType/InboxStatus/OutboxStatus/
   status-event models/`util.EventFactory`→`event`, AgentEntity/AgentRepository→`dispatcher`);
   J2 moves (engine's TaskService/WorkflowService/WorkflowTemplateService + their V1 controllers +
   Task/TaskRevision/Workflow/WorkflowRevision/WorkflowTemplate repositories→`workflow`);
   schedule split (Cron/Schedule* + WorkflowScheduleRepository + CronValidationResponse/
   WorkflowScheduleCalendar→`schedule`); workspace split (TeamService/InsightsService + Team*/
   ApproverGroup* entities/repos/models + TeamSummaryInsights→`workspace`); WebhookEventService +
   flow's WorkflowRunEventRequest→`event` (keep ENGINE's WorkflowRunEventRequest — superset with
   dedup `id`; delete flow's); SlackEventPayload→`integrations.model`; util splits (ConvertUtil→
   `workflow`, GraphProcessor/ResultUtil→`engine`); tekton stays `workflow.tekton` with renames
   `Metadata`→`TektonMetadata`, `Spec`→`TektonSpec` (dangerously generic names); error/* →
   lib-common `common.error` (BoomerangError/Exception/RestErrorResponse; RestExceptionHandler→
   `api`); ALL v2 controllers→`api` EXCEPT WebhookEventControllerV2→`event` (ingress, not
   composition); `*ResponsePage` DTOs→`api.model`; tests move with their features (Agent*/
   Dispatcher* tests→`dispatcher`, OutboxDeliveryTest→`event`, Cron/ScheduleWatcher tests→
   `schedule`, SecurityInterceptorTests→`core.security`).
3. **P3 — the hard merges + inversions** (the only dangerous edits):
   - Four flow-vs-engine same-name service pairs: **engine's class is the base**; flow's
     `WorkflowService`(1138ln)/`TaskService`(470ln) team-scoping/canvas/quota concerns dissolve
     into `api` composition + `workspace`; flow's thin `WorkflowRunService`(289ln)/
     `TaskRunService`(125ln) scoping shims dissolve into `api`. EngineClient facade deleted (J2,
     15 call-sites re-pointed). `workflow.repository.ActionRepository` merged into engine's (J3).
   - `ParameterManager` name clash: rename `workflow.ParameterManager`→`GlobalParameterManager`
     (J5) vs engine's runtime substitution manager.
   - `WorkflowClient` (engine→flow HTTP: createschedule/submit/relationship) → direct
     `schedule`/`workflow` calls or events (E9 overlap — the event inversion may land here).
   - `InternalController` delete-after-verify (existed only for engine→flow HTTP).
   - R1 residue: `UserService`→Team* inverted (workspace queries core); `core.audit.
     AuditInterceptor`'s Team/WorkflowCanvas references genericized or interceptor moves;
     `SystemControllerV2`/`InternalController`→`api` kills their core→workflow edges;
     `UserProfile`→TeamSummary inverted via `api` assembly (R14).
   - R11: `InsightsService` stops importing `core.audit.AuditRepository` — goes through a new
     core `AuditQueryService` (C6).
   - LogClient→`engine` (or `dispatcher.client`) — engine-consumed HTTP to the agent log stream.

## Ruling adopted (flagged for maintainer review) — R12

"workflow must not depend on core" is violated wholesale today (~48 imports, `RelationshipService`
pervasive). **Adopted resolution: the ARCHIE reading — `core` is the platform substrate that any
feature module may depend downward on.** Engine-mode purity comes from MODE COMPOSITION, not
import direction: the mode matrix already prescribes non-full modes load a default-anchor/no-op
`RelationshipService` (I3: byte-identical code paths, single-anchor no-op), so feature→core
dependencies stay valid in every mode. The original Q-133 concern is met by gating + no-op
implementations, not by forbidding the import. Most R12/R13 imports disappear anyway when
controllers move to `api` and J2 dissolves the facades.

## Bean-name/SpEL landmines (runtime, not compile-time — R15)

- `mongoConfiguration` bean name is referenced by SpEL in EVERY `@Document` — must not change.
- RestTemplate qualifiers (`externalRestTemplate`/`insecureRestTemplate`/`internalRestTemplate`/
  `selfRestTemplate`/`streamingRestTemplate`) — preserve bean names across moves.
- `engineAuditInterceptor`/`engineAuditRepository` aliases die with the dead audit package.

## Known coverage gap (pre-existing)

Zero tests for TeamService (1002 ln), flow's WorkflowService (1138 ln), integrations,
WebhookEventService, and all v2 controllers — the P3 merges of exactly these classes proceed
behind compile + the existing integration suite only. Flagged: consider characterization tests
for the two big merges if P3 proves hairy.
