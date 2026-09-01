# Framework-vs-Rolled-Own Review — Fix Wave and Follow-On Wave

**Status: 🟢 FIX WAVE COMPLETE (2026-09-01, branch `feat-v5-framework-wave`, worktree off `feat-v5-track10`, unmerged, not pushed). Follow-on wave (§3) not started; A7/A8/A10/A11/A16 await the maintainer’s decision on the proposals; C4 awaits an answer.**

Source: the 2026-08-31 review of industry frameworks vs hand-rolled implementations. Every item below
is dispositioned by the maintainer (2026-09-01). This file is the single tracker — update the Status
column in place; do not fork a second list.

Status legend: ✅ DONE (committed on this branch, hash cited) · 🟡 IN PROGRESS · 📝 PROPOSED (before/after
shown in `framework-review-proposals.md`, not applied) · 🐙 GITHUB ISSUE (number cited) · ⏸️ DEFERRED
(grouped into the follow-on wave, §3) · ❓ AWAITING DECISION.

Gates: **G1** — this wave touches `TaskExecutionService` in exactly one method (A20, `Calendar.HOUR`
→ `java.time`), committed on its own; `DAGUtility` is not touched. **G2** — no data-model changes
(fields, indexes, collections, migrations) in this wave.

## 1. Backend (section A of the review)

| # | Concern | Where | Disposition | Status |
|---|---|---|---|---|
| A1 | Authorization via `HandlerInterceptor` regex, not `@PreAuthorize` | `core/security/SecurityInterceptor.java:21,39,77-80` | Defer → **Security** | ⏸️ DEFERRED |
| A2 | Empty `GrantedAuthority` list on every `Authentication` | `core/security/AuthenticationFilter.java:219,248,271,301` | Defer → **Security** | ⏸️ DEFERRED |
| A3 | Bearer JWT parsed without signature verification | `core/security/AuthenticationFilter.java:181-186` | Defer → **Security** (live security defect — first item of that wave) | ⏸️ DEFERRED |
| A4 | Hand-rolled Basic auth, non-constant-time compare | `core/security/AuthenticationFilter.java:225-253` | Defer → **Security** | ⏸️ DEFERRED |
| A5 | Hand-rolled anonymous filter | `core/security/UnauthenticatedGlobalAuthenticationFilter.java:31-46` | Defer → **Security** | ⏸️ DEFERRED |
| A6 | `AES/CBC` with static IV | `core/model/AESAlgorithm.java:20,24,37,54` | Defer → **Security** | ⏸️ DEFERRED |
| A7 | No Bean Validation; inline regex checks diverged | `TaskService.java:90`, `WorkflowTemplateService.java:45`, `WorkflowService.java:1572-1600`, `WorkspaceService.java:546-553` | Ruled 2026-09-01: **apply** (shared `ResourceName.REGEX` + `@Valid` edge + `handleMethodArgumentNotValid` → `RestErrorResponse`) | ✅ DONE — `e9ffac499` (`ResourceName.REGEX` + `messages.properties:41` + lib-common test), A7 edge commit (`@ResourceName`/`@ParamName`, `@Valid` ×6, `handleMethodArgumentNotValid`; 3 MockMvc cases), constant swap in both services |
| A8 | Zero `@ConfigurationProperties`; ~110 `@Value` | `EncryptionConfig.java`, `FlowSecurityProperties.java`, `WorkflowWatcher.java:81-86`, `RestConfig.java:42-46` | Ruled 2026-09-01: issue | 🐙 boomerang-io/flow#325 |
| A9 | Hand-stamped `creationDate`, no Spring Data auditing | 6 sites (`TokenService:632` …) | Investigate: usable on single-instance free MongoDB and DocumentDB? — **Yes to both**: auditing is client-side (`AuditingEntityCallback`, a `BeforeConvertCallback`), no server feature needed. Caveat: callbacks fire only on `save`/`insert`, not on the engine’s 36 `Update`-based writes, so `@CreatedDate` fits the 6 creation sites but `@LastModifiedDate` MUST NOT go on CAS-mutated entities. Detail in `framework-review-proposals.md` §A9 | ✅ INVESTIGATED (📝 proposal) |
| A10 | Hand-built `PageRequest` ×9; wrong `totalElements` | `PageableExecutionUtils.getPage(list, p, () -> list.size())` ×8 | Ruled 2026-09-01: **apply step 1** (9 count fixes + `TokenService:507` `ActionEntity` typo, no wire change); step 2 `Pageable` adoption = issue | ✅ step 1 DONE `14a026a68` (9 sites → `mongoTemplate.count(Query.of(query).skip(-1).limit(-1), Entity.class)`; `TokenService` now counts `TokenEntity`; 2 Testcontainers tests, one proven to fail on the old code) · 🐙 boomerang-io/flow#328 (step 2) |
| A11 | 12 copies of the `List<Criteria>` filter block | 9 services | Ruled 2026-09-01: issue (carries the `UserService:386` bug and the `ActionService` `lte`/`lt` ruling) | 🐙 boomerang-io/flow#326 |
| A12 | Non-thread-safe `HashMap` cache | `core/audit/AuditInterceptor.java:34,167,187` | Defer → **Cache** | ⏸️ DEFERRED |
| A13 | JWKS cache without TTL/rotation | `core/security/OidcTokenVerifier.java:60-62,133-153` | Defer → **Cache** | ⏸️ DEFERRED |
| A14 | Two hardcoded 200-thread pools beside virtual threads | `core/config/AsyncConfiguration.java`, `engine/config/AsyncConfig.java` | Defer → **Threads** | ⏸️ DEFERRED |
| A15 | 3 JSON libraries / 2 Jackson majors / ad-hoc `new ObjectMapper()` | event models, `WebhookEventService`, `GenericStatusEvent`, dispatcher Gson sites | Fix — Jackson-2 stragglers → Jackson 3 `47ed94372`; Gson → Jackson 3 `b78d5a60c` (gson removed from `service-core` pom); 13 ad-hoc `new ObjectMapper()` → injected bean / one static `JsonMapper` `eb51020cc`; dispatcher Gson → fabric8 `Serialization`/Jackson `92e6bc35d` `db5a2815b` (gson removed from dispatcher pom). **Residue**: `jackson-databind` 2.x stays on `service-core` — `cloudevents-json-jackson:4.0.2` (`PojoCloudEventDataMapper`) and `json-path` (`JacksonMappingProvider`) expose Jackson-2-only APIs; `TaskExecutionService:69` mapper untouched (G1). | ✅ DONE |
| A16 | Reflective `ConvertUtil` + 47 `BeanUtils.copyProperties` | `workflow/ConvertUtil.java:35-52` | Ruled 2026-09-01: issue | 🐙 boomerang-io/flow#327 |
| A17 | `Watcher` + `CountDownLatch` per task; `System.exit(1)` on watch close | `TektonServiceImpl.java:509-537`, `KubeJobsExecutor.java:348-356`, `TaskWatcher.java:109-114` | Defer → **Threads** | ⏸️ DEFERRED |
| A18 | Loki client bypasses `RestConfig`; raw `HttpClients.createDefault()` | `service-dispatcher/.../dispatcher/LogService.java:68-153` | GitHub issue, low priority | 🐙 boomerang-io/flow#322 |
| A19 | Unencoded query string | `engine/LogClient.java:51-54` | Fix — `UriComponentsBuilder` + `LogClientTest` | ✅ DONE `5e927e792` |
| A20 | `java.util.Calendar` + `Calendar.HOUR` (12-hour) | `engine/TaskExecutionService.java:856-882` | Fix (G1: `runScheduledWorkflow(TaskRunEntity, WorkflowRunEntity)` only, own commit). Semantics preserved except the 12h→24h fix; invalid zone id falls back to UTC as before | ✅ DONE `c2e7255cc` |
| A21 | `@Async` on a private self-called method | `service-dispatcher/.../dispatcher/TaskService.java:102-109` | Defer → **Threads** | ⏸️ DEFERRED |
| A22 | Manual stream-copy loops; manual hex | `KubeLogService.java:96-119`, `LogClient.java:88-93`, `TokenService.java:374-392` | Fix — `transferTo` in `LogClient` + `KubeLogService` (also fixed the `> 0` zero-read early-stop), `HexFormat` in `TokenService` pinned to SHA-256("abc") | ✅ DONE `82905d44b` `ef77e1bef` |
| A23 | Unused `spring-retry` dependency | `service-core/pom.xml:193-197` | Fix | ✅ DONE `2aca8ca53` |

## 2. Web client (section C of the review)

| # | Concern | Where | Disposition | Status |
|---|---|---|---|---|
| C1 | `lodash` imported at 36 sites, undeclared | `client-web/package.json` | Fix | ✅ DONE `b3db7f304` |
| C2 | Dead imports of uninstalled packages (`react-tracked`, `axios-mock-adapter`) | `src/State/reducers/{app,editor}.ts`, `src/Utils/{mocks,testing}/axios.ts`, `src/Utils/testing/context.tsx` | Fix (`Utils/index.ts` kept — 7 live importers via the `Utils` vite alias) | ✅ DONE `03c4978e3` |
| C3 | Four hand-written duration/date formatters beside `moment` | `src/Utils/dateHelper.ts`, `timeHelper.ts`, `timeSecondsToTimeUnit.ts` | Fix — characterisation spec (44 cases) pinned outputs before and after | ✅ DONE `6369ba9b0` |
| C4 | Duplicated validation regexes / vendored `isUrl` | `LabelModal.tsx`, `CustomLabel.tsx`, `Utils/isUrl.ts`, `Constants/index.ts:22` | Maintainer question: yup vs another validator? | ❓ AWAITING DECISION |
| C5 | Two parallel route tables; unencoded query string | `Config/appConfig.ts:80,162,210` | Defer → **Web follow-on** | ⏸️ DEFERRED |
| C6 | Ad-hoc `{ok, errorMessage}` action envelope (47 reads / 30 files) | route `action`s + call sites | Fix — new `Utils/actionResult.ts` (`ActionError`, `isActionError`, `actionError` over React Router `data()`); success returns the payload, failure returns `data(..., {status:400})` so the route stays mounted; 11 cluster commits `3cbb77be0`…`84cf1740f`. Out of scope: schedules cluster (still react-query `useMutation`), `Settled`/`settle` loader helper (not an action envelope) | ✅ DONE |
| C7 | Global `rtl*Render` test wrappers instead of `createRoutesStub` | `src/setupTests.tsx:134-137,192,228,298-301` | Fix — `Utils/testing/render.tsx` (`renderWithRouter`/`renderWithContext` on `createRoutesStub`); 49 specs migrated, `setupTests.tsx` −143 lines, ESLint globals removed; 6 commits `5985a7563`…`982613c8b` | ✅ DONE |
| C8 | `MutationObserver` modal detection | `src/Hooks/useIsModalOpen.ts`, `useMutationObserver.ts` | Fix — hook had 0 consumers and nothing ever set the class; deleted | ✅ DONE `5c49727a5` |
| C9 | Permission wildcard matcher written twice | `Utils/permissionHelper.tsx:36-45`, `CreateToken/Form/PermissionSelector.tsx:45-58` | Fix | ✅ DONE `f7e50effd` |

## 3. Follow-on wave (deferred, grouped)

| Group | Items | Entry condition / note |
|---|---|---|
| **Security** | A1, A2, A3, A4, A5, A6 | One coherent change: map `Token.permissions` → `GrantedAuthority` (A2), then `@PreAuthorize` replaces `SecurityInterceptor` (A1), `oauth2ResourceServer(jwt())` replaces the unverified parse (A3), `httpBasic()` (A4), `anonymous()` (A5), `Encryptors.delegatingText()` (A6). Sequence with the `SecurityInterceptor` enforcement flip (CLAUDE.md "What To Work On First" #3). A3 is a live defect: **MUST** lead the wave. |
| **Cache** | A12, A13 | Spring Cache (`@Cacheable`) for `AuditInterceptor`; nimbus `JWKSourceBuilder.cache().retrying()` or `NimbusJwtDecoder` for JWKS — the latter folds into Security A3. |
| **Threads** | A14, A17, A21 | Boot's virtual-thread `applicationTaskExecutor` for `@Async` (A14); fabric8 `waitUntilCondition`/informer replacing latch-per-task and the `System.exit(1)` (A17, also `gap-register.md` H18 / `runtime-contract.md` §2.5); public + self-proxied `deleteTaskRun` (A21). Q-005 virtual-thread measurement rides here. |
| **Web follow-on** | C5 | `generatePath`/`href` replacing the duplicated `AppPath`/`appLink` tables; `query-string` at `appConfig.ts:210`. |

## 4. Log

- 2026-09-01 — A18 raised as boomerang-io/flow#322.
- 2026-09-01 — wave opened; dispositions recorded; worktree `../flow.service.workflow-framework-wave` on `feat-v5-framework-wave`.
- 2026-09-01 — batch 1 landed: dispatcher (3 commits, 30/30 tests), client-web C1/C2/C3/C8/C9 (gates: vitest 273 passed / 8 pre-existing TZ snapshot failures, tsc 27 unchanged, build exit 0). Hygiene note: `92e6bc35d` (dispatcher) swept up 4 staged client-web deletions belonging to C3/C8 via a broad `git add`; tree state is correct, attribution is not. Not rewritten (agents live on the branch).
- 2026-09-01 — C4 answer: `yup` is a **peer dependency of `@boomerang-io/carbon-addons-boomerang-react`** (`formik ^2.4.6`, `yup >=0.32.11`), so it is pinned by the forms/add-ons stack, not a free choice; no spec records a move to another validator.
- 2026-09-01 — `framework-review-proposals.md` complete: A7/A8/A11 (`bbd2fd5fd`) + A9/A10/A16. Two new live bugs surfaced by the before/after passes and NOT yet fixed (awaiting the maintainer's call on A10/A11): `UserService.java:386` (`ids=` filter inert) and `TokenService.java:507` (counts `ActionEntity`).
- 2026-09-01 — backend fix set complete: service-core 303 tests / 0 failures (full module), dispatcher 30/0. Pre-existing quirk noted, not touched: `runScheduledWorkflow` removes param `workflowId` but reads `workflowRef`.
- 2026-09-01 — C6 landed (11 commits; `ok:` literals 206 → 21 residue, all out-of-scope; TZ=UTC vitest 281/281, tsc 27, build 0). C7 started.
- 2026-09-01 — C7 landed; fix wave complete. Final gates: service-core 303/0, service-dispatcher 30/0, client-web TZ=UTC vitest 281/281, tsc 27 (baseline), build exit 0. Stale doc noted: CLAUDE.md’s T8 note “21 redundant `revalidate()` of 25” — 6 call sites remain on this branch, each justified in-file.
- 2026-09-01 — maintainer ruling on the proposals: A7 + A10 step 1 applied on this branch; A8 → #325, A11 → #326, A16 → #327, A10 step 2 (Pageable, wire change) → #328.
- 2026-09-01 — A7 + A10 step 1 landed. Final gates: lib-common 4/4, service-core 308 run / 0 failures / 0 errors / 2 skipped (both pre-existing `@Disabled` cases in `E4AuditGapTest`, unchanged on this branch). Attribution of the swept tracker commit was repaired by a soft reset + path-limited recommit (no content change). Spec correction: `RunParam`/`ParamSpec` are standalone classes, not `AbstractParam` subclasses — the `@Valid` cascade reaches `TaskSpec.params` only.
