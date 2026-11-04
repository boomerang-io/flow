# Authentication and authorization

Every request to `service-core` passes two gates: `AuthenticationFilter` establishes *who* is calling (a
`Token` on the Spring `SecurityContext`), then `AuthCriteriaAuthorizationManager` plus `RelationshipService`
decide *what* that token may touch. One property switches both gates. Paths below are under
`service-core/src/main/java/io/boomerang/`.

## The switch: `flow.security.enabled`

One property gates both halves, and its default is `true`. `FlowSecurityProperties.isSecurityEnabled` returns
the property, `true` when it is unset (`core/security/FlowSecurityProperties.java:19-20`); an embedded engine
sets it to `false` explicitly, together with `flow.workspace.single=true` (`architecture.md`, "Deployment
shape"). Two mutually exclusive configurations key off it:

| Security | Filter chain | Identity on every request | Authorization |
| --- | --- | --- | --- |
| on | `SecurityConfiguration` — `AuthenticationFilter`, `anyRequest().authenticated()` (`core/security/SecurityConfiguration.java:24,88-118`) | the real `Token` the filter resolved | `AuthCriteriaAuthorizationManager` wired as a method-security `Advisor` by `MethodSecurityConfiguration` (same condition) |
| off | `SecurityDisabledConfiguration` — `anyRequest().permitAll()`, `http.anonymous(...)` (`core/security/SecurityDisabledConfiguration.java:13,18-33`) | a synthetic `UnauthenticatedGlobalToken` (see below) | the advisor is not registered; `RelationshipService` still runs but its `global` branch allows everything |

The dispatcher's `/dispatcher/**` chain (`dispatcher/DispatcherSecurityConfiguration.java:37-47`) is ordered
first and is independent of this switch — see "Dispatcher endpoints".

## Authentication: how a caller becomes a `Token`

`AuthenticationFilter` checks the identity sources in a fixed order and stops at the first one present
(`core/security/AuthenticationFilter.java:107-121`). Only `SecurityConfiguration` constructs it, so it runs only
when security is on (`core/security/SecurityConfiguration.java:24,91-93`). Every branch installs the resolved
`Token` as the `Authentication`'s details with an empty authority list: the `Token` is what every permission and
relationship check reads.

| Order | Source | Handled by | Result |
| --- | --- | --- | --- |
| 1 | `Authorization: Bearer bf?_…` (a Flow-minted token, `AuthenticationFilter.java:309-312`) | `getTokenAuthentication` (`AuthenticationFilter.java:286-306`) | shape gate `TokenTypePrefix.isFlowToken` (`AuthenticationFilter.java:292`), then SHA-256 hash lookup in `tokens` and expiry check (`core/TokenService.java:388-413`) |
| 1b | `Authorization: Bearer <JSON Web Token (JWT)>` (not Flow-shaped) | `getUserSessionAuthentication` (`AuthenticationFilter.java:197-244`) | the token is parsed for `emailAddress`/`email` and names as the proxy sent it, **not signature-verified**: the route relies on the authenticating proxy in front of `service-core`. A session token is minted for that email |
| 1c | `Authorization: Basic email:password` | same method (`AuthenticationFilter.java:245-275`) | password checked against `flow.authorization.basic.password` via `PasswordEncoder.matches` (constant-time; encoded once at filter construction, `AuthenticationFilter.java:89`); session token minted for the email |
| 2 | `x-access-token` header | `getTokenAuthentication` | as row 1 |
| 3 | `?access_token=` query parameter | `getTokenAuthentication` | as row 1; kept for webhook senders that cannot set headers (`AuthenticationFilter.java:50-57`) |
| 4 | `x-forwarded-email` / `x-forwarded-user` (an authenticating proxy) | `getGithubUserAuthentication` (`AuthenticationFilter.java:317-341`) | session token minted for the forwarded email |
| 5 | `flow_session` cookie | `getTokenAuthentication` (`AuthenticationFilter.java:119-120,161-172`) | the opaque `bfs_` value minted by `POST /internal/v1/auth/exchange` |

Rows 1b, 1c and 4 mint through `TokenService.createSessionToken`, which reuses one persisted session per
normalised email for 60 seconds per instance (`core/TokenService.java:72,563-594`). User creation is allowed
only on `/api/v2/profile` and the exchange path; activation only on `/api/v2/activate` and the exchange path
(`core/security/AuthenticationFilter.java:185-195`).

Rows 1, 2, 3 and 5 resolve the bearer through `TokenLookupCache` (`core/security/TokenLookupCache.java:24-54`), a
per-instance Caffeine cache keyed by the stored SHA-256 hash and holding the validated `TokenEntity`, so the
filter's `validate` followed by `get` costs one repository read (`core/TokenService.java:394-413`). Only a
present, unexpired entity is cached — misses are never cached — and the entity's `expirationDate` is re-checked
on every hit. Entries live 60 seconds and the cache holds at most 10 000 (`flow.security.token-cache.enabled`,
`flow.security.token-cache.ttl`, `flow.security.token-cache.max-size`,
`service-core/src/main/resources/application.properties:26-28`). `TokenService.delete` evicts the token's hash
and `deleteAllForPrincipal` flushes the cache (`core/TokenService.java:463-480`); `touchLastUsed` writes only the
`lastUsedAt` field and does not evict (`core/TokenService.java:430-451`). Eviction is per instance: after a revoke
on one instance, another instance MAY still honour the token until its own entry ages out, at most 60 seconds.

No identity → `AUTH_REQUIRED` (HTTP 401) via `DelegatedAuthenticationEntryPoint`
(`core/security/AuthenticationFilter.java:132-136`), except on `/internal/v1/auth/exchange`, which continues so
the controller can verify an OpenID Connect (OIDC) id_token itself (`:127-131`). Paths that skip the filter:
`/error`, `/health` and its probe groups `/health/**`, `/api/docs`, the GitHub App callback, `/internal/v1/auth/config` and the four Slack routes
that check the request themselves (`/api/v2/integration/slack/{auth,commands,interactivity,events}`,
`core/security/SecurityConfiguration.java:39-48`) (`:345-353`); `SecurityConfiguration.java:106-111` permits the
same paths plus `/info`, `/webjars` and the exchange endpoint.

### Session sign-in (the browser flow)

`InternalAuthControllerV1` (`core/InternalAuthControllerV1.java`) exposes three endpoints. They sit under
`/internal/v1`, outside the public `/api/v2`, because only the webapp's server calls them: a front door that
routes `/api/**` to `service-core` does not reach them. The prefix is a routing boundary, not a credential, so
a caller that reaches `service-core` directly reaches them as well.

| Endpoint | Auth | What it does |
| --- | --- | --- |
| `GET /internal/v1/auth/config` | public | Returns `mode` = `none` (security off), `oidc` (both the `auth.oidc.issuer` and `auth.oidc.clientId` settings set, by an admin or seeded from properties, below) or `proxy`, which carries `flow.signOutUrl` (`core/security/AuthExchangeService.java:51-64`) |
| `POST /internal/v1/auth/exchange` | public | Empty body: mints a session for the user the filter already resolved (`AuthExchangeService.java:80-96`). `{idToken, nonce}`: `OidcTokenVerifier.verify` checks the signature against the issuer's published signing keys, matches `iss`, `aud` and `nonce` exactly and requires an unexpired `exp` (`core/security/OidcTokenVerifier.java:83-134`); symmetric and `none` algorithms are rejected (`OidcTokenVerifier.java:99-109`). Either way the response sets an httpOnly, `Secure`, `SameSite=Lax` `flow_session` cookie (`core/security/SessionCookie.java:22-30`) |
| `POST /internal/v1/auth/logout` | `session` token | Deletes the token and clears the cookie (`core/InternalAuthControllerV1.java:96-107`, `AuthExchangeService.java:137-142`) |

The cookie carries only the opaque token; the webapp runs the OIDC redirect and logout from its own server
routes (`client-web/src/Features/Auth/oidc.server.ts`, `client-web/app/routes/authCallback.tsx`), so the
id_token never reaches the browser.

## Token kinds

`AuthScope` is the token **class**; `TokenActorKind` is an orthogonal badge for machine tokens; `PermissionScope`
(`global` | `workspace` | `user`) is the scope of each grant and is always derived server-side
(`core/TokenService.java:172-201`).

| `AuthScope` | Prefix | Holder | Grants | How it is minted |
| --- | --- | --- | --- | --- |
| `session` | `bfs_` | a signed-in human | re-resolved from the user's type and memberships: the global role for an admin or operator, otherwise a `user`-scoped self-service baseline (own profile, flags, context, navigation, templates, listing workspaces, and creating one when the `features` setting `workspaceCreate` allows) plus one workspace grant per membership (`resolvePermissionsForUser`, `TokenService.java:689-721`) | only by `AuthenticationFilter` or the exchange; `POST /api/v2/token` rejects it (`TokenService.java:121-123`) |
| `user` | `bfu_` | a human's long-lived personal token | copied from that user's workspace roles (`TokenService.java:176-186`) | `POST /api/v2/token` |
| `key` | `bfk_` | a machine — service, AI agent, or a workflow's own scheduler credential (`actorKind`) | always `workspace`-scoped; a `global` grant is refused (`TokenService.java:188-191,361-376`) | `POST /api/v2/token`; `createWorkflowSchedulerToken` for workflows (`TokenService.java:754-771`) |
| `global` | `bfg_` | platform admin or the dispatcher (`actorKind=SERVICE`) | one `global` grant | `POST /api/v2/token`, and only by a caller who already holds a `global` grant (`TokenService.java:148-150`); or at startup from `flow.security.bootstrap-token` or `flow.security.tokens` (below) |

**Bootstrap token.** A secured install where no person will sign in gets its first credential from
`flow.security.bootstrap-token`. When it is set — `bfg_` and at least 32 further characters, else the start
fails — `BootstrapTokenService` stores its hash at startup as a global `**/**` token with `actorKind=SERVICE`
under the fixed id `bootstrap-token` (`core/security/BootstrapTokenService.java:62-104`). A restart changes
nothing, instances starting together converge on one record, and a changed value replaces the hash and evicts
the old one from the local token cache; it is rotated by changing the value and restarting. It registers whether
or not security is on. Being a `global` token, it is refused by `/api/v2/profile`, which admits only `user` and
`session` tokens (`workspace/ProfileControllerV2.java:54,76`), so the webapp cannot sign in with it.

**Declared tokens.** `flow.security.tokens.<name>.*` declares narrower machine tokens so a hosted install needs no
minting step. The map key is the name, so the environment form carries it:
`FLOW_SECURITY_TOKENS_DISPATCHER_HASH`, `_PERMISSIONS`, `_ACTORKIND`, `_DESCRIPTION`, `_EXPIRY` declare `dispatcher`.
A name is 1 to 63 lowercase letters and digits and is not `bootstrap` (that is `FLOW_SECURITY_BOOTSTRAP_TOKEN`); a
hyphen or underscore cannot be used because an underscore in a variable name becomes a dot. The binder lowercases the
name from an environment variable and keeps a properties key as written, so an uppercase key is refused rather than
folded. An entry has exactly one of `hash` or `value`, `actor-kind` (default `SERVICE`), `permissions`, and optionally
`description` and `expiry` (an ISO-8601 instant, or a date meaning the end of that day in UTC). `hash` is the
lowercase hex SHA-256 of the raw token (`printf %s "$RAW" | sha256sum`, or `shasum -a 256` on macOS), the form
`TokenService.hashString` stores; `value` must be `bfg_` plus at least 32 characters, as for the bootstrap token.
Either way the raw token the client sends must begin `bfg_` to pass the shape gate below. Each permission is a
`<resource>/<action>` string checked by `TokenService.assertValidPermissions`, the check `POST /api/v2/token` runs, and
becomes one `global` grant with principal `**`. A bad entry fails the binding, so the start stops before the install
serves (`core/security/DeclaredTokenProperties.java:34-73`). A past `expiry` does not stop it: the token is stored and
refused like any expired token.
`DeclaredTokenService` upserts each entry on `ApplicationReadyEvent` under the id `declared-token:<name>`; an entry
unchanged since the last start writes nothing, a changed hash or grant replaces the record and evicts the old hash
from the local cache, and two entries sharing a token stop the start (`core/security/DeclaredTokenService.java:58-105`).
Every stored token whose id has that prefix and whose name is no longer configured is deleted and evicted, so removing
an entry revokes only it and a rotation is: add the new entry, roll the client, remove the old one. Declared tokens
are registered whether or not security is on. `TokenService.delete` refuses their ids with `TOKEN_DECLARED` (409), so
the API cannot fight the configuration (`core/TokenService.java:463-466`).

**OpenID Connect seed.** The migrations seed `auth.oidc.issuer` and `auth.oidc.clientId` empty, which leaves
sign-in in `proxy` mode. `flow.auth.oidc.issuer` and `flow.auth.oidc.client-id` (`FLOW_AUTH_OIDC_ISSUER`,
`FLOW_AUTH_OIDC_CLIENT_ID`) fill them on a fresh install: `OidcSettingsSeedService` runs on `ApplicationReadyEvent`,
after the migrations, and writes each property that is set through `SettingsService.setIfEmpty`, one conditional
Mongo update that matches only while the stored value is empty
(`core/security/OidcSettingsSeedService.java:49-69`, `core/SettingsService.java:194-211`). A value an admin set
through `PATCH /api/v2/settings`, or that another instance seeded first, is kept, and a later change to the
property does not overwrite it; the start logs at info that the stored value is kept. The issuer must be an
absolute `https` URL without credentials, `http` only for `localhost`, `*.localhost`, `127.0.0.0/8` and `::1`,
else the start fails (`OidcSettingsSeedService.java:80-108`). The auth config is read from the settings on every
request and is not cached, so it shows the value at once on every instance; the write also clears the writing
instance's parameter-layer cache, as `PATCH` does. The compose stack sets both properties for IDPZero
(`docker-compose.yml`, `application-local.properties`).

`TokenActorKind` is `SERVICE`, `AGENT` or `WORKFLOW` (`core/security/enums/TokenActorKind.java:22-26`), null on
human tokens. A `WORKFLOW` token's principal is the workflow's id, whether the editor's Configure › Tokens tab or
`createWorkflowSchedulerToken` minted it (`core/TokenService.java:754-760`); a workflow name is only unique within
its workspace. Deleting the workflow revokes every token of that principal (`workflow/WorkflowService.java:740`),
and deleting a workspace revokes every token whose principal is the workspace's name, its reference everywhere
(`workspace/WorkspaceService.java:375-376`). Only the SHA-256 hash of a raw token is stored (`core/TokenService.java:211-213,378-386`), and a
token that does not match `^bf[gkus]_.+` (`core/enums/TokenTypePrefix.java:35,57-59`) is rejected before any
database lookup on both paths — `AuthenticationFilter.getTokenAuthentication` for every source in the table above
(`core/security/AuthenticationFilter.java:292`) and `DispatcherAuthFilter` (`dispatcher/DispatcherAuthFilter.java:79`)
— so a retired `bft_`/`bfw_` bearer never reaches MongoDB.

Seeded roles (`service-core/src/main/resources/seed/roles.json`): global `admin` (`**/**`) and `operator` (`**/read`,
`**/write`, `**/action`); workspace `owner` (`**/**`), `editor` (`**/read`, `**/write`, `**/action`) and `reader`
(`**/read`). A permission string is `<resource>/<action>` with `**` as a wildcard on either side; resources and actions
are the `PermissionResource` and `PermissionAction` enums. A user the roles do not cover, one in no workspace, holds
only the self-service baseline (`TokenService.SELF_SERVICE_PERMISSIONS`), anchored to their own id, so the checks that
key on a `global` grant (another user's account, the audit trail, the token ceiling) never count it;
`WorkspaceService.create` refuses a user who is not an admin when the setting is off, since the route check is
scope-blind and a workspace editor's `**/write` would pass it.

## Authorization: two layers

### Layer 1 — `@AuthCriteria` at the endpoint

Every REST handler MUST carry one of two annotations, on the method or on its controller class.
`@AuthCriteria` declares `resource`, `action` and `assignableScopes` (`core/security/AuthCriteria.java:11-19`).
`@AuthExempt(reason = …)` is the explicit opt-out, with a mandatory reason, for a handler that needs no
permission rule (`core/security/AuthExempt.java:25-29`). A handler with neither is **denied**, so a route cannot
ship open by omission. `AuthCriteriaCoverageTest` scans every `io.boomerang` `@RestController` and fails the build
naming any handler without a decision.

Enforcement is Spring Security method security, not a `HandlerInterceptor`: `MethodSecurityConfiguration`
(`core/security/MethodSecurityConfiguration.java:51-70`) registers `AuthCriteriaAuthorizationManager` as an
`Advisor` — `AuthorizationManagerBeforeMethodInterceptor` over a `Pointcut` that matches every request-mapped
method of an `io.boomerang` `@RestController` (`RestHandlerPointcut`, `:73-85`; framework controllers such as
springdoc stay outside) plus any class or method carrying `@AuthCriteria` — via `@EnableMethodSecurity`; the
bean carries `@Role(ROLE_INFRASTRUCTURE)` because `EnableMethodSecurity`'s auto-proxy creator only ever applies
advisor beans with that role. `authorize()` (`core/security/AuthCriteriaAuthorizationManager.java:68-138`) reads
the method's annotations first, then the class's, whichever kind each is:

1. Neither annotation → error log, `flow.security.denied{resource=unannotated}` and a denied `AuthorizationDecision`, answered like the missing permission in step 5 (`:80-98`).
2. `@AuthExempt` → the call proceeds with no permission check (`:74-76`, `:81-84`).
3. `@AuthCriteria` but no identity → throws `AuthenticationCredentialsNotFoundException` (`:100-106`), which `RestExceptionHandler` renders as the structured 401 before it leaves the dispatcher (`core/RestExceptionHandler.java:132-150`).
4. Token class not in `assignableScopes` → the same exception and response, counted in `flow.security.denied` (`core/security/AuthCriteriaAuthorizationManager.java:108-117`).
5. No grant action matching `(**|<resource>)/(**|<action>)` → a denied `AuthorizationDecision`, which Spring turns into `AuthorizationDeniedException` (an `AccessDeniedException`); Spring Security's exception translation answers it with a 403 that carries no `RestErrorResponse` body, counted in `flow.security.denied` (`:119-136`).

`EndpointAuthorizationResponseTest` pins the 401 and the 403 over HTTP against real endpoints, and that an exempt
route is public only where the permit list also names it; `MethodSecurityConfigurationTest` pins the denial of an
unannotated handler and both placements of `@AuthExempt`.

`assignableScopes` decides which token classes reach a route at all. Five sets are in use:

| Admits | Handlers | Routes |
| --- | --- | --- |
| `global`, `key`, `user`, `session` | 77 | every route under `/api/v2/workspace/{workspace}/` (workflows, runs, task runs, tasks, schedules, actions, artifacts, Insights), the workspace routes other than create, name check and leave, tokens, users, webhooks and events, integrations, `GET /api/v2/taskrun/{id}/log` |
| `global`, `user`, `session` | 28 | settings, context, features, navigation, audit, global parameters, the task catalogue, the platform-wide Insights, creating a workspace and checking its name |
| `user`, `session` | 4 | the profile (`GET`, `PATCH`), `PUT /api/v2/activate`, leaving a workspace |
| `global` | 2 | the event outbox, `GET /api/v2/system/outbox` and `PUT /api/v2/system/outbox/replay` (`event/OutboxControllerV2.java:45,70`) |

A `key` token therefore reaches no system, audit or catalogue route, and only a `global` token reaches
the outbox: a signed-in admin's `session` token is refused there.

Whether a request must sign in and whether a permission rule applies are separate decisions. The URL rules in
"Authentication" make the first, by path; the annotation makes the second, on the handler. A public route
therefore needs both a permit entry and `@AuthExempt`, and an exempt handler outside the permit list still
requires sign-in. The exempt handlers, each with its reason in the annotation:

| Handlers | Must sign in | Why no permission rule |
| --- | --- | --- |
| `GET /internal/v1/auth/config`, `POST /internal/v1/auth/exchange` | No | Read or called before a session exists (`core/InternalAuthControllerV1.java:57,77`) |
| `POST /internal/v1/auth/logout` | Yes | Ends the caller's own session, the one its cookie authenticated; takes no input and touches no other token, so a user outside every workspace role can sign out (`core/InternalAuthControllerV1.java:95`) |
| `GET /api/v2/integration/github/callback` | No | A browser redirect from GitHub, secured by its signed state (`integrations/IntegrationControllerV2.java:340-346`) |
| `POST /api/v2/integration/slack/{commands,interactivity,events}`, `GET /api/v2/integration/slack/auth` | No | Reached by Slack, which cannot hold a Flow token. The three POST routes accept only a request signed with the configured Slack signing secret, within five minutes of its timestamp, and refuse everything when no secret is set (`integrations/SlackService.java:84,1005-1017`); the OAuth redirect accepts only a code Slack issued for the configured client, exchanged against Slack with the client secret, and links the installation to a workspace only under a valid signed state naming a member of it (`:521-576`) |
| `GET /api/v2/integration/slack/install` | Yes | A redirect to Slack's authorize URL for the configured app, linked to no workspace, that takes no input and checks nothing of its own; while the app's client id or secret is unset it is `409 INTEGRATION_NOT_CONFIGURED` (`integrations/IntegrationControllerV2.java:66-71`, `integrations/SlackService.java:983-998`) |
| The nine routes of `DispatcherControllerV1`, exempt at class level | By its own chain | A dispatcher token is checked by `DispatcherAuthFilter` (see "Dispatcher endpoints") and may do everything the protocol defines (`dispatcher/DispatcherControllerV1.java:53`) |

Layer 1 matches a permission string without looking at the **scope** the grant was made at, so a
workspace `owner`'s `**/**` satisfies `system/read`. Routes whose data spans every workspace therefore
re-check the scope themselves: `GET /api/v2/audit` and `/api/v2/audit/stats`
(`core/audit/AuditControllerV2.java:217-232`) and `PATCH`/`DELETE /api/v2/user/{userId}`'s non-self path
(`core/UserService.java:456-474`) both require a `global`-scoped grant and raise `PERMISSION_DENIED`
(HTTP 401) otherwise.

### Layer 2 — `RelationshipService` on the data

Services then ask whether the caller's node can reach the target through `rel_nodes`/`rel_edges` (node id =
`type:ref`, labelled edges such as `memberOf`, `hasWorkflow`, `hasWorkflowRun`). Every check is a live
level-by-level walk anchored at the caller's own node — one edge query plus one node batch-load per level —
with no in-memory graph and no cache beyond a per-HTTP-request memo (`core/RelationshipService.java:34-54`).
The walk follows every outgoing edge whatever its label, so an edge widens access unless it joins nodes the graph
already joins: the `linksTemplate` claim on a workspace's link to a template runs from the workspace to that link,
beside its `hasWorkflow` edge (`core/RelationshipService.java:289-342`).

| Token class | `check()` anchor (`:385-413`) | `filter()` anchor (`:478-513`) |
| --- | --- | --- |
| `session`, `user` | the `user:<principal>` node; one holding a `global` grant (an admin's or operator's session) **returns `true`** like a global token | same; a `global` grant anchors at `root`, so an admin lists every workspace |
| `key` with `actorKind=WORKFLOW` | the `workflow:<principal>` node | same; anchors at `root` when reading its own workflow |
| `key` (any other) | the `workspace:<principal>` node | same |
| `global` | **returns `true` without walking** (`:408-410`) | anchors at `root`, so it sees every node of the type |

`check()` first confirms some grant covers the resource type at all (`checkPermissions`, `:416-437`) —
action granularity is layer 1's job. A failed `check()` returns `false` and counts `flow.security.denied`
with `layer=relationship` (`:362-384`). Most callers then answer with the object's not-found error, so a hidden
object reads like a missing one (for example `WORKSPACE_INVALID_REF`, HTTP 404); a few raise `PERMISSION_DENIED`,
which serialises as HTTP 401 (`common/error/BoomerangError.java:25,45`). Workspace-scoped routes pass the
`{workspace}` path segment as an intermediate containment (for example
`workflow/WorkflowRunService.java:673-684`). Because `global` returns before the walk, a global token's requests
ignore that path segment; the same caller could reach the object through its real workspace URL, so nothing extra
is granted. `filter()` for `TASK` always anchors at `root` — the task catalogue is global
(`core/RelationshipService.java:535-539`).

Ownership edges are written when a run is created: `WorkflowService.internalSubmit` writes
`workspace → hasWorkflowRun → workflowrun` from the path workspace after `submit` has confirmed that
workspace contains the workflow (`workflow/WorkflowService.java:592-596,687-695`); retries — including the
engine's automatic retry — resolve the owner from the original run's edge, falling back to the workflow's
parent (`workflow/WorkflowRunService.java:642-666,956,1023-1033`); child runs get the edge from
`RelationshipEventListener` (`core/RelationshipEventListener.java:37-49`).

## Security off: the synthetic admin

With security off, `UnauthenticatedGlobalAuthenticationFilter` — a thin subclass of Spring Security's own
`AnonymousAuthenticationFilter`, overriding only `createAuthentication`, so the superclass's "only if the
`SecurityContext` is still empty" guard applies unchanged — installs an `UnauthenticatedGlobalToken` on every
request that has no identity yet (`core/security/UnauthenticatedGlobalAuthenticationFilter.java:29,45-51`). It is a
`global`-class token, principal `system`, `actorKind=SERVICE`, one `**/**` grant, and an empty `GrantedAuthority`
list (`core/security/UnauthenticatedGlobalToken.java:64-72`). `UserService.getCurrentUser()` maps it to
`virtualUser()` — id `system`, type `admin`, active — so `/api/v2/profile` and `/api/v2/context` render
(`core/UserService.java:257-258`, `UnauthenticatedGlobalToken.java:82-90`). Neither the token nor the user is
ever written to MongoDB, and the user cannot be activated by an identity-provider email match. Audit records
carry `actor.principal=system`.

With `flow.workspace.single=true` `SingleWorkspaceInterceptor` also rejects any `/api/v2/workspace/{workspace}`
or `/api/v2/workspace/{workspace}/**` request whose segment is not `system`
(`core/security/SingleWorkspaceInterceptor.java:32-45`, `SingleWorkspaceInterceptorConfiguration.java:22-27`), and
`WorkspaceService.create`/`delete` refuse (`workspace/WorkspaceService.java:167,351`).

## Dispatcher endpoints

`/dispatcher/**` is its own filter chain (`dispatcher/DispatcherSecurityConfiguration.java:26,37-47`), and
`DispatcherAuthFilter` checks every request that chain claims, regardless of `flow.security.enabled`
(`dispatcher/DispatcherAuthFilter.java:63-93`): the bearer must be Flow-shaped, then
`TokenService.validateActorToken` requires a stored, unexpired `global` token with a non-null `actorKind`
(`core/TokenService.java:415-428`, read through the same lookup cache). `lastUsedAt` is stamped at most every 5
minutes (`core/TokenService.java:67,430-451`). The token then becomes the request's identity, the one the same
bearer carries on `/api/v2` (`dispatcher/DispatcherAuthFilter.java:88-100`): `RelationshipService` sees a `global`
caller and audit records name the dispatcher's token as the actor. That holds with security off too, because this
chain runs ahead of the synthetic admin's. The chain's `SecurityContextHolderFilter` clears the identity when the
request ends. `flow.dispatcher.auth.enabled=false` (default `true`,
`service-core/src/main/resources/application.properties:43`) checks nothing, for local development, and the
request acts as the synthetic admin of "Security off" (`dispatcher/DispatcherAuthFilter.java:66-71`).
The chain's matcher is the only statement of which paths need the token: the filter keeps no path list of its
own, and nothing else is served under the prefix. `DispatcherAuthTest` pins that a path under the prefix no
handler serves still needs the token, and that `/api/v1/dispatcher` is not the dispatcher wire. The wire has its
own prefix so that it can be routed separately from the public API (`api-contract.md`, "URL shape").

## Secured settings at rest

A `settings.config[]` entry typed `secured` is encrypted on every save and decrypted on every internal read
(`core/SettingsService.java:213-217,158-180`). The settings API (`GET` and `PATCH /api/v2/settings`, the latter a
merge patch of `{"<group>": {"<key>": value}}`, `core/SystemControllerV2.java:56,71`) follows the one secret rule
(`parameters.md`, "Sensitive parameters"): a set value reads as `*****` and an unset one as nothing
(`core/SettingsService.java:225-234`); a patch that sends `*****` or leaves the entry out keeps the stored value, a
blank or a null clears it, and any other value replaces it (`core/SettingsService.java:125-135`). A value that will
not decrypt is kept as stored on the next save of its group (`core/SettingsService.java:247-256`). The cipher is
AES-256-GCM with a random initialisation vector per call (`Encryptors.delux`, `core/model/AESAlgorithm.java:49-52`),
and the stored value carries the scheme in its own label, `crypt_v1{AESGCM|…}` — the library does not tag it. A
blank value and a value carrying the whole label pass through the cipher untouched, and an encryption failure
throws (`core/SecretCipher.java:38-60`).

The key is `flow.encrypt.secret` and `flow.encrypt.salt`, required on every install: a blank pair refuses startup
(`core/model/EncryptionConfig.java:25-32`). Two historical populations are brought forward by the change unit
`_0022__ReencryptSettingsAesGcm` (`migration/_0022__ReencryptSettingsAesGcm.java:28-43`), which runs as the context starts, after the settings seed: values under the
retired AES/CBC label `crypt_v1{AES|…}` (a fixed initialisation vector), written by v3 under the pair in `mongo.encrypt.secret` and
`mongo.encrypt.salt`, are decrypted with a frozen copy of that cipher and re-encrypted under the new key; and
values carrying no label at all — secured values that an earlier release stored as plaintext — are encrypted in
place. A legacy value that does not decrypt stops the migration rather than leaving a credential unreadable,
unless `flow.migration.reset-unreadable-settings=true`, which blanks each such value and logs it by setting, to
be entered again in Settings. Both pairs default to `FLOW_ENCRYPT_SECRET` and `FLOW_ENCRYPT_SALT`, the names v3's
chart kept its pair under, so a v3 install upgrades with that Secret as it is; `MONGO_ENCRYPT_*` overrides the v3
pair alone (`service-core/src/main/resources/application.properties:129-140`). The unit is idempotent on the
label, so a second run changes nothing.

## Known gaps

- **Machine tokens cannot approve group approvals.** `ActionService.action` resolves the current *user*; a `key`/`global` token resolves none and is not a member, so an automation must be given a real user identity placed in the approver group (`workflow/ActionService.java:128,155-160`). The controller's `assignableScopes` still admit machine tokens. Manual tasks and approvals without a group accept a machine token and record its name as the actor (`workflow/ActionService.java:169-182`).
