# API Contract Trace — webapp ↔ service-core

**Status: 🔵 ACTIVE (2026-08-18).** Produced during T8 after repeated single-field patches
(`Task.config`, `?team=` vs `?workspace=`, quota field names) proved the mismatches were systemic.
Every frontend call was traced **call site → URL builder → controller route → service → response
model → serialised fields**, rather than checked at the route level. Route-level checks miss the
expensive class of defect: the call succeeds, the shape is wrong, and the UI silently shows
nothing.

**Method.** Four domain sweeps (workspace, workflow/run/task, identity/auth, schedule/integration)
against `io.boomerang.api.**` and `client-web/src/Config/servicesConfig.ts`. Findings below are
marked **[verified]** where re-checked in source directly, **[reported]** where they rest on the
sweep alone.

## 1. Live defects — fixed during this track

| Area | Defect | Consequence |
| ---- | ------ | ----------- |
| Actions | `ApproveRejectActions` read `approver.actioned`/`.actionDate`; the API sends `approved`/`date` (and the frontend's own `SimpleApprover` type declared them correctly) | **[verified]** `undefined` is falsy, so **every approver rendered as "Rejected"** regardless of their real decision, and the timestamp column was always `---`. An audit-trail defect in an approval product. |
| Quotas | `RestoreDefaults` read four field names absent from `Quotas` | **[verified]** 4 of 5 rows showed `undefined`; the storage row reused the monthly-executions value. |
| Feature flags | Frontend read `workspace.*`; the settings serve `team.*` | **[verified]** All four workspace features were **permanently hidden regardless of admin configuration**. Self-inflicted by the T7 rename — see §5. |
| Task upgrade | `TaskUpdateModal` read the removed `Task.config` | **[verified]** Modal threw on render. Fixed earlier in-track. |

## 2. Blocked capability — features that cannot work at all

These are not degraded, they are dead. Ordered by user impact.

1. **No admin can change a role or delete a user from the webapp. [verified]**
   `UserControllerV2`'s GETs accept `{session, user, key, global}`, but `PATCH /user/{userId}` and
   `DELETE /user/{userId}` accept **only `{global}`**. A browser session is always
   `AuthScope.session`, so Change Role, Delete User, and self-service Delete Account always 401 —
   only a raw `bfg_` key could satisfy them. The asymmetry with the GET routes reads as an
   oversight, not intent. **Backend fix; highest priority in this document.**
2. **Workspace and Workflow token tabs cannot list or create. [reported]**
   They send `type: "workspace"` / `"workflow"`; the restructure retired both. `"workspace"` fails
   `AuthScope.valueOfLabel` → `TOKEN_INVALID_REQ`; `"workflow"` isn't an enum constant at all, so
   the *list* query fails Spring's parameter binding with a 400. Both now map to `key`, with actor
   kind carrying what `workflow` used to encode. **Frontend fix — see §4.**
3. **Editing any global parameter fails in the normal case. [reported]**
   `CreateEditParametersModal` sends `updatedDiff(initial, next)` — only changed fields. But
   `ParameterService.update` looks the record up by `name`, and `name` is precisely the field that
   does *not* change on a typical edit, so it is omitted → `PARAMS_INVALID_REFERENCE`.
   **Frontend fix:** send the whole object, or always include `name`.
4. **Newly created Workflow Templates are not persisted. [verified — no `save()` in the method]**
   `WorkflowTemplateService.create` builds, validates, and returns a plausible object with a 200,
   but never calls `wfTemplateRepository.save(...)`. `apply()` delegates first-time creates into
   `create()`, so the first version is lost on both paths. **Backend fix.**
5. **Removing a workspace label is a no-op that reports success. [verified]**
   `WorkspaceService.patch()` does `getLabels().putAll(request.getLabels())` — additive only, never
   removes. Worse, the `!isEmpty()` guard means deleting your *last* label skips the block
   entirely. The frontend already sends the correct full desired map. **Backend fix, but it changes
   PATCH from merge to replace semantics — an API contract decision, hence deliberately not applied
   here.**
6. **Workspace search does nothing. [reported]** The search box updates the URL, but the query
   builder forwards only `order/page/limit/sort` — the term is dropped before the request is built.
   And the backend has no free-text search param to receive it. **Two changes, both sides.**
7. **The Editor's version switcher shows the wrong definition. [reported]**
   `composeGet` ignores its `version` parameter and always resolves latest, so selecting an older
   version — and the historical-run DAG view, which passes the run's version — both render the
   current definition. **Backend fix.**
8. **Every successful workspace-task delete shows an error. [reported]**
   `WorkspaceTaskService.delete` lacks a `return`/`else`, so it falls through and throws
   `TASK_INVALID_NAME` after deleting successfully. **Backend fix.**
9. **Workflow Template "Export" 404s. [verified — route commented out]** The card has a live Export
   menu item; the route is commented out server-side. Inherited v4 debt (`4dc06234`), not a merge
   regression. **Remove the button or restore the route.**

## 2b. Schedule / integration domain

**Schedule labels are broken end to end. [reported]** Labels are discarded on save (both create and
edit), never render in `SchedulePanelDetail`, render empty in `SchedulePanelList`, and the search
keys are array-shaped against what is actually a `Record<string,string>` map. The map-treated-as-array
confusion recurs at every layer — the same root cause as the confirmed edit-time labels loss.

**The frontend did not know a schedule could be `completed`. [verified — fixed]** Backend
`WorkflowScheduleStatus` has six values; the TS union had five, omitting `completed`. A `runOnce`
schedule becomes `completed` once it fires — the single most common one-shot case — so it rendered
with no status label (the `Record<ScheduleStatus,string>` had no entry) and could not be filtered.
Widened the union and added the label; the `Record` type then enforced the map update, which is the
type system doing exactly the job T8-0 was built for.

**Disabling any non-GitHub integration would unlink the wrong thing. [verified]**
`IntegrationCard.tsx:23` hardcodes `useMutation(resolver.postGitHubAppUnlink)` and calls it
regardless of `data.name` — so a Slack card's "Disable" posts the Slack entity's `ref` to the
**GitHub** unlink endpoint. Latent today only because Slack templates are seeded inactive and never
returned; it becomes live the moment a second integration type ships. Directly relevant to the
parked Slack redo.

**`GHLinkRequest.team` is the DD-01 outlier. [verified]** The frontend already sends `{workspace,
ref}` — correct post-rename. The backend model still declares `team`, read in `GitHubService`'s link
and unlink paths. **The backend is what should change**, not the frontend. Whether the mismatch
currently manifests as a hard 400 or a silent null is *unconfirmed*: this codebase is on Jackson 3,
`GHLinkRequest` lacks the `@JsonIgnoreProperties(ignoreUnknown = true)` its sibling DTOs opt into,
and no global override was found — both outcomes point to the same fix.

**Also reported:** the webhook/event trigger sends `?workflow=` where the backend reads `?ref=`;
`ScheduleStatus` filtering and cron-dialect concerns noted above. **Verified as NOT a bug:**
`Schedule.workflow` is absent from the wire by design (only `workflowRef` is sent) and is joined
client-side in `Schedules.tsx` — worth stating because it has exactly the shape of the
missing-field defects elsewhere in this document.

*Confidence note:* date-field serialisation (ISO-8601 vs epoch) was **inferred from the absence of
Jackson configuration, not runtime-verified**. Confirm with a live `GET /schedule/{id}` before
relying on it.

## 2c. GitHub integration — rebuilt (2026-08-18)

**The redirect mechanism was never the problem.** Flow already round-trips the workspace through
GitHub's `state` parameter, and CHEER does it with character-for-character the same two lines
(`btoa(workspaceSlug)` out, `atob(state)` back). Neither asks the user which workspace they meant.
The complexity worth removing was the *shape* around it, and the defects inside it.

**Five verified defects in the old implementation:**
- `linkAppInstallation` read `request.getTeam()` twice while the frontend sent `{workspace, ref}` —
  the rename never reached `GHLinkRequest`, so the workspace ref was null on both the duplicate
  check and `createEdge`. **Linking was broken outright.**
- `unlinkAppInstallation` did `findById(request.getRef())` — a Mongo `_id` lookup using a GitHub
  installation id, so it never matched. **Unlink was a silent no-op.** (`link` correctly used
  `findByRef`; the two were asymmetric.)
- `installations.get(0)` in three places — `getInstallation`, `getInstallationForWorkspace`,
  `linkAppInstallation` all built a client for the requested installation and then used the *first*
  one. With more than one installation, every one operated on the wrong org.
- `/github/unlink` carried **no `@AuthCriteria`**, which in this codebase means unauthenticated.
- `state` was `btoa(workspaceName)` — encoding, not signing, and therefore forgeable.

**Why the last one mattered more than it looked.** The app client is constructed *from* the
installation id and then lists repositories, so a forged state plus an arbitrary installation id is
cross-tenant **data access**, not just a mislabelled record. GitHub's documentation is explicit:
*"Bad actors can hit this URL with a spoofed installation_id. Therefore, you should not rely on the
validity of the installation_id parameter"* — and recommends verifying via a user access token for
the installer. **CHEER shares this hole and is worse on the write path** (its upsert takes the
workspace slug as a trusted path variable with no membership check at all), so it was studied for
shape, not copied for security.

**Ruled design.** Server-issued signed state (HMAC, workspace + user + TTL) appended to the link at
the point that already substitutes `{app_name}`, so the frontend never constructs a URL. A single
backend callback then verifies the state, asserts workspace membership, exchanges the OAuth `code`
for a user access token and confirms the installation appears in `GET /user/installations`, persists
against the real installation id, and **302s** to the workspace's integrations page. Both unlink
paths are kept: fixed self-service, plus GitHub's `installation.deleted` webhook with proper
`X-Hub-Signature-256` verification.

Net shape: the frontend `Connect` page, the `GitHub.tsx` link mutation, and the separate
`/github/link` POST all disappear — three moving parts replaced by one redirect. **Deployment is not
code-only**: the GitHub App needs its Setup URL repointed, "Request user authorization (OAuth)
during installation" enabled so a `code` is returned, and new client id/secret settings populated.

## 2d. Workflow Templates — demoted to static content (ruled 2026-08-18)

**They were not gone.** The premise "we got rid of Workflow Templates" was checked and is false:
`Home.tsx` renders a "Get Started With A Template" section for **every** user, and `App.tsx` gates
the entire app's loading *and* error state on the templates query — if it fails, nobody gets past
the spinner. Every install also holds data (loader seeds a starter set; v3 installs migrate their
`scope=template` workflows in).

**But the consumption path never treated them as an entity.** `WorkflowTemplateHomeCard` doesn't
call any template endpoint to instantiate — it spreads the template's fields into an ordinary
`POST /workspace/{workspace}/workflow`. Templates were already just seed content; only their
*storage* pretended otherwise.

**Ruling: templates become static content shipped with the product**, imported into a workspace
exactly like create-from-JSON/YAML. Served read-only from the backend's resources folder rather
than embedded in the UI bundle — the frontend keeps its existing query URL (no churn), templates
stay one artifact for any client, and an install-specific override via a mounted file stays possible.

*Removed:* the `workflow_templates` collection, `WorkflowTemplateEntity`/repository, the CRUD
routes (`POST`/`PUT`/`DELETE`), `PermissionResource.WORKFLOWTEMPLATE` and its `@AuthCriteria`
wiring, the admin surface (`Features/TemplateWorkflows`, `Components/WorkflowTemplateCard`,
`CreateWorkflowTemplate`), and the loader's template seed + v3 extraction.
*Kept:* a read-only query endpoint reading resource files, the Home-page consumption, and the
`WorkflowTemplate` wire shape.

**Legacy data — export and retain, do not destroy.** The v3 extraction's own javadoc records
exactly **2** `scope=template` workflows in the dump it was verified against. Those are exported for
manual uplift into resource files, and the existing collection is **retained as an archive rather
than dropped**, so the demotion is reversible until the maintainer confirms the uplift. Dropping it
is a separate, later step.

*Note:* this retires two defects fixed in the interim — the missing `save()` in
`WorkflowTemplateService.create` (every "Import new Workflow Template" showed success and lost the
data) and the Export menu item pointing at a route commented out since `4dc06234`. Both fixes were
correct for the code as it stood and cost little.

## 3. Security findings

- **TaskRun log streaming has no ownership check at any layer. [reported]** The workspace check in
  `WorkspaceTaskRunService.streamLog` is a commented-out TODO, and `TaskRunService.streamLog` does a
  bare `findById`. Any caller holding `TASKRUN/READ` on *any* workspace can stream *any* run's logs
  by ID. This is the only route in the run domain with no `/workspace/{workspace}/` segment.
  Belongs with the A2 enforcement work.
- **Admin nav is inconsistent with the route guard. [reported]** The frontend gates `/admin/*` on
  `elevatedUserRoles = [Admin, Operator]`, but the backend's `isCurrentUserAdmin()` also treats
  `auditor` and `author` as admin-equivalent when building navigation — so those users see admin nav
  items the frontend then blocks.

## 4. What the token UI must become

Backend truth: `AuthScope {session, user, key, global}` is the token **class**;
`TokenActorKind {SERVICE, AGENT, WORKFLOW}` is an **orthogonal** badge; `PermissionScope`
(global/workspace) is the **grant** scope and is always derived server-side, never client-settable.
`session` can never be minted via the API. A `key` token's grant is always workspace-scoped and
cannot be widened.

Therefore: `TokenType` becomes exactly the creatable classes `{User:"user", Key:"key",
Global:"global"}`; the Workspace-tokens and Workflow-tokens screens both send `type: "key"` (their
`principal` stays the workspace/workflow name, which is what `key` grants key off, so behaviour is
unchanged); the Workflow screen optionally sets `actorKind: "WORKFLOW"` to match how the scheduler
mints the same shape. The create form is otherwise already correctly shaped. The `Token` type
should also gain `actorKind`/`createdBy`/`lastUsedAt` — the backend already sends them for audit
visibility and nothing renders them.

## 5. Rename hazard — a vocabulary rename must stop at the wire

The T7 Team→Workspace rename changed `feature["team.*"]` to `feature["workspace.*"]`. Those keys are
**persisted settings values**, not code identifiers — the backend still serves `team.*` and the
loader seeds `teamQuotas`/`teamParameters`/`teamManagement`/`teamTasks`. Result: four features
silently off for everyone. Reverted in the app, the test setup, and the fixture, with a comment
explaining why those particular strings do not follow the rename.

**Rule:** persisted config keys, enum wire values, and query-parameter names are wire contract. A
rename may only cross that boundary together with a migration. The same discipline already caught
the vendor `User.teams` field; this is the settings-layer instance of it.

## 6. The permissions finding — T8-4 starts on the backend

**No endpoint returns a user's resolved permissions.** `UserProfile.permissions` walks only
*workspace* `MEMBER_OF` roles, so an admin's global grant is absent unless they also hold explicit
memberships; the strings are pre-flattened with no scope discriminator and no per-workspace
grouping. The real `List<ResolvedPermissions>` (`scope`/`principal`/`actions`) exists only inside
the server-side security context for the duration of one request — rebuilt per call, never
returned.

So "gate the UI off real permissions" has **no data source today**. T8-4 must begin with a backend
endpoint (e.g. `GET /profile/permissions`, or folding a scope-tagged `ResolvedPermissions` list into
`Profile`) computed the way `createSessionToken` computes it — global-role-aware, not just
workspace-role-aware. Note also that resource/action enforcement is still shadow-mode, so the UI's
implicit fallback of "the backend will 403 me" does not hold either.

## 7. Auth/session — what a login flow would require

`AuthenticationFilter` never reads cookies. It resolves identity from a `Bearer bf[gkus]_` token, an
`access_token` param, a raw OIDC JWT ("populated by the app via OAuth2_Proxy"), Basic auth, or
`x-forwarded-email`/`x-forwarded-user` — minting a **fresh session token per request**. There is no
persistent browser-visible credential. The webapp sends no `Authorization` header at all, and the
dev proxy attaches a static JWT from an env var precisely because there is no in-repo way to obtain
one. An unauthenticated request gets a bare 401 with no `WWW-Authenticate` and no redirect hint.

Compounding it: `fetchUserResolver` special-cases only HTTP 423, so a **401 resolves to `undefined`**
rather than rejecting — `isError` stays false, the render gate never opens, and the app shows a
**blank page forever** with no error and no login prompt.

For standalone to be usable this needs: a decision on what fronts it, a real login/callback route
that mints and returns a browser credential, and 401 handling on the frontend. Confirms T7-F1 with
mechanism.

## 8. Cleanup — dead on both sides

Seven endpoint builders point at routes that do not exist (workspace labels ×2, quota reset,
workspace parameter create/update ×2 — one of which references a `serviceUrl.getWorkspaceParameter`
that was **never defined** and would throw if called — and approver-group POST/PUT). Zero call
sites. Also dead: `Actions.tsx` sends a `workspaces=` param the backend never reads (scoping is
already via the path); `FlowWorkspace.description` has no backend representation at any layer;
`Action.workspaceRef` is never sent; `WorkflowTemplate.config` is typed required but does not exist.

Backend capability with no UI at all: **`start`/`pause`/`resume`/`finalize` on WorkflowRun are fully
implemented and completely unwired** — pause/resume in particular is a committed feature with no way
to trigger it.

## 9. Open decisions for the maintainer

1. **Label PATCH: merge → replace, or a delete-by-key route?** Removal is broken either way today.
2. **`phase` on the wire. [verified]** It *is* serialised on both `TaskRun` and `WorkflowRun`,
   against the status-only invariant — and the approval/manual UI depends on `TaskRun.phase` with no
   status-based fallback, while `WorkflowRun.phase` is never read. Narrowing the invariant to
   `WorkflowRun` is the cheap resolution; stripping `TaskRun.phase` needs a public replacement first.
3. **Approver groups match by name, not id** — renaming a group during edit silently creates a
   duplicate and orphans the original. Needs an id-first match, and the frontend to send `id` rather
   than `groupId`.
4. **`Action.workspaceName` is never populated** (the field is still `teamName`), so the Workspace
   field in every approval modal is permanently blank.
5. **`WorkspaceType` is write-once-blind** — accepted at create, ignored on patch, never returned.
6. **Another user's workspace memberships cannot be fetched**, so the admin User Detail "Workspaces"
   tab renders the *viewer's own* workspaces for every user.

## Unverified

`WorkflowService.apply` was reported to drop `displayName`/`icon` on update; the cited lines are in
`create()`, so the claim is **not confirmed** and should be re-checked before anyone acts on it.
Spring's `Page<T>` serialisation was not re-derived against the frontend's generic `Pageable<T>`.
