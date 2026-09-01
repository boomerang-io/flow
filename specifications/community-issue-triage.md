# `boomerang-io/community` issue triage — executed 2026-09-01

Audit trail for the sunset of the `community` issue tracker. All 143 open issues were triaged one by one
(maintainer-ruled), then executed by script: **61 transferred** (45 → `flow`, 6 → `bosun.client.web`,
6 → `bosun.service.policy`, 3 → `tasks`, 1 → `website`), **82 closed** (35 completed, 12 duplicate, 35 not
planned). Old `community` URLs redirect to the transferred issue. Closed issues remain readable in the
archived repository. Announcement: boomerang-io/community#450.

Calls: KEEP/BOSUN/TASKS/WEBSITE = transferred; DUP = closed as duplicate, body appended to the survivor;
DONE = closed completed with the v5 artefact that delivered it; AGED/CLOSE = closed not planned with the reason.

| community # | Opened | Title | Call | Outcome |
| --- | --- | --- | --- | --- |
| 15 | 2020-11-05 | Advanced Task Configuration | KEEP | boomerang-io/flow/issues/330 |
| 18 | 2020-11-11 | Log Event Activities | KEEP | boomerang-io/flow/issues/331 |
| 23 | 2020-11-16 | Add Counter task type in Flow | KEEP | boomerang-io/flow/issues/332 |
| 28 | 2020-11-26 | Team Secret Management | KEEP | boomerang-io/flow/issues/333 |
| 30 | 2020-11-26 | Ability to run workflow from within Editor | KEEP | boomerang-io/flow/issues/334 |
| 35 | 2021-01-04 | No network flag security context for Custom Task or All Tasks | DUP | dup of boomerang-io/flow/issues/330 |
| 36 | 2021-01-04 | UI - Control the timeouts on a task / custom task | DUP | dup of boomerang-io/flow/issues/131 |
| 40 | 2021-01-04 | Advanced PVC Configuration | KEEP | boomerang-io/flow/issues/335 |
| 42 | 2021-01-04 | Workflows Explorer or Hub | KEEP | boomerang-io/flow/issues/336 |
| 56 | 2021-02-08 | Update Experience For Import/Overwrite a Workflow | AGED | closed not planned — v4 rewrote import; the ID checks described no longer exist |
| 62 | 2021-02-15 | Align web and service with the apps and services | CLOSE | closed not planned — org-wide naming alignment is moot |
| 81 | 2021-03-05 | Shell Params not Being Substituted properly | DONE | closed completed — engine-side `$(params.x)` substitution (runtime-contract C2, 2026-08) |
| 83 | 2021-03-12 | New Kubernetes Tasks | AGED | closed not planned — a single link; file concrete task requests in boomerang-io/tasks |
| 89 | 2021-04-07 | Team based Kubernetes Node Selectors | DUP | dup of boomerang-io/flow/issues/330 |
| 90 | 2021-04-07 | Kubernetes Namespace Per Team | AGED | closed not planned — superseded by the per-deployment isolation model (`task-contract-research.md` §6) |
| 91 | 2021-04-07 | APIs Phase 1: Teams + Users | DONE | closed completed — v4 `/api/v2` + tokens |
| 93 | 2021-04-07 | CLI | KEEP | boomerang-io/flow/issues/337 |
| 95 | 2021-04-07 | Debug Endpoints | DONE | closed completed — actuator + `/api/v2/context` |
| 96 | 2021-04-07 | Additional Team Roles | DONE | closed completed — v5 `AuthScope` + editor/reader grants |
| 97 | 2021-04-07 | Centralized Approvals and Manual Tasks | DONE | closed completed — Actions feature (`ActionService`) |
| 98 | 2021-04-07 | Workflow Status (Draft vs Published) | KEEP | boomerang-io/flow/issues/338 |
| 101 | 2021-04-07 | Support Multi Step Tasks | KEEP | boomerang-io/flow/issues/339 |
| 102 | 2021-04-07 | Output artifacts (not just properties) | DUP | dup of boomerang-io/flow/issues/319 |
| 103 | 2021-04-07 | Non blocking workflow execution between tasks using NATS queues | DONE | closed completed — superseded by the E4 claim queues; no broker ruled |
| 104 | 2021-04-07 | Workflow Validation | KEEP | boomerang-io/flow/issues/340 |
| 105 | 2021-04-07 | Ability to lock flows | KEEP | boomerang-io/flow/issues/341 |
| 106 | 2021-04-07 | User Space | DONE | closed completed — `WorkspaceType.personal` |
| 107 | 2021-04-07 | Workflow Execution Output Properties | DONE | closed completed — `WorkflowRun.results` |
| 109 | 2021-04-07 | Enhanced Input Parameter Manager | KEEP | boomerang-io/flow/issues/342 |
| 110 | 2021-04-07 | Ability to see the input parameters to the workflow / task as part of  | DONE | closed completed — v4 run detail shows params |
| 111 | 2021-04-07 | Improve and add advanced Insights | KEEP | boomerang-io/flow/issues/343 |
| 113 | 2021-04-07 | New PagerDuty tasks | TASKS | boomerang-io/tasks/issues/21 |
| 114 | 2021-04-07 | Tasks to put messages on queue | KEEP | boomerang-io/flow/issues/344 |
| 117 | 2021-05-05 | Task Management - YAML Validation | KEEP | boomerang-io/flow/issues/346 |
| 121 | 2021-05-05 | Manage Tasks YAML Documentation | AGED | closed not planned — v4 docs rewritten |
| 123 | 2021-05-12 | Triggering Multiple Workflows at once can break through quotas | KEEP | boomerang-io/flow/issues/347 |
| 130 | 2021-05-18 | Export only takes latest version | AGED | closed not planned — export is per revision by design; no demand since 2021 |
| 134 | 2021-05-21 | Submit to CNCF Sandbox | KEEP | boomerang-io/flow/issues/348 |
| 137 | 2021-05-21 | Dynamic handling of auto complete for result parameters | KEEP | boomerang-io/flow/issues/349 |
| 140 | 2021-05-21 | Add Support New Yaml Template Task | AGED | closed not planned — Tekton-specific; the executor SPI abstracts the runtime |
| 145 | 2021-05-24 | Determine if the JSON view is still needed in the View Result Paramete | DUP | dup of boomerang-io/flow/issues/350 |
| 149 | 2021-05-26 | Ability to see and search for workflow labels on the workflows screen | DONE | closed completed — `labels` query param on the workspace workflow query |
| 156 | 2021-06-09 | Update the header to match the new design styling | BOSUN | boomerang-io/bosun.client.web/issues/36 |
| 157 | 2021-06-09 | Bosun v2 | BOSUN | boomerang-io/bosun.service.policy/issues/24 |
| 158 | 2021-06-09 | Inclusive Language Policy | BOSUN | boomerang-io/bosun.service.policy/issues/25 |
| 159 | 2021-06-10 | Update the Bosun Web App to latest components | BOSUN | boomerang-io/bosun.client.web/issues/37 |
| 160 | 2021-06-10 | Adjust Terminology | BOSUN | boomerang-io/bosun.service.policy/issues/26 |
| 161 | 2021-06-10 | Add Policy Validation Button | BOSUN | boomerang-io/bosun.client.web/issues/38 |
| 162 | 2021-06-10 | Add Policy ID to the Policy Detail | BOSUN | boomerang-io/bosun.client.web/issues/39 |
| 163 | 2021-06-10 | New Policy: OSS License | BOSUN | boomerang-io/bosun.service.policy/issues/27 |
| 164 | 2021-06-10 | Add support for Policy Scopes | BOSUN | boomerang-io/bosun.service.policy/issues/28 |
| 165 | 2021-06-10 | Typescript Migration | BOSUN | boomerang-io/bosun.client.web/issues/40 |
| 166 | 2021-06-10 | Enhance Policy: Sonarqube Quality Gate | BOSUN | boomerang-io/bosun.service.policy/issues/29 |
| 171 | 2021-06-15 | Workflow Service maintenance tasks | AGED | closed not planned — v3 controller service is gone |
| 172 | 2021-06-16 | Always show View Parameters on Workflow Activity | AGED | closed not planned — UI rewritten (v4/T8) |
| 175 | 2021-06-23 | Industry Task Alignment Phase 3 | KEEP | boomerang-io/flow/issues/350 |
| 178 | 2021-06-25 | FE Tech Debt Clean Up | DONE | closed completed — T8 frontend refactor |
| 181 | 2021-07-03 | Deprecate and remove support for legacy property format and syntax | DONE | closed completed — v5 accepts `$(params.x)` only; loader migrates |
| 183 | 2021-07-21 | View Task Error misalignment | AGED | closed not planned — controller service gone; v5 `RunError` |
| 184 | 2021-07-21 | Task multiline script represented wrong in yaml | DONE | closed completed — `YamlJacksonHttpMessageConverter` enables `LITERAL_BLOCK_STYLE`; verified 2026-09-01 that task YAML emits `script: |` on every YAML endpoint and the webapp uses the server YAML unchanged |
| 187 | 2021-07-22 | Ensure E2E first install is a good experience | AGED | closed not planned — docker-compose stack + e2e suite exist; re-raise specifics if any |
| 188 | 2021-07-23 | Understand the Policy as Code purpose | CLOSE | closed not planned — empty placeholder |
| 189 | 2021-07-23 | Document requirements and vision | CLOSE | closed not planned — empty placeholder |
| 190 | 2021-07-26 | Oops looks like there is an issue | AGED | closed not planned — no login flow yet; tracked by `specifications/authentication.md` |
| 191 | 2021-07-26 | Cannot create users and standalone authentication | AGED | closed not planned — tracked by `specifications/authentication.md` / boomerang-io/flow#314 |
| 193 | 2021-07-28 | Add support for mobile to Flow marketing page on useboomerang.io site | WEBSITE | boomerang-io/website/issues/1 |
| 195 | 2021-07-31 | Workflow disconnects from NATs randomnly | AGED | closed not planned — NATS removed |
| 196 | 2021-08-02 | Add a CONTRIBUTING.md to Web App | CLOSE | closed not planned — folded into the monorepo CONTRIBUTING.md (boomerang-io/flow#329) |
| 197 | 2021-08-03 | WFE does not show View Result in Activity Execution | AGED | closed not planned — v3 UI |
| 198 | 2021-08-04 | Log all Events as activity even failure | DUP | dup of boomerang-io/flow/issues/331 |
| 200 | 2021-08-13 | Document Feature Specs | DONE | closed completed — `specifications/` in boomerang-io/flow |
| 202 | 2021-08-19 | Add Task support for additional Parameter Ref Types | AGED | closed not planned — Tekton import path; the contract is engine-side now |
| 203 | 2021-08-19 | Ability to create personal workflows | DONE | closed completed — `WorkspaceType.personal` |
| 204 | 2021-08-20 | Task Timeout not being respectied | DONE | closed completed — E2 `DAGUtility` timeout merge fix |
| 205 | 2021-08-20 | Empty Event Body causing exception | KEEP | boomerang-io/flow/issues/381 |
| 206 | 2021-08-20 | Controller code cleanup | AGED | closed not planned — v3 controller service is gone |
| 207 | 2021-08-20 | Flow Activity does not have event in the Trigger filter | AGED | closed not planned — UI rewritten (v4/T8) |
| 208 | 2021-08-23 | Settings section description does not show | AGED | closed not planned — UI rewritten (v4/T8) |
| 209 | 2021-08-24 | Add Documentation link to help menu in all modes | KEEP | boomerang-io/flow/issues/351 |
| 210 | 2021-08-26 | Simplify and Migrate Feature Flags | KEEP | boomerang-io/flow/issues/352 |
| 212 | 2021-08-27 | UI + Workflow: User Roles & Role Checks | DONE | closed completed — v5 permission enforcement (2026-08-31) |
| 215 | 2021-08-27 | UI + Workflow - Email Notifications | KEEP | boomerang-io/flow/issues/353 |
| 217 | 2021-08-27 | New Task for Sending mail with Postmark | TASKS | boomerang-io/tasks/issues/22 |
| 220 | 2021-09-13 | Custom install into GKE | AGED | closed not planned — v3 charts |
| 221 | 2021-09-14 | Validate Google Oauth2 integration | DUP | dup of boomerang-io/flow/issues/314 |
| 225 | 2021-09-17 | HTTP Call Task results in error even though 200 | AGED | closed not planned — 2021 worker.flow bug; the tasks line has since been rewritten — reopen in boomerang-io/tasks if reproduced |
| 226 | 2021-09-17 | Oops looks like there is an issue | AGED | closed not planned — reported against the v3 UI, which was rewritten in v4/T8 |
| 227 | 2021-09-21 | Saving Task in YAML view loses the Category | DONE | closed completed — `TektonConverter` carries `category` through the `boomerang.io/category` annotation on the YAML round-trip (`TektonConverter.java:48,109-113`); a regression test is in PR branch `test/community-205-227` on boomerang-io/flow |
| 229 | 2021-09-28 | Community Videos | CLOSE | closed not planned — empty placeholder |
| 234 | 2021-09-29 | Navigating from Manual to Approvals causes Actions to crash | AGED | closed not planned — UI rewritten (v4/T8) |
| 235 | 2021-09-30 | Add Templates to System Workflows | DONE | closed completed — workflow templates (v4) |
| 242 | 2021-10-11 | Enhance Workflow Parameters to be set and retrieved dynamically | KEEP | boomerang-io/flow/issues/354 |
| 246 | 2021-10-13 | Token Management | DONE | closed completed — v4 tokens + dispatcher token |
| 249 | 2021-10-13 | Enhance Flows ability to Integrate for Triggering Workflows | KEEP | boomerang-io/flow/issues/355 |
| 250 | 2021-10-15 | Ability to drag and drop the workflow tiles to reorder them on the scr | KEEP | boomerang-io/flow/issues/356 |
| 251 | 2021-10-15 | Add worker support for popular products | TASKS | boomerang-io/tasks/issues/23 |
| 253 | 2021-10-18 | Add new Trello tasks as Verified Tasks | AGED | closed not planned — time-bound 2021 follow-up |
| 280 | 2021-11-03 | Add the ability to schedule execution in the future on the manual Run  | KEEP | boomerang-io/flow/issues/357 |
| 287 | 2021-11-09 | Loader not properly checking for existing collections | AGED | closed not planned — loader rewritten on Flamingock (DD-07) |
| 297 | 2021-12-07 | Add support for newer Tekton versions | DONE | closed completed — fabric8 7.8 + Tekton v1 (Phase 0) |
| 298 | 2021-12-07 | Update to Workflow Parameter Types to align with IBM Core Platform Pro | AGED | closed not planned — params re-ruled 2026-08; milestone 3.5.1 is history |
| 306 | 2022-01-11 | Adjust the Simple Recurring Schedule UI to allow a better input format | KEEP | boomerang-io/flow/issues/358 |
| 309 | 2022-01-13 | Save ScheduleID on Run Schedule Workflow Activity for deep linking | KEEP | boomerang-io/flow/issues/359 |
| 326 | 2022-01-26 | Produce events for status changes of a workflow activity | DONE | closed completed — transactional outbox CloudEvents (E4) |
| 327 | 2022-02-01 | Support Markdown in a Task's description | KEEP | boomerang-io/flow/issues/360 |
| 331 | 2022-03-09 | Add Instana support to Bosun | BOSUN | boomerang-io/bosun.client.web/issues/41 |
| 333 | 2022-03-30 | Disabled Scheduled Jobs affect all of a workflows Scheduled jobs | DONE | closed completed — Quartz retired; `ScheduleWatcher` |
| 345 | 2022-05-09 | Support Dot and Backet Notation in parameters | AGED | closed not planned — param matching ruled 2026-08-26 |
| 346 | 2022-05-09 | Support onError in tasks | DUP | dup of boomerang-io/flow/issues/339 |
| 347 | 2022-05-09 | Confirm timeout still works for Task Time Out | DONE | closed completed — timeout audit (E2) |
| 349 | 2022-05-09 | Scheduler - Recurring via cron expression failed to get triggered | DONE | closed completed — E5 `ScheduleWatcher` rewrite |
| 350 | 2022-05-10 | When scheduler toggle is active, creating a schedule is added as disab | DONE | closed completed — E5 `ScheduleWatcher` rewrite |
| 354 | 2022-06-20 | Add Documentation for Flow Templates | AGED | closed not planned — contradicts the ruling on #42 — WorkflowTemplates are being retired in favour of a public hub |
| 356 | 2022-07-01 | Additional Workflow Templates | AGED | closed not planned — contradicts the ruling on #42 — WorkflowTemplates are being retired in favour of a public hub |
| 363 | 2022-09-20 | Output parameters not shown in case of task failure | KEEP | boomerang-io/flow/issues/361 |
| 369 | 2022-11-09 | All workflow password-type parameters will be set to null in database  | DONE | closed completed — `WorkspaceSecuredParameterUpdateTest` |
| 373 | 2022-12-13 | Workflow - Revisit / Redesign Trigger implementation | AGED | closed not planned — v4 triggers tab shipped; re-raise specifics |
| 374 | 2022-12-21 | Engine - Split Status and Phase events for publish | KEEP | boomerang-io/flow/issues/366 |
| 375 | 2023-01-26 | Scaffold - Refactor and consolidate Error Handling across services | DONE | closed completed — `RestErrorResponse` in lib-common |
| 379 | 2023-03-21 | UI - Add Wizard / Accessible controls | KEEP | boomerang-io/flow/issues/368 |
| 381 | 2023-03-21 | Engine - Add the ability to enable / disable / archive (delete) a Work | DONE | closed completed — `active/inactive/deleted` + tombstone delete |
| 382 | 2023-03-21 | Engine - Update acquireTaskLock with prefix for Team or User context s | DONE | closed completed — `TaskLockEntity` `_id` is the workspace-scoped key |
| 393 | 2023-04-04 | Workflow - Convert User to new Entity in Loader | DONE | closed completed — loader `_0008__V3MigrateUsers` |
| 394 | 2023-04-10 | Workflow + Engine - Migrate creationDate to be auto created via Mongo  | DUP | dup of boomerang-io/flow/issues/325 |
| 398 | 2023-04-14 | UI - Reimagine + Simplify the implementation | KEEP | boomerang-io/flow/issues/369 |
| 405 | 2023-04-24 | Web + Workflow - Adjust UI Param (AbstractParam) model | DONE | closed completed — v4 `AbstractParam` |
| 407 | 2023-06-21 | The workflow activity logs exist the  syntax/Type error | AGED | closed not planned — legacy worker; file in boomerang-io/tasks if still seen |
| 408 | 2023-07-04 | Workflow + Engine - TaskTemplate Changelog author can be more than a u | KEEP | boomerang-io/flow/issues/370 |
| 411 | 2023-08-18 | Emails / Notifications | DUP | dup of boomerang-io/flow/issues/353 |
| 412 | 2023-08-18 | UI - Add Editor tab to Workflow to show the code view (same as TaskTem | KEEP | boomerang-io/flow/issues/371 |
| 415 | 2023-08-18 | UI - Implement updated Tutorial and hook into the Cards on the Home pa | KEEP | boomerang-io/flow/issues/372 |
| 416 | 2023-08-18 | Workflow - Better mapping of Params to Config (AbstractInput). | DUP | dup of boomerang-io/flow/issues/377 |
| 421 | 2023-10-27 | UI + Workflow + Engine - Review Params + Config elements | DUP | dup of boomerang-io/flow/issues/377 |
| 422 | 2023-10-27 | UI - Cleanup | DONE | closed completed — T8 (MSW, vitest) + `e2e/` Playwright |
| 424 | 2023-11-10 | Workflow + Engine - Optimise Indexes and relationships. | DONE | closed completed — E3 loader-managed indexes |
| 425 | 2024-04-12 | Web + Engine - Ability for user to control task dependencies being AND | KEEP | boomerang-io/flow/issues/373 |
| 427 | 2024-04-23 | UI + Workflow - Fix the Slack integration | KEEP | boomerang-io/flow/issues/374 |
| 429 | 2024-04-24 | UI + Workflow - Audit Endpoints and screen | KEEP | boomerang-io/flow/issues/375 |
| 431 | 2024-08-07 | UI - WorkflowRun Detail - Task Log ordering | KEEP | boomerang-io/flow/issues/376 |
| 437 | 2024-08-07 | Workflow + Engine - Fix up the use of config vs params | KEEP | boomerang-io/flow/issues/377 |
| 444 | 2025-05-24 | UI - Actions table should show Workflow Display Name not just slug | KEEP | boomerang-io/flow/issues/378 |
| 448 | 2025-06-25 | Fix OpenAPI doc generation | DONE | closed completed — springdoc 3 (Phase 0) |
| 449 | 2025-06-25 | Workflow + Engine - Clean up the WorkflowRun model | KEEP | boomerang-io/flow/issues/379 |
