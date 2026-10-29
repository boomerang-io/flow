---
name: spring-module
description: Spring Boot patterns for the Boomerang Flow backend (Java 25 / Spring Boot 4, MongoDB). Use this skill when creating or modifying any backend Java class — entities, repositories, services, controllers, config — in service-core, dispatcher-sdk or service-dispatcher. Triggers on any Java backend work. If you're writing Java here, read this skill first.
---

# Spring Boot Module Patterns — Boomerang Flow

Java 25, Spring Boot 4.1, Maven multi-module, MongoDB primary store, Lombok, Log4j2.
Match the conventions **already in the codebase** — the examples below are real files; read
the nearest equivalent before writing.

## Modules and package layout

Four Maven modules under the parent `pom.xml`:

| Module | Package roots (`io.boomerang.*`) | Role |
| --- | --- | --- |
| `service-core` | `core`, `workspace`, `workflow`, `engine`, `dispatcher`, `schedule`, `event`, `integrations`, `migration`, `common`, `config` | The product: v2 REST API, authentication and authorization, workspaces, definitions, the DAG engine, the dispatcher-facing v1 API, the database migrations. |
| `dispatcher-sdk` | `dispatcher` | The dispatcher protocol as a library, published as `io.boomerang:dispatcher-sdk`. Depends on spring-web and Jackson only, never on `service-core`; owns its wire models. |
| `service-dispatcher` | `dispatcher`, `executor`, `kube`, `config`, `error` | Flow's Kubernetes dispatcher, built on the SDK: Tekton (default) or plain Jobs. |
| `flow-sdk` | `flow.sdk` | The public API as a library, published as `io.boomerang:flow-sdk`. Depends on spring-web and Jackson only, never on `service-core`; owns its wire models (records) and checks them against `contracts/api-v2.yaml`. |

Within a feature package, code is grouped by role: `entity/`, `model/`, `repository/`, then the
service and controller classes at the package root. Follow the layout of the package you are
editing (e.g. `service-core/src/main/java/io/boomerang/core/`, `.../engine/`).

**Invariants** (from `CLAUDE.md`):
- `io.boomerang.common` holds the domain model only: entities, public models, enums, errors and
  pure utilities. No beans, no repositories, no service logic.
- The engine never calls the platform side (`core`, `workspace`, `schedule`, `integrations`)
  synchronously; it publishes `ApplicationEvent`s those packages listen to. `engine` and
  `workflow` are the two halves of one bounded context and call each other directly.
- `service-core` MUST NOT gain Kubernetes, Tekton or Docker dependencies.
- Anything the engine reads to make a decision is a typed field, never a `boomerang.io/*`
  annotation; labels are user metadata only.

## Entity Pattern

Read `service-core/src/main/java/io/boomerang/core/entity/TokenEntity.java` for the canonical shape.

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
}
```

Key conventions:
- `@Data` (Lombok) on entities; `@JsonIgnoreProperties(ignoreUnknown = true)` + `@JsonInclude(NON_NULL)`.
- **Entities stay Lombok-only — do NOT add nested `Fields` constant classes or `public static final String` field-name constants** (maintainer ruling 2026-08-13). Mongo `Criteria`/`Update` calls use **raw string literals** (`Criteria.where("claim.by")`, `.set("retry.after", …)`) — the house style, consistent with the migration change units. A field-name constants holder is NOT wanted (it clutters the entities); only introduce one with an explicit maintainer exception. This overrides any generic "magic strings → constants" instinct here.
- **Collection name via SpEL** — `#{@mongoConfiguration.fullCollectionName('<name>')}` so the
  configured collection prefix (`flow.mongo.collection.prefix`) is applied. Never hardcode a
  bare collection name.
- `@Id private String id;` (Mongo ObjectId as String).
- The prevailing timestamp type in existing entities is `java.util.Date`. Match the neighbours
  you're editing; do not mix `Date` and `Instant` within one entity.
- `@Indexed` on fields used in filters/lookups. For the engine's claim query, the index is
  correctness-critical — coordinate index changes with `specifications/performance.md` and `data-model.md`.
- **Concurrent state transitions are compare-and-set, not `@Version` and not a lock.** One
  `findAndModify` whose query carries the expected state (and the claim's fencing sequence where
  one exists) and whose update sets the new state; a miss means another writer got there first.
  See `findAndModifyPreImage` in `engine/TaskRunService.java` and `engine/WorkflowRunStateHelper.java`.
- **Data-model changes are a review gate:** present any new field, index, collection or
  migration for discussion before implementing it.

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
- **Atomic claiming** (the dispatcher queue) is the same compare-and-set: one `findAndModify`
  with the claim predicate and the ownership update — never read-then-write. This is the
  concurrency backbone; see `specifications/execution-model.md`.

## Service Pattern

```java
@Service
public class TokenService {

  @Autowired private TokenRepository tokenRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  public TokenEntity create(TokenCreateRequest request) {
    TokenEntity token = new TokenEntity();
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
- Validate state transitions explicitly; throw `BoomerangException` with a `BoomerangError`
  (`io.boomerang.common.error`) rather than a raw `RuntimeException`. Messages live in
  `messages.properties`.
- **Transition handlers must be idempotent** (`CLAUDE.md` invariant): a watcher sweep may call
  them again after a restart. Re-read current state from the database, compare-and-set, and never
  create a step's task run when a succeeded one exists.
- `ApplicationEventPublisher` between feature packages. Outbound CloudEvents (webhooks, event
  sinks) go through the outbox in `io.boomerang.event`.
- **Execution review gate:** a change to `DAGUtility`, `TaskExecutionService` or `ParameterManager`
  (parameter layering, `resolveParamLayers`) lists the exact methods and semantics it touches and
  gets review before implementation. Parameter layering is the v3 contract: `$(params.x)` is the
  flattened global, workspace, workflow and context layers; a task's own params are read only by
  its spec.

## Controller Pattern

Read `service-core/src/main/java/io/boomerang/core/TokenControllerV2.java`.

```java
@RestController
@RequestMapping("/api/v2")
@Tag(name = "Token Management", description = "Create and retrieve Tokens")
public class TokenControllerV2 {

  @Autowired private TokenService tokenService;

  @PostMapping("/token")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.key, AuthScope.session},
      resource = PermissionResource.TOKEN,
      action = PermissionAction.WRITE)
  @Audited(
      action = AuditAction.TOKEN_CREATE,
      resourceType = "token",
      resourceId = "#result?.getId()")
  @Operation(summary = "Create Token")
  public TokenCreateResponse createToken(@RequestBody TokenCreateRequest request) {
    return tokenService.create(request);
  }
}
```

Conventions:
- Name controllers `*ControllerV2` for `/api/v2`, and `*ControllerV1` for the versioned wire
  surfaces: `/dispatcher/v1` (the dispatcher protocol, `contracts/dispatcher-v1.yaml`) and
  `/internal/v1` (the webapp's own server).
- Workspace-scoped resources nest under `/api/v2/workspace/{workspace}/...` in a
  `Workspace*ControllerV2`.
- **Authorization is annotation-driven.** Every endpoint method carries
  `@AuthCriteria(assignableScopes = {...}, resource = PermissionResource.X, action = PermissionAction.Y)`.
  `AuthCriteriaAuthorizationManager` enforces it. A handler with **no** `@AuthCriteria` is denied
  and fails `AuthCriteriaCoverageTest`; the only opt-out is `@AuthExempt(reason = "…")`, which
  skips the permission check — so use it only for a route that is public by design or
  authenticated another way, and say which in the reason. Services then check the data with
  `RelationshipService`. See `specifications/authorization.md` and the `security-audit` skill.
- `resource`/`action` must be real enum values: `PermissionResource` (system, workflow,
  workflowrun, workflowtemplate, taskrun, task, action, user, workspace, token, parameter,
  schedule, insights, integration, webhook, `**`=ANY) and `PermissionAction` (read, write,
  delete, action). `assignableScopes` lists token classes: `session`, `user`, `key`, `global`.
- A mutating endpoint carries `@Audited(action = AuditAction.X, ...)`.
- Document endpoints with Swagger `@Tag` / `@Operation` / `@Parameter`.
- **`status` is the external field.** `phase` stays exposed beside it on `WorkflowRun` and
  `TaskRun` only because the dispatcher dispatches on it. No execution-state field (`claim`,
  `timeoutAt`, `retry`, `waitUntil`, `pauseRequestedAt`, `waitingSince`) appears in a public
  model; `PublicRunModelSerialisationTest` pins it.
- Return the model directly; use `ResponseEntity` only for custom status codes. Use request
  models for input, not entities.

## Deployment shape is properties, not modes

Every bean loads in every deployment; there is no mode switch. Two properties express the shape:
`flow.security.enabled` (default `true`; `false` runs every request as a synthetic admin) and
`flow.workspace.single` (default `false`; `true` pins `{workspace}` to `system`). An embedded
headless engine runs with both flipped. Rules:

- Never add a mode enum or a mode-conditional bean. A deployment-shape switch is a
  `flow.<subsystem>.<switch>` property; a runtime product toggle is a `features.<name>` stored
  setting. A switch lives in exactly one of those tiers.
- A value an operator tunes after deployment (a retention, a switch, a limit) is a key in
  `service-core/src/main/resources/seed/settings.json`, read through `SettingsService` with a
  fallback. Properties are for wiring: URLs, ports, credentials, byte caps that protect the process.
- Anything you add must hold with security off and on a single-workspace install, not only on
  the default secured, multi-workspace shape.

## Code style (maintainer-ruled + Spring team conventions)

Repo rulings (from v5 review feedback — these override any generated-code habit):

- **Class naming is `<Name>Service` / `<Name>Controller`** (maintainer ruling 2026-08-15), with
  `<Name>Client` ONLY for interfaces to an external system, `<Name>ExecutionService` for the
  engine's execution orchestrators, and a small set of error/config shapes. The DOMAIN service
  carries the plain name (`workflow.WorkflowService` = the definition service); when layers
  collide on a simple name, the composition layer takes the marked name — never suffix the
  domain class (`WorkflowDefinitionService`-style names were explicitly overruled).
- **Smallest honest expression.** No long-form if/else with staging variables when a
  guarded expression states the rule. Express the semantics, skip the scaffolding.
  Reference shape: `long timeout = <default>; if (userValue set && valid && not greater)
  timeout = userValue;` — two statements, zero ceremony.
- **Comments state what the code guarantees, in 1–2 lines.** NEVER put process language
  in code: no epic/phase/track codes (E0, D3, T6-3), no "v5", no spec-file citations — git
  history carries provenance. Bad: `// v5 E2/D3 fix, see timeout-audit.md`. Good:
  `// Timeout = the platform default, or the task's own value when set and not greater.`
- **No process language in identifiers.** Class/test names describe BEHAVIOUR
  (`TaskClaimFencingTest`), never phases or scenario numbers (`E0Scenario02...` was
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
- Tests: JUnit Jupiter (its own `Assertions` in most classes, AssertJ in some — match the
  class) and Mockito; static imports fine in tests. Spring suffixes test classes `Tests`;
  this repo uses `*Test` — match the repo.

## Tests

- A `@SpringBootTest` that adds a property, `@Import`, `@MockitoBean` or `@MockitoSpyBean` boots
  its own context (~33 s for service-core's first). Put new spies on
  `AbstractSpiedEngineIntegrationTest` and new properties beside an existing set.
- `@Tag("smoke")` marks the fast tier (`mvn test -Dgroups=smoke`): one context boot per module,
  the run lifecycle, the dispatcher contract, the migration chain, the public-model pin and the
  authorization coverage check. Tag a new class only if it belongs in that list.
- Per-class times land in `target/surefire-reports/TEST-*.xml`; sort on `time` before guessing
  where a slow run goes.

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
- Agent refactors a v3 migration change unit's duplicated helpers → leave them; they are
  production upgrade code. Run `V3DumpMigrationTest` after touching anything in
  `io.boomerang.migration`.
- Agent sees `NoClassDefFoundError` for a class that exists while every Spring context fails
  to load → another build recompiled `target/` under the run; re-run in a worktree of your own.
- Agent adds a condition with `criteria.and("x").is(y);` as a statement, or inside
  `ifPresent` → in Spring Data MongoDB 5 `Criteria.and` adds to a **copy** of the chain, so the
  condition is silently dropped. Assign it back (`criteria = criteria.and("x").is(y)`) or keep
  the whole condition one chained expression.
- Agent asserts a library/framework incompatibility from jar metadata or docs → **check
  the reference repos first** (CHEER at `~/Workspaces/cheerdev/cheer.dev`, and any listed in
  `CLAUDE.local.md`) — a working deployment beats inferred metadata
  (e.g. flamingock-springboot-integration was claimed Boot-3-only; CHEER runs it on Boot 4).

## Config / dependencies

- Build via the parent `pom.xml` (Spring Boot parent 4.1) + per-module poms. Log4j2:
  `spring-boot-starter-logging` is excluded in favour of `spring-boot-starter-log4j2` — don't
  reintroduce Logback.
- `RestConfig` (proxy, trust-all option, per-template timeouts, the streaming template) is a
  product requirement for enterprises behind proxies and internal CAs. Framework upgrades MUST
  preserve it.
- Java 25 is required. If Maven reports `class file version 69.0 … only recognizes … up to 65.0`,
  run `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
- Cheapest verification: `mvn -q -B -pl service-core compile`, then
  `mvn -q -B -pl service-core test -Dtest=<Class>`. `service-dispatcher` builds on the SDK, so it
  takes `-pl dispatcher-sdk,service-dispatcher -am`.
