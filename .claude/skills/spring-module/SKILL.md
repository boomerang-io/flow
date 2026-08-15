---
name: spring-module
description: Spring Boot patterns for the Boomerang Flow backend (Java 21 / Spring Boot 3, MongoDB). Use this skill when creating or modifying any backend Java class — entities, repositories, services, controllers, config — in service-flow, service-engine, service-agent, or lib-common. Triggers on any Java backend work. If you're writing Java here, read this skill first.
---

# Spring Boot Module Patterns — Boomerang Flow

Java 21, Spring Boot 3.4.x, Maven multi-module, MongoDB primary store, Lombok, Log4j2.
Match the conventions **already in the codebase** — the examples below are real files; read
the nearest equivalent before writing.

## Modules and package layout

Four Maven modules under the parent `pom.xml`:

| Module           | Package roots (`io.boomerang.*`)                                   | Role                                             |
| ---------------- | ------------------------------------------------------------------ | ------------------------------------------------ |
| `lib-common`     | shared domain model / enums / error                                | **Shared domain model only** — no service logic. |
| `service-flow`   | `core`, `workflow`, `integrations`, `security`, `client`, `config`, `common`, `error` | v2 REST API, auth/authz, teams, tokens.          |
| `service-engine` | `engine`, `audit`, `aspect`, `client`, `config`, `util`, `error`   | DAG execution, WorkflowRun/TaskRun lifecycle.    |
| `service-agent`  | `agent`, `kube`, `client`, `config`, `error`                       | Pluggable execution worker (Tekton default).     |

Within a domain area, code is grouped by role: `entity/`, `model/`, `service`, controller,
`repository` — not a deep per-feature module tree. Follow the layout of the package you are
editing (e.g. `service-flow/**/core/`, `service-engine/**/engine/`).

**Invariant** (from `CLAUDE.md`): keep `lib-common` for shared domain model only. Flow-only or
engine-only logic belongs in that service module. No new **synchronous HTTP** calls between
service-flow and service-engine — use CloudEvents (or in-process `ApplicationEvent` post-merge).

## Entity Pattern

Read `service-flow/src/main/java/io/boomerang/core/entity/TokenEntity.java` for the canonical shape.

```java
@Data                                   // Lombok — getters/setters/equals/hashCode/toString
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('tokens')}")
public class TokenEntity {

  @Id private String id;
  private AuthScope type;
  private String name;
  private Date creationDate = new Date();
  private Date expirationDate;
  private List<ResolvedPermissions> permissions = new LinkedList<>();

  // Add @Version for entities with concurrent transitions (e.g. WorkflowRun).
  // @Version private Long version;
}
```

Key conventions:
- `@Data` (Lombok) on entities; `@JsonIgnoreProperties(ignoreUnknown = true)` + `@JsonInclude(NON_NULL)`.
- **Entities stay Lombok-only — do NOT add nested `Fields` constant classes or `public static final String` field-name constants** (maintainer ruling 2026-08-13). Mongo `Criteria`/`Update` calls use **raw string literals** (`Criteria.where("claim.by")`, `.set("retry.after", …)`) — the house style, consistent with the loader changeunits. A field-name constants holder is NOT wanted (it clutters the entities); only introduce one with an explicit maintainer exception. This overrides any generic "magic strings → constants" instinct here.
- **Collection name via SpEL** — `#{@mongoConfiguration.fullCollectionName('<name>')}` so the
  configured collection prefix (`flow.mongo.collection.prefix`) is applied. Never hardcode a
  bare collection name.
- `@Id private String id;` (Mongo ObjectId as String).
- The prevailing timestamp type in existing entities is `java.util.Date`. Match the neighbours
  you're editing; do not mix `Date` and `Instant` within one entity.
- `@Indexed` on fields used in filters/lookups. For the engine's claim query, the index is
  correctness-critical — coordinate index changes with `scaling.md`.
- `@Version` (optimistic locking) on entities with concurrent state transitions — this is how
  v5 does workflow-level transition safety instead of a distributed lock (ARCHIE lesson).

## Repository Pattern

```java
public interface TokenRepository extends MongoRepository<TokenEntity, String> {

  Optional<TokenEntity> findByToken(String token);

  List<TokenEntity> findByType(AuthScope type);

  // Use @Query for anything the derived-method DSL can't express cleanly.
  @Query("{ 'type': ?0, 'expirationDate': { $gt: ?1 } }")
  List<TokenEntity> findActiveByType(AuthScope type, Date now);
}
```

- Extend `MongoRepository<Entity, String>`; derived methods for simple queries, `@Query` for
  complex ones. Return `List<>` for collections, `Optional<>` for single lookups.
- **Atomic claiming** (engine pollers) uses a single `findAndModify` (MongoTemplate
  `findAndModify` with the claim predicate + ownership update) — not a distributed lock and not
  read-then-write. This is the v5 concurrency backbone; see `scaling.md`.

## Service Pattern

```java
@Service
public class TokenService {

  @Autowired private TokenRepository tokenRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  public TokenEntity create(TokenCreateRequest request) {
    var token = new TokenEntity();
    token.setType(request.type());
    token.setCreationDate(new Date());
    // ... set fields, validate state transitions explicitly
    return tokenRepository.save(token);
  }
}
```

- `@Service`. The prevailing injection style in this codebase is **field `@Autowired`** — match
  it when editing existing classes. Constructor injection is acceptable for brand-new components,
  but stay consistent within a class.
- Validate state transitions explicitly; throw the project's domain errors (see
  `io.boomerang.error.BoomerangError` / `ErrorDetail`) rather than raw `RuntimeException`.
- **Transition handlers must be idempotent** (`CLAUDE.md` invariant): the reconciler may call
  them on restart. Re-read current state from the DB before acting, check the transition hasn't
  already occurred, and use versioned writes. Only create a TaskRun for a DAG step if no
  non-SUPERSEDED SUCCEEDED run exists for it.
- Prefer `ApplicationEventPublisher` for in-process decoupling; CloudEvents for cross-service.

## Controller Pattern

Read `service-flow/src/main/java/io/boomerang/core/TokenControllerV2.java`.

```java
@RestController
@RequestMapping("/api/v2")
@Tag(name = "Token Management", description = "Create and retrieve Tokens")
public class TokenControllerV2 {

  @Autowired private TokenService tokenService;

  @PostMapping("/token")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.team,
                          AuthScope.workflow, AuthScope.session},
      resource = PermissionResource.TOKEN,
      action = PermissionAction.WRITE)
  @Operation(summary = "Create Token")
  public TokenCreateResponse createToken(@RequestBody TokenCreateRequest request) {
    return tokenService.create(request);
  }
}
```

Conventions:
- Name controllers `*ControllerV2` (flow, `/api/v2`) or `*ControllerV1` (engine, `/api/v1`).
- Team-scoped resources nest under `/api/v2/team/{team}/...`.
- **Authorization is annotation-driven.** Every protected endpoint method carries
  `@AuthCriteria(assignableScopes = {...}, resource = PermissionResource.X, action = PermissionAction.Y)`.
  `SecurityInterceptor.preHandle` enforces it: a method with **no** `@AuthCriteria` is treated as
  unauthenticated/public (the interceptor logs a warning and skips authz) — so an endpoint that
  should be protected but omits the annotation is a **silent auth hole**. See the `security-audit`
  skill.
- `resource`/`action` must be real enum values: `PermissionResource` (system, workflow,
  workflowrun, workflowtemplate, taskrun, task, action, user, team, token, parameter, schedule,
  insights, integration, webhook, `**`=ANY) and `PermissionAction` (read, write, delete, action).
- Document endpoints with Swagger `@Tag` / `@Operation` / `@Parameter`.
- **Status is the only external-facing field; never expose `phase`** in a response body.
- Return the model/record directly; use `ResponseEntity` only for custom status codes. Use
  request records for input, not entities.

## Deployment modes (`flow.mode`)

New mode-gated behaviour uses `@ConditionalOnProperty` — the same pattern as
`flow.authorization.enabled` (see `SecurityDisabledConfiguration`). Modes: `full` (default),
`engine` (headless/embedded, no auth module, single `default` workspace), `standalone`
(single-user, local agent, no Kubernetes). Anything you add must be sound in all modes it can
run under, not just `full`.

## Code style (maintainer-ruled + Spring team conventions)

Repo rulings (from v5 review feedback — these override any generated-code habit):

- **Class naming is `<Name>Service` / `<Name>Controller`** (maintainer ruling 2026-08-15), with
  `<Name>Client` ONLY for interfaces to an external system, `<Name>ExecutionService` for the
  engine's execution orchestrators, and a small set of error/config shapes. The DOMAIN service
  carries the plain name (`workflow.WorkflowService` = the definition service); when layers
  collide on a simple name, the composition/shim layer takes the marked name
  (`api.TeamWorkflowService` pairing `TeamWorkflowControllerV2`) — never suffix the domain class
  (`WorkflowDefinitionService`-style names were explicitly overruled).
- **Smallest honest expression.** No long-form if/else with staging variables when a
  guarded expression states the rule. Express the semantics, skip the scaffolding.
  Reference shape: `long timeout = <default>; if (userValue set && valid && not greater)
  timeout = userValue;` — two statements, zero ceremony.
- **Comments state what the code guarantees, in 1–2 lines.** NEVER put process language
  in code: no epic/phase codes (E0/E2/D3), no "v5", no spec-file citations — git history
  carries provenance. Bad: `// v5 E2/D3 fix, see timeout-audit.md`. Good:
  `// Timeout = the platform default, or the task's own value when set and not greater.`
- **No process language in identifiers.** Class/test names describe BEHAVIOUR
  (`AgentQueueClaimTest`), never phases or scenario numbers (`E0Scenario02...` was
  rejected: "E0 makes no meaningful sense in the code base once this refactor is
  finished").
- **Check the sibling pattern first.** Before writing logic, look for the same concern
  solved elsewhere (task-level timeout mirrors the workflow-level quota-ceiling pattern)
  — mirror it rather than inventing a variant.
- **Default to the existing data model; highlight any deviation** (maintainer ruling
  2026-08-14). The entities/collections/relationships are proven across v1–v5. Reuse them.
  Do NOT add wrapper/wire/DTO models, denormalized fields, new collections, extra
  indirection, or model splits without a concrete field-level justification — and when a
  change *does* deviate by adding a layer, **call it out explicitly** to the maintainer
  rather than slipping it in. Added complexity carries the burden of proof. (`WorkflowRunClaim`,
  `teamRef`, `RetryClass`, and run-creation idempotency keys were each pulled back for exactly
  this.)

From the Spring Framework team's own style guide
([Code Style wiki](https://github.com/spring-projects/spring-framework/wiki/Code-Style),
[Contributing](https://github.com/spring-projects/spring-framework/blob/main/CONTRIBUTING.md)) —
apply where they don't conflict with the repo's existing idiom:

- Wrap ternaries in parentheses; put the not-null/positive condition first.
- Javadoc first sentences imperative ("Return…", not "Returns…"); wrap code refs in
  `{@code}`.
- No `var` in production code; descriptive identifiers (`Method method`, never `m`).
- `Assert.notNull(arg, "Arg must not be null")` for argument validation;
  `CONSTANT_CASE` only for truly constant `static final`s; utility classes `abstract` +
  private constructor, `*Utils` suffix.
- Tests: JUnit Jupiter + AssertJ assertions + Mockito; static imports fine in tests.
  (Spring suffixes test classes `Tests`; this repo uses `*Test`/`*IT` — match the repo.)

## Gotchas — "agent does X, do Y instead"

(Format borrowed from rrezartprebreza/spring-boot-skills; entries are REAL mistakes made
and corrected in this repo. Add to this list whenever the maintainer corrects generated
code. Principle from the same source: *don't describe what Spring Boot already knows* —
only document where this repo diverges from defaults.)

- Agent writes long-form if/else with staging variables → write the smallest honest
  expression (see Code style).
- Agent puts epic/phase codes ("E2", "v5") or spec citations in comments, class names, or
  fixture data → domain language only; git history carries provenance.
- Agent adds `countBy...()` and tests `> 0` → use `existsBy...()`.
- Agent adds a parameter or helper method through which only one value ever flows →
  inline it; a parameter that never varies is ceremony.
- Agent applies cross-cutting config (timeouts, keep-alive) to some clients/beans but not
  all → apply uniformly or not at all; partial application is a latent bug.
- Agent names integration tests `*IT` → this repo has no failsafe config, so `*IT` is
  **silently never run** by `mvn test`; use `*Test`.
- Agent trusts a file's trailing newline when appending to properties files → check;
  `messages.properties` had none and lines concatenated.
- Agent designs a Mongo partial unique index with `$exists: false` → unsupported; use a
  full compound unique index or a positive-value partial filter.
- Agent renames a file AND rewrites its contents in one commit → git's rename detection
  (similarity-based, default 50%) loses the pairing — `git log --follow` and IDE blame
  break. **Rename in one commit, modify in the next.**
- Agent adds guards as `Optional<X> opt = repo.findById(id); if (opt.isEmpty()) {...};
  X x = opt.get();` → use the codebase idioms: `.orElseThrow(() -> new
  BoomerangException(...))` on throwing paths; `.orElse(null)` + one null-check on
  log-and-return paths. No Optional staging variables.
- Agent asserts a library/framework incompatibility from jar metadata or docs → **check
  the reference repos first** (ARCHIE at `~/Workspaces/tlawrie/asdr`, CHEER at
  `~/Workspaces/walkaboutdev/cheer.dev`) — a working deployment beats inferred metadata
  (e.g. flamingock-springboot-integration was claimed Boot-3-only; CHEER runs it on Boot 4).

## Config / dependencies

- Build via the parent `pom.xml` + per-module poms. Java 21, Spring Boot parent 3.4.x, Log4j2
  (spring-boot-starter-logging is excluded in favour of log4j2 — don't reintroduce Logback).
- Cheapest verification for a backend change: `mvn -q -B -pl <module> -am compile`, then
  `mvn -q -B -pl <module> test` when the touched module has tests.
