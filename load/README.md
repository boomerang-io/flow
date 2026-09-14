# Load harness

`run.mjs` is a dependency-free Node 20+ script that submits many runs of one workflow against a
running `service-core`, waits for every run to finalize, and prints latency, throughput and status
counts. It measures the engine as a client sees it; it does not measure Kubernetes or a dispatcher
unless you choose the `dispatch` profile. Results are recorded in
`specifications/performance.md` ("Throughput baseline").

```bash
node load/run.mjs --help
FLOW_TOKEN=bfg_... node load/run.mjs --runs 200 --concurrency 20
```

| Setting | Flag / env | Default | Meaning |
| --- | --- | --- | --- |
| Origin | `--url` / `FLOW_URL` | `http://localhost:7700` | `service-core`'s own origin, not the webapp on `:3000` |
| Token | `--token` / `FLOW_TOKEN` | required | A `bfg_` global token (see below) |
| Workspace | `--workspace` / `WORKSPACE` | `load` | Created on first use |
| Runs | `--runs` / `RUNS` | 50 | Total submissions |
| Concurrency | `--concurrency` / `CONCURRENCY` | 5 | Submitters in flight at once |
| Profile | `--profile` / `PROFILE` | `inline` | `inline` or `dispatch` (below) |
| Fan-out | `--fanout` / `FANOUT` | 4 | Parallel `setwfproperty` tasks per run |
| Deadline | `--deadline` / `DEADLINE` | 900 s | Runs not finalized by then are reported as stuck |

Exit code is 0 only when every submission returned 2xx and every run finalized as `succeeded`.

## Profiles

| Profile | Workflow | Needs |
| --- | --- | --- |
| `inline` | `start → setwfproperty → decision → setwfproperty ×FANOUT → end`, plus one never-taken decision branch. Every task type is executed inside the engine (`TaskExecutionService`), so the numbers are the engine and MongoDB only. | The compose stack |
| `dispatch` | `inline` plus one `execute-shell` script task (`echo load`) before `end`, which only a dispatcher can claim and run as a container. | `service-dispatcher` running against a cluster: layer `docker-compose.kube.yml` as described in its header comment |

The workflow is named `load-<profile>-fanout<N>` and reused across invocations; delete it (or
the workspace) to recreate it.

## What is printed

- Submit phase: ok/error counts, submits per second, latency p50/p95/p99/max of `POST .../submit`
  (which starts the run by default).
- Time to complete as the client sees it (submit sent → terminal `status` observed, polled once per
  second in pages of 100 ids via `GET .../workflowrun/query?workflowruns=...`), the server's own
  `duration`, and time to finalize (→ `phase=finalized` observed).
- Completed runs per minute and executed task runs per second (tasks except `start`, `end` and
  the unmatched decision branch), finalized runs per minute, per-status counts, and the ids of
  runs that never reached a terminal status.
- Every non-2xx response grouped by route and status, with one sample body.

Completion and finalization are reported separately on purpose. A run without workspaces is
finalized only by the watcher sweep — 50 runs every 30 s per instance
(`WorkflowWatcher.finalizeWorkspacelessRuns`) — so `finalized` trails `succeeded` by up to
`outstanding / 100` minutes and the script keeps polling until the deadline. The exit code depends
on status only. Runs left unfinalized by a previous invocation are still drained by that sweep, so
either wait for it or start from an empty run collection before comparing numbers.

Polling costs the server one indexed list query per 100 outstanding runs per second; it is
included in the numbers, as any real client would be.

## Minting the token

The compose stack is secured, so the harness needs a global token. Either:

1. Sign in at `http://localhost:3000` (IDPZero user picker; the first user becomes admin), then
   Admin → Tokens → create a token of type `global` with permission `**/**`, or
2. Run `FLOW_TOKEN=$(node load/mint-token.mjs)`, which performs the same sign-in against the
   stack's IDPZero without a browser (authorization code + PKCE, `POST /api/v2/auth/exchange`,
   then `POST /api/v2/token`) and prints a fresh `bfg_` token valid for 24 h. It signs in as
   `usr-flow-admin`, the user the Playwright suite also uses, so on a fresh database that user
   becomes the admin. If something else on the machine already owns host port 4380 (a host-run
   `idpzero serve` for another project does), point the helper at the container directly:
   `IDP_ADDRESS=$(docker inspect flow-idpzero-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'):4380`.

Either way the underlying route is `POST /api/v2/token` with
`{"type":"global","name":"load","permissions":["**/**"]}` from a session that already holds a
global grant (`TokenService.create` refuses otherwise).
