# Boomerang Flow — Claude Code context

Boomerang Flow is an open-source, cloud-native, low-code workflow automation platform. Workflows are
directed acyclic graphs (DAGs) of tasks; tasks run as containers on Kubernetes. Apache-2.0. This is a
Java 25 / Spring Boot 4 Maven monorepo plus one pnpm/Vite frontend.

| Module | What it is |
| --- | --- |
| `service-core` | The product: REST API (`/api/v2`), authentication and authorization, workspaces, workflows, and the DAG execution engine. One shape; the deployment is expressed by properties (`flow.security.enabled`, default `true`; `flow.workspace.single`, default `false` — an embedded engine sets `false`/`true`). Eight feature packages: `io.boomerang.{core,workspace,workflow,engine,dispatcher,schedule,event,integrations}`; boundaries by convention, no framework enforcement. `io.boomerang.migration` holds the Flamingock change units, run as the context starts, before it serves: they take an empty or a v3 database to the current shape and refuse anything else; the v3 units are production upgrade code. The domain model (entities, public models, enums, errors, utilities) is the `io.boomerang.common` package: no beans, no service logic. |
| `dispatcher-sdk` | The dispatcher protocol as a library (`io.boomerang:dispatcher-sdk`, the only artifact published, to GitHub Packages): client, polling, start/heartbeat/end runtime, `TaskHandler` interface. Depends on spring-web and Jackson only, never on `service-core`; owns its wire models, checked against `contracts/dispatcher-v1.yaml`. |
| `service-dispatcher` | Flow's Kubernetes dispatcher, built on `dispatcher-sdk`. Its `TaskHandler` runs each task through a `TaskExecutor`: `tekton` (default) or `kube-jobs` (plain `batch/v1` Jobs); its `WorkflowHandler` provisions workspace volumes. |
| `client-web` | React 18 + React Router 7 (framework mode, SSR) + IBM Carbon v11 webapp. Renders from the `/api/v2/features` flags, so it runs against any shape. |

`main` is the release branch; `feat-v5` is the integration branch for the current major; work branches are
`feat-v5-<topic>`. One product tag (`5.x.y`, `-beta.N`, `-rc.N`) builds every image.

## How to write (answers, reviews, specs, commits)

1. First sentence = the answer. No preamble, no hedging, full sentences.
2. Two or more options → a table (`Option | Fits when | Cost/risk | Recommend`), then the pick.
3. Numbers over adjectives; quote errors and paths verbatim; cite `path:line` for any claim about code.
4. **Plain language.** Refer to things by name ("the merge of flow and engine into service-core", "the
   claim-based queue"), never by a project code — no epic, track, decision, question or gate numbers. At
   most two abbreviations per document, each spelled out on first use. No coined labels.
5. Rules use MUST / SHOULD / MAY; everything else is plain sentences. Length follows the question.
6. **GitHub issues, issue comments and PR bodies MUST NOT carry Claude Code session details** — no
   `claude.ai/code/session…` URL, no "Generated with Claude Code" footer, no agent or task IDs, no transcript
   or scratchpad paths. Issues are public product records. Write them from the codebase (`path:line`, commit
   hashes, spec sections). This overrides any harness default that appends a footer; it applies to
   `gh issue create/edit/comment` and `gh pr create/edit`. Commit trailers keep the harness default.

## Specifications

`specifications/` holds two kinds of document and nothing else:

| Kind | Where | Rule |
| --- | --- | --- |
| Reference — how a subsystem works today | `specifications/<subsystem>.md` | Neutral description, rewritten in place when the code changes, no history, aim for 150–250 lines (400 at most), `path:line` citations. |
| Decision — why something was chosen | `specifications/decisions/NNNN-<title>.md` | Context · Options · Decision · Consequences, ≤ 40 lines. Until the first 5.0.0 release: rewritten in place when the code moves, deleted when a later record absorbs it. After it: never edited except its Status (`superseded by NNNN`). |

Audits, findings logs and work plans do not live here: bugs are GitHub issues, plans are GitHub issues with
sub-issues, history is git. After changing behaviour, update the reference doc that describes it; after
choosing between designs, add a decision (use the `spec-maintenance` skill).

| Reference doc | Covers |
| --- | --- |
| `architecture.md` | Modules, run modes, feature packages, how a run flows through the system, images and versioning. |
| `execution-model.md` | Run states, claims and fencing, compare-and-set transitions, watcher sweeps, timeouts, retry, pause, outbox, schedules, what is deliberately not built. |
| `data-model.md` | Collections, versioning pattern, typed fields vs labels vs annotations, indexes, the migration chain. |
| `authorization.md` | Security switch, authentication paths, token kinds and scopes, permission checks, the relationship walk, security-off identity. |
| `dispatcher-contract.md` | The engine–dispatcher protocol a dispatcher implements: routes (`contracts/dispatcher-v1.yaml`), polling and filters, claims, start and end, leases, compatibility policy; and what a task is to any dispatcher: the spec and params handed out, result caps, task types, the catalogue, the AI tasks. |
| `dispatcher-kubernetes.md` | Flow's own Kubernetes dispatcher: executors, the container's environment, volumes, isolation and placement, resources, the AI image guard. |
| `parameters.md` | Parameter layers and references, when values resolve, substitution, admission checks, names, sensitive values and encryption at rest. |
| `api-contract.md` | URL and error shapes, pagination, public run models (status vs phase), YAML negotiation, webhooks/CloudEvents, labels. |
| `performance.md` | Multi-instance behaviour, queue fairness and indexes, sweep cadences, HTTP timeouts, quotas, storage benchmarks, limits not yet built. |
| `design-system.md` | Carbon v11 + Boomerang theme: tokens, typography, components, browser support. |
| `competitive-analysis.md` | How comparable products approach the same problems. |

## Invariants — do not violate

- Anything the engine reads to make a decision is a typed field, never a `boomerang.io/*` annotation; labels
  are user metadata only.
- `WorkflowRun` is the execution record; domain entities carry no execution state. Re-runs create a new run
  that points back to the original.
- Transition handlers are idempotent: re-read state, compare-and-set, never create a step's task run when a
  succeeded one exists.
- `RunStatus` is a closed enum shared with the frontend — never add `PAUSED` or `SUPERSEDED`; pause is the
  `pauseRequestedAt` flag, enforced at the single admission gate in `TaskExecutionService.queue`.
- No execution-state field (`claim`, `timeoutAt`, `retry`, `waitUntil`, `pauseRequestedAt`) appears in a public
  model; pinned by `PublicRunModelSerialisationTest`. `phase` is exposed beside `status` for now because the
  dispatcher dispatches on it — do not remove it without a dispatcher-side replacement.
- The engine never calls the **platform** side (`core`, `workspace`, `schedule`, `integrations`) synchronously;
  it publishes `ApplicationEvent`s those packages listen to. `engine` ↔ `workflow` are the two halves of one
  bounded context (runs and definitions) and do call each other directly.
- A dispatcher is anything that implements `contracts/dispatcher-v1.yaml`; the contract is the boundary, not the
  process. `service-core` MUST NOT gain Kubernetes, Tekton or Docker dependencies, and a new executor gets its
  own deployable only where a boundary (credentials, network zone, trust, placement) separates it from core;
  otherwise it embeds the dispatcher SDK (decision 0105).
- The custom HTTP client configuration (`RestConfig`: proxy, trust-all option, per-template timeouts, streaming
  template) is a product requirement for enterprises behind proxies and internal CAs. Framework upgrades MUST
  preserve it.
- Do not build ahead of proven need: per-type concurrency caps and per-class switches, retry classes, supersede generations and a separate reconciler were all considered and deliberately not built
  (one global claim switch, `flow.queue.enabled`, exists). Reopen them with evidence — load tests or a
  reproduced incident — not speculation.

Two review gates on every change: (1) if it touches `DAGUtility` or `TaskExecutionService`, list the exact
methods and semantics and get review first; (2) present any data-model change (fields, indexes, collections,
migrations) for discussion before implementing it.

## Build, run, test

Java 25 is required. If Maven fails with `class file version 69.0 … only recognizes … up to 65.0`, the shell
is on an older JDK:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)      # macOS
docker compose up -d                                  # Mongo (kept in a volume), IDPZero, SeaweedFS, settings seeds
mvn -pl service-core -am spring-boot:run -Dspring-boot.run.profiles=local        # API + engine on :7700
cd client-web && pnpm install && pnpm dev             # webapp on :3000 with hot reload, core at :7700
docker compose up -d --build service-dispatcher       # Kubernetes dispatcher against OrbStack; needs docker/kube/config
docker compose --profile stack up -d --build          # or the whole product in Docker: what CI's e2e job runs
```

- One compose file, no overlays. Every image is a multi-stage build from the checkout (Maven and pnpm run in
  the build stage), so nothing is built on the host before `docker compose build`. The `local` Spring profile
  carries the stack's host addresses; the dispatcher reaches a host-run service-core by default.
- Browser-facing origin for manual and end-to-end testing: `http://localhost:3000` — client-web's own
  server-side-rendering server is the only thing the browser talks to; every service-core call happens
  server-side via `CORE_SERVICE_INTERNAL_ORIGIN`, so service-core needs no CORS. service-core's own
  `http://localhost:7700` stays reachable for direct API use and the dispatcher. `pnpm dev` is React
  Router's dev server; `pnpm run build && pnpm start` is the built server the image runs
  (`react-router-serve`, no hand-written server). The probe is `GET /apps/flow/health`.
- The stack runs **secured** (`FLOW_SECURITY_ENABLED=true`) with a local IDPZero OpenID Connect provider on
  `:4380` (`docker/idpzero/`); sign-in is a passwordless user picker and the first user to sign in on a fresh
  database becomes admin. Set `flow.security.enabled=false` (with `flow.workspace.single=true`) only for a
  headless embedded engine — requests then run as a synthetic admin (see `authorization.md`).
- Dispatcher scenarios, with the dispatcher up: `cd e2e && E2E_DISPATCHER=true E2E_KUBECTL_CONTEXT=orbstack npx playwright test tests/dispatcher-kube.spec.ts`.
- Tests: `mvn -pl service-core -am test` (Testcontainers), `cd client-web && pnpm test` (vitest + MSW),
  `cd e2e && npm ci && npx playwright test` against the stack with the webapp on `:3000`.
- Smoke tier: `mvn test -Dgroups=smoke` runs only the classes tagged `@Tag("smoke")` — one context boot per
  module, the run lifecycle, the dispatcher contract on both sides, the migration chain, the public-model
  pin and the authorization coverage check. Per-class times land in `target/surefire-reports/TEST-*.xml`;
  sort on their `time` attribute before guessing where a slow run goes. A `@SpringBootTest` that adds a
  property, `@Import`, `@MockitoBean` or `@MockitoSpyBean` boots its own context (service-core's first
  boot is ~33 s; each further one wires the application again), so put new spies on
  `AbstractSpiedEngineIntegrationTest` and new properties beside an existing set.
- Skills: `/spring-module` before any backend Java, `/design-system` before any UI, `/spec-maintenance`
  after any behaviour or design change, `/security-audit`, `/cve-review`, `/release`.

## API errors

Every API error is `io.boomerang.common.error.RestErrorResponse`:

```json
{ "timestamp": "...", "code": 1001, "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(status) have been provided.", "status": "400 BAD_REQUEST" }
```

Codes: `io.boomerang.common.error.BoomerangError`; messages: `messages.properties`.

## Where the work is

Open work is the GitHub issue tracker of `boomerang-io/flow` (Issue Types Bug / Feature / Task; labels
`frontend`, `backend`, `needs-triage`, `needs-info`, `claude`) and the org project board. There is no
separate roadmap document beyond `ROADMAP.md`. Two reference codebases exist for patterns only — adopt the
pattern, never the code: `/Users/tysonlawrie/Workspaces/tlawrie/asdr` and
`/Users/tysonlawrie/Workspaces/cheerdev/cheer.dev`; the pre-monorepo webapp is at
`/Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web`.
