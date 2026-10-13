# Authentication and authorization

Every request to `service-core` passes two gates: `AuthenticationFilter` establishes *who* is calling (a
`Token` on the Spring `SecurityContext`), then `AuthCriteriaAuthorizationManager` plus `RelationshipService`
decide *what* that token may touch. One property switches both gates. Paths below are under
`service-core/src/main/java/io/boomerang/`.

## The switch: `flow.security.enabled`

One property gates both halves, and its default follows the run mode. `FlowSecurityProperties.isSecurityEnabled`
returns the property when it is set, otherwise `true` for `flow.mode=standalone` and `false` for `engine`
(`core/security/FlowSecurityProperties.java:23-28`). Two mutually exclusive configurations key off it:

| Security | Filter chain | Identity on every request | Authorization |
| --- | --- | --- | --- |
| on | `SecurityConfiguration` — `AuthenticationFilter`, `anyRequest().authenticated()` (`core/security/SecurityConfiguration.java:23,78-108`) | the real `Token` the filter resolved | `AuthCriteriaAuthorizationManager` wired as a method-security `Advisor` by `MethodSecurityConfiguration` (same condition) |
| off | `SecurityDisabledConfiguration` — `anyRequest().permitAll()`, `http.anonymous(...)` (`core/security/SecurityDisabledConfiguration.java:13,20-33`) | a synthetic `UnauthenticatedGlobalToken` (see below) | the advisor is not registered; `RelationshipService` still runs but its `global` branch allows everything |

The worker-facing `/api/v1/**` chain (`dispatcher/DispatcherSecurityConfiguration.java:41-50`) is ordered
first and is independent of this switch — see "Dispatcher endpoints".

## Authentication: how a caller becomes a `Token`

`AuthenticationFilter` checks the identity sources in a fixed order and stops at the first one present
(`core/security/AuthenticationFilter.java:107-121`). It is loaded only when security is on (`:43`). Every
branch installs the resolved `Token` as the `Authentication`'s details with an empty authority list: the
`Token` is what every permission and relationship check reads.

| Order | Source | Handled by | Result |
| --- | --- | --- | --- |
| 1 | `Authorization: Bearer bf?_…` (a Flow-minted token, `isFlowBearer` `:309-312`) | `getTokenAuthentication` (`:286-306`) | shape gate `TokenTypePrefix.isFlowToken` (`:290-294`), then SHA-256 hash lookup in `tokens`, expiry check (`core/TokenService.java:403-417`) |
| 1b | `Authorization: Bearer <JSON Web Token (JWT)>` (not Flow-shaped) | `getUserSessionAuthentication` (`:180-244`) | the token is parsed for `email`/`emailAddress` and names as the proxy sent it, **not signature-verified**: the route rests on the proxy in front of `service-core` (boomerang-io/flow#512 tracks checking it against an issuer). A session token is minted for that email |
| 1c | `Authorization: Basic email:password` | same method (`:245-275`) | password checked against `flow.authorization.basic.password` via `PasswordEncoder.matches` (constant-time; encoded once at filter construction, `:79-91`), not a raw string comparison; session token minted for the email |
| 2 | `x-access-token` header | `getTokenAuthentication` | as row 1 |
| 3 | `?access_token=` query parameter | `getTokenAuthentication` | as row 1; kept for webhook senders that cannot set headers (`:50-57`) |
| 4 | `x-forwarded-email` / `x-forwarded-user` (an authenticating proxy) | `getGithubUserAuthentication` (`:317-341`) | session token minted for the forwarded email |
| 5 | `flow_session` cookie | `getTokenAuthentication` (`:119-120,161-172`) | the opaque `bfs_` value minted by `POST /api/v2/auth/exchange` |

Rows 1b, 1c and 4 mint through `TokenService.createSessionToken`, which reuses one persisted session per
normalised email for 60 seconds per instance (`core/TokenService.java:585-619`). User creation is allowed
only on `/api/v2/profile` and the exchange path; activation only on `/api/v2/activate` and the exchange path
(`AuthenticationFilter.java:185-195`).

Rows 1, 2, 3 and 5 resolve the bearer through `TokenLookupCache` (`core/security/TokenLookupCache.java`), a
per-instance Caffeine cache keyed by the stored SHA-256 hash and holding the validated `TokenEntity`, so the
filter's `validate` followed by `get` costs one repository read (`core/TokenService.java:411-423`). Only a
present, unexpired entity is cached - misses are never cached - and the entity's `expirationDate` is re-checked
on every hit. Entries live 60 seconds and the cache holds at most 10 000 (`flow.security.token-cache.ttl`,
`flow.security.token-cache.max-size`, `flow.security.token-cache.enabled`, `application.properties:23-25`).
`TokenService.delete` evicts the token's hash and `deleteAllForPrincipal` flushes the cache
(`core/TokenService.java:458-472`); `touchLastUsed` does not evict. Eviction is per instance: after a revoke on
one instance, another instance MAY still honour the token until its own entry ages out, at most the TTL.

No identity → `AUTH_REQUIRED` (HTTP 401) via `DelegatedAuthenticationEntryPoint` (`:132-136`), except on
`/api/v2/auth/exchange`, which continues so the controller can verify an OpenID Connect (OIDC) id_token itself
(`:127-131`). Paths that skip the filter: `/error`, `/health`, `/api/docs`, the GitHub App callback and
`/api/v2/auth/config` (`:345-352`); `SecurityConfiguration.java:96-99` also permits `/info`, `/webjars`, the
exchange endpoint and `/api/v2/extensions/slack/install`, a path no controller serves.

### Session sign-in (the browser flow)

`AuthControllerV2` (`core/AuthControllerV2.java`, standalone mode only, `:43`) exposes three endpoints:

| Endpoint | Auth | What it does |
| --- | --- | --- |
| `GET /api/v2/auth/config` | public | Returns `mode` = `none` (security off), `oidc` (both `auth.oidc.issuer` and `auth.oidc.clientId` settings set) or `proxy` (`core/security/AuthExchangeService.java:55-66`) |
| `POST /api/v2/auth/exchange` | public | Empty body: mints a session from the identity the filter already resolved (`:84`). `{idToken, nonce}`: `OidcTokenVerifier.verify` checks the signature against the issuer's published signing keys and exact-matches `iss`, `aud`, `exp`, `nonce` (`core/security/OidcTokenVerifier.java:83-122`); symmetric and `none` algorithms are rejected. Either way the response sets an httpOnly, `Secure`, `SameSite=Lax` `flow_session` cookie (`core/security/SessionCookie.java:22-30`) |
| `POST /api/v2/auth/logout` | `session` token | Clears the cookie and deletes the token (`AuthExchangeService.java:141-144`) |

The cookie carries only the opaque token; the webapp runs the OIDC redirect dance and logout from its own
server routes (`client-web/app/Features/Auth/`), so the id_token never reaches the browser.

## Token kinds

`AuthScope` is the token **class**; `TokenActorKind` is an orthogonal badge for machine tokens; `PermissionScope`
(`global` | `workspace`) is the scope of each grant and is always derived server-side (`core/TokenService.java:174-203`).

| `AuthScope` | Prefix | Holder | Grants | How it is minted |
| --- | --- | --- | --- | --- |
| `session` | `bfs_` | a signed-in human | re-resolved from the user's type and memberships (`resolvePermissionsForUser`, `TokenService.java:711-731`) | only by `AuthenticationFilter`/the exchange; `POST /api/v2/token` rejects it (`:109-110`) |
| `user` | `bfu_` | a human's long-lived personal token | copied from that user's workspace roles (`:178-188`) | `POST /api/v2/token` |
| `key` | `bfk_` | a machine — service, AI agent, or a workflow's own scheduler credential (`actorKind`) | always `workspace`-scoped; a `global` grant is refused (`:379-385`) | `POST /api/v2/token`; `createWorkflowSchedulerToken` for workflows (`:764-781`) |
| `global` | `bfg_` | platform admin or the dispatcher (`actorKind=SERVICE`) | one `global` grant | `POST /api/v2/token`, and only by a caller who already holds a `global` grant (`:136-138`) |

`TokenActorKind` is `SERVICE`, `AGENT` or `WORKFLOW` (`core/security/enums/TokenActorKind.java:22-26`), null on
human tokens. Only the SHA-256 hash of a raw token is stored (`TokenService.java:384-387`), and a token that does
not match `^bf[gkus]_.+` (`core/enums/TokenTypePrefix.java:35,57-59`) is rejected before any database lookup on both
paths — `AuthenticationFilter.getTokenAuthentication` for every source in the table above (`:290-294`) and
`DispatcherAuthFilter` (`dispatcher/DispatcherAuthFilter.java:94`) — so a retired `bft_`/`bfw_` bearer never reaches Mongo.

Seeded roles (`service-core/src/main/resources/seed/roles.json`): global `admin` (`**/**`) and `operator`
(`**/read`, `**/write`, `**/action`); workspace `owner` (`**/**`), `editor` (`**/read`, `**/write`,
`**/action`) and `reader` (`**/read`). A permission string is `<resource>/<action>` with `**` as a wildcard on
either side; resources and actions are the `PermissionResource` and `PermissionAction` enums.

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
3. `@AuthCriteria` but no identity → throws `AuthenticationCredentialsNotFoundException` (`:100-106`), which `RestExceptionHandler` renders as the structured 401 before it leaves the dispatcher (`core/RestExceptionHandler.java:116-134`).
4. Token class not in `assignableScopes` → same exception and response, counted in `flow.security.denied` (`:108-117`).
5. No grant action matching `(**|<resource>)/(**|<action>)` → a denied `AuthorizationDecision`, which Spring turns into `AuthorizationDeniedException` (an `AccessDeniedException`); Spring Security's exception translation answers it with a 403 that carries no `RestErrorResponse` body, counted in `flow.security.denied` (`:119-136`).

`EndpointAuthorizationResponseTest` pins the 401 and the 403 over HTTP against real endpoints, and that an exempt
route is public only where the permit list also names it; `MethodSecurityConfigurationTest` pins the denial of an
unannotated handler and both placements of `@AuthExempt`.

Whether a request must sign in and whether a permission rule applies are separate decisions. The URL rules in
"Authentication" make the first, by path; the annotation makes the second, on the handler. A public route
therefore needs both a permit entry and `@AuthExempt`, and an exempt handler outside the permit list still
requires sign-in. The exempt handlers, each with its reason in the annotation:

| Handlers | Must sign in | Why no permission rule |
| --- | --- | --- |
| `GET /api/v2/auth/config`, `POST /api/v2/auth/exchange` | No | Read or called before a session exists |
| `GET /api/v2/integration/github/callback` | No | A browser redirect from GitHub, secured by its signed state |
| The five `/api/v2/integration/slack/*` routes | Yes | Meant for Slack, which cannot hold a Flow token, so Slack cannot reach them today; request-signature verification is not wired (boomerang-io/flow#374) |
| The eight routes of `DispatcherControllerV1`, exempt at class level | By its own chain | A dispatcher token is checked by `DispatcherAuthFilter` (see "Dispatcher endpoints") and may do everything the protocol defines |

Layer 1 matches a permission string without looking at the **scope** the grant was made at, so a
workspace `owner`'s `**/**` satisfies `system/read`. Routes whose data spans every workspace therefore
re-check the scope themselves: `GET /api/v2/audit` and `/api/v2/audit/stats`
(`core/audit/AuditControllerV2.java:217-232`) and `PATCH`/`DELETE /api/v2/user/{userId}`'s non-self path
(`core/UserService.java:537-555`) both require a `global`-scoped grant and raise `PERMISSION_DENIED`
(HTTP 401) otherwise.

### Layer 2 — `RelationshipService` on the data

Services then ask whether the caller's node can reach the target through `rel_nodes`/`rel_edges` (node id =
`type:ref`, labelled edges such as `memberOf`, `hasWorkflow`, `hasWorkflowRun`). Every check is a live
level-by-level walk anchored at the caller's own node — one edge query plus one node batch-load per level —
with no in-memory graph and no cache beyond a per-HTTP-request memo (`core/RelationshipService.java:31-51`).

| Token class | `check()` anchor (`:394-422`) | `filter()` anchor (`:486-514`) |
| --- | --- | --- |
| `session`, `user` | the `user:<principal>` node | same |
| `key` with `actorKind=WORKFLOW` | the `workflow:<principal>` node | same; anchors at `root` when reading its own workflow |
| `key` (any other) | the `workspace:<principal>` node | same |
| `global` | **returns `true` without walking** (`:417-419`) | anchors at `root`, so it sees every node of the type |

`check()` first confirms some grant covers the resource type at all (`checkPermissions`, `:425-446`) —
action granularity is layer 1's job. A failed `check()` returns `false` and counts `flow.security.denied`
with `layer=relationship` (`:371-393`); callers typically raise `PERMISSION_DENIED`, which serialises as HTTP
401 (`lib-common/src/main/java/io/boomerang/common/error/BoomerangError.java:22`). Workspace-scoped routes pass the `{workspace}` path segment
as an intermediate containment (for example `WorkflowRunService.requireWorkspaceRelationship`,
`workflow/WorkflowRunService.java:425-436`). Because `global` returns before the walk, a global token's
requests ignore that path segment; the same caller could reach the object through its real workspace URL,
so nothing extra is granted. `filter()` for `TASK` always anchors at `root` — the task catalogue is global (`:480-484`).

Ownership edges are written when a run is created: `WorkflowService.internalSubmit` writes
`workspace → hasWorkflowRun → workflowrun` from the path workspace after `submit` has confirmed that
workspace contains the workflow (`workflow/WorkflowService.java:561-572,655-663`); retries — including the
engine's automatic retry — resolve the owner from the original run's edge, falling back to the workflow's
parent (`workflow/WorkflowRunService.java:394-400,908,941-950`); child runs get the edge from
`RelationshipEventListener` (`core/RelationshipEventListener.java:37-49`).

## Security off: the synthetic admin

With security off, `UnauthenticatedGlobalAuthenticationFilter` — a thin subclass of Spring Security's own
`AnonymousAuthenticationFilter`, overriding only `createAuthentication` to install the token below; the "only if
the `SecurityContext` is still empty" guard is that superclass's native behaviour, not reimplemented — installs
an `UnauthenticatedGlobalToken` on every request that has no identity yet
(`core/security/UnauthenticatedGlobalAuthenticationFilter.java:29,45-51`). It is a
`global`-class token, principal `system`, `actorKind=SERVICE`, one `**/**` grant, an empty `GrantedAuthority`
list matching its Layer 1 privilege exactly (`core/security/UnauthenticatedGlobalToken.java:61-70`). `UserService.getCurrentUser()` maps it to
`virtualUser()` — id `system`, type `admin`, active — so `/api/v2/profile` and `/api/v2/context` render
(`core/UserService.java:258-259`, `UnauthenticatedGlobalToken.java:81-86`). Neither the token nor the user is
ever written to MongoDB, and the user cannot be activated by an identity-provider email match. Audit records
carry `actor.principal=system`.

In `engine` mode `EngineWorkspaceInterceptor` also rejects any `/api/v2/workspace/{workspace}/**` request whose
segment is not `system` (`core/security/EngineWorkspaceInterceptor.java:37-44`, `EngineWorkspaceInterceptorConfiguration.java:15-22`).

## Dispatcher endpoints

`/api/v1/dispatcher/**` is guarded by `DispatcherAuthFilter` regardless of `flow.security.enabled`
(`dispatcher/DispatcherAuthFilter.java:54,78-105`): the bearer must be Flow-shaped, then
`TokenService.validateActorToken` requires a stored, unexpired `global` token with a non-null `actorKind`
(`core/TokenService.java:433-438`, read through the same lookup cache). `lastUsedAt` is stamped at most every 5 minutes. `flow.dispatcher.auth.enabled=false`
(default `true`, `application.properties:34`) turns the filter into a pass-through for local development.
The rest of `/api/v1/**` is `permitAll` and relies on network isolation.

## Secured settings at rest

A `settings.config[]` entry typed `secured` is encrypted on every save and decrypted on every read
(`core/SettingsService.java:135-145`). The cipher is AES-256-GCM with a random initialisation vector per call
(`core/model/AESAlgorithm.java:27-48`, `Encryptors.delux`), and the stored value carries the scheme in its own
label, `crypt_v1{AESGCM|…}` — the library does not tag it. A blank value and a value already labelled pass
through untouched (`:147-156`).

The key is `flow.encrypt.secret` and `flow.encrypt.salt` (`core/model/EncryptionConfig.java`), required on every
install: a blank pair refuses startup. Two historical populations are brought forward by the change unit
`_0022__ReencryptSettingsAesGcm`, which runs as the context starts, after the settings seed: values under the
retired static-IV AES/CBC label `crypt_v1{AES|…}`, written by v3 under the pair in `mongo.encrypt.secret` and
`mongo.encrypt.salt`, are decrypted with a frozen copy of that cipher and re-encrypted under the new key; and
values carrying no label at all — every secured value saved while the service's encryption guard was inverted, so
the stored value was its own plaintext — are encrypted in place. A legacy value that does not decrypt stops the
migration rather than leaving a credential unreadable. The unit is idempotent on the label, so a second run
changes nothing (decision 0100).

## Known gaps

- **Machine tokens cannot approve group approvals.** `ActionService.action` resolves the current *user*; a `key`/`global` token resolves none and is denied membership, so an automation must be given a real user identity placed in the approver group (`workflow/ActionService.java:137-146`). The controller's `assignableScopes` still admit machine tokens.
