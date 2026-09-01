# Boomerang Flow — Claude Code context

Boomerang Flow is an open-source, cloud-native, low-code workflow automation platform. Workflows are
directed acyclic graphs (DAGs) of tasks; tasks run as containers on Kubernetes. Apache-2.0. This is a
Java 25 / Spring Boot 4 Maven monorepo plus one pnpm/Vite frontend.

| Module | What it is |
| --- | --- |
| `service-core` | The product: REST API (`/api/v2`), authentication and authorization, workspaces, workflows, and the DAG execution engine. Runs as `flow.mode = standalone` (default, serves the webapp) or `engine` (headless, security off by default). Eight feature packages: `io.boomerang.{core,workspace,workflow,engine,dispatcher,schedule,event,integrations}`; boundaries by convention, no framework enforcement. |
| `service-dispatcher` | The execution worker. Polls `/api/v1/dispatcher`, claims task runs, heartbeats their leases, runs them through a `TaskExecutor`: `tekton` (default) or `kube-jobs` (plain `batch/v1` Jobs). |
| `service-loader` | Flamingock migrations and seed data, run as a pre-deploy Job once per deploy. The change-unit chain upgrades a v3 database in place and MUST NOT be collapsed. |
| `lib-common` | Shared model, entities, enums, error handling. |
| `client-web` | React 18 + React Router 7 (framework mode, SSR) + IBM Carbon v11 webapp. Served only in `standalone` mode. |

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
| Decision — why something was chosen | `specifications/decisions/NNNN-<title>.md` | Context · Options · Decision · Consequences, ≤ 40 lines, never edited after acceptance except its Status (`superseded by NNNN`). |

Audits, findings logs and work plans do not live here: bugs are GitHub issues, plans are GitHub issues with
sub-issues, history is git. After changing behaviour, update the reference doc that describes it; after
choosing between designs, add a decision (use the `spec-maintenance` skill).

| Reference doc | Covers |
| --- | --- |
| `architecture.md` | Modules, run modes, feature packages, how a run flows through the system, images and versioning. |
| `execution-model.md` | Run states, claims and fencing, compare-and-set transitions, watcher sweeps, timeouts, retry, pause, outbox, schedules, what is deliberately not built. |
| `data-model.md` | Collections, versioning pattern, typed fields vs labels vs annotations, indexes, the migration chain. |
| `authorization.md` | Security switch, authentication paths, token kinds and scopes, permission checks, the relationship walk, security-off identity. |
| `task-runtime.md` | Dispatcher protocol, executors, the parameter/result contract, sensitive values, isolation, task catalogue. |
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
- The engine never calls the flow side synchronously; engine → flow is events.
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
mvn -pl service-core,service-loader -am clean package -DskipTests
cd client-web && pnpm install && pnpm run build && cd ..
docker compose up --build                             # Mongo, loader Job, IDPZero, service-core, client-web
```

- Browser-facing origin for manual and end-to-end testing: `http://localhost:3000` — client-web's own
  server-side-rendering server is the only thing the browser talks to; every service-core call happens
  server-side via `CORE_SERVICE_INTERNAL_ORIGIN`, so service-core needs no CORS. service-core's own
  `http://localhost:7700` stays reachable for direct API use and the dispatcher.
- The stack runs **secured** (`FLOW_SECURITY_ENABLED=true`) with a local IDPZero OpenID Connect provider on
  `:4380` (`docker/idpzero/`); sign-in is a passwordless user picker and the first user to sign in on a fresh
  database becomes admin. Set `flow.security.enabled=false` only for a headless engine — requests then run as
  a synthetic admin (see `authorization.md`).
- `service-dispatcher` is not in the default compose stack. To run it against a laptop Kubernetes (OrbStack) with the
  plain-Jobs executor, layer `docker-compose.kube.yml` (header comment explains the kubeconfig at `docker/kube/config`)
  and run the dispatcher scenarios: `cd e2e && E2E_DISPATCHER=true E2E_KUBECTL_CONTEXT=orbstack npx playwright test tests/dispatcher-kube.spec.ts`.
- Tests: `mvn -pl service-core -am test` (Testcontainers), `cd client-web && pnpm test` (vitest + MSW),
  `cd e2e && npm ci && npx playwright test` against the compose stack.
- Skills: `/spring-module` before any backend Java, `/design-system` before any UI, `/spec-maintenance`
  after any behaviour or design change, `/security-audit`, `/cve-review`, `/release`.

## API errors

Every API error is `io.boomerang.common.error.RestErrorResponse`:

```json
{ "timestamp": "...", "code": 1001, "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(status) have been provided.", "status": "400 BAD_REQUEST" }
```

Codes: `io.boomerang.error.BoomerangError`; messages: `messages.properties`.

## Where the work is

Open work is the GitHub issue tracker of `boomerang-io/flow` (Issue Types Bug / Feature / Task; labels
`frontend`, `backend`, `needs-triage`, `needs-info`, `claude`) and the org project board. There is no
separate roadmap document beyond `ROADMAP.md`. Two reference codebases exist for patterns only — adopt the
pattern, never the code: `/Users/tysonlawrie/Workspaces/tlawrie/asdr` and
`/Users/tysonlawrie/Workspaces/cheerdev/cheer.dev`; the pre-monorepo webapp is at
`/Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web`.
