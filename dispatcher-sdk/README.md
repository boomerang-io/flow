# Dispatcher SDK

A dispatcher is any process that takes task runs from the Flow engine, runs them, and reports how they
ended. This library is that protocol as Java: you implement the work, it does the rest — registers with the
engine, long-polls for claims, says each task is starting, renews leases while it runs, reports the end with
retries, and hands terminate orders to your code. Flow's own Kubernetes dispatcher (`service-dispatcher`) is
built on it. The protocol's semantics are in `specifications/dispatcher-contract.md`; the routes and wire
models are `contracts/dispatcher-v1.yaml`. This page is the how-to.

## Get it

Published to this repository's GitHub Packages registry on every product tag, as `io.boomerang:dispatcher-sdk`
with the product's version. Reading GitHub Packages needs a token with `read:packages`.

```xml
<repository>
  <id>boomerang-flow</id>
  <url>https://maven.pkg.github.com/boomerang-io/flow</url>
</repository>

<dependency>
  <groupId>io.boomerang</groupId>
  <artifactId>dispatcher-sdk</artifactId>
  <version>5.0.0</version>
</dependency>
```

It depends on `spring-web` (for `RestClient`) and Jackson, and nothing else: no Spring Boot, no Kubernetes.

## Write one

```java
TaskHandler handler =
    (task, context) -> {
      // task.getSpec() has the image, command, script, env; task.getParams() the resolved params.
      String output = runSomewhere(task);
      context.uploadLog(output);                       // optional: the engine stores and serves it
      return TaskResult.of(List.of(new RunResult("exitCode", "0")));
    };

try (Dispatcher dispatcher =
    Dispatcher.builder()
        .engine("http://flow:7700", dispatcherToken)   // a Flow machine token with an actor kind
        .name("my-dispatcher")                          // with the host, its identity across restarts
        .taskTypes(List.of("template", "custom", "script"))
        .tasks(handler, () -> 10)                       // at most 10 in flight; 0 is no cap
        .build()) {
  dispatcher.start();                                   // returns at once; registers, then polls
  waitForShutdown();
}                                                       // close() drains for 10 s, then stops
```

That is a complete dispatcher. What the handler sees and must do:

| Situation | What to do |
| --- | --- |
| The task succeeded | Return `TaskResult.of(results)` or `TaskResult.of(results, message)`; `TaskResult.empty()` for none |
| It failed with a reason | Throw `new TaskFailure("OOMKilled", message)` or with the results written before the failure |
| The runtime refused it for lack of capacity | Throw `TaskFailure.exceededQuota(message)`: the engine hands it back for another attempt |
| Its work was created but never began | Throw `TaskFailure.startTimeout(message)`: handed back the same way |
| Anything else is thrown | Reported as `DispatchError` |
| The engine ordered it terminated | `context.isCancelled()` turns true and `context.onCancel(action)` runs; `cancel(task)` on your handler is called too. Stop and return; no end is reported |

The SDK has already told the engine the task is starting before `run` is called, and will not call it when
the engine said not to (the task was cancelled while being handed over). The end is retried with backoff until
the engine takes it; the task stays in flight, its lease renewed, until then.

## Shapes

The contract is the boundary, not the process. A dispatcher is its own deployable only when a boundary
separates it from the engine: credentials the engine must not hold, a different network zone, isolation of
untrusted content, or placement on infrastructure the engine cannot reach. Otherwise it is embedded
(`specifications/dispatcher-contract.md`, "Topologies"; decision 0105).

### A worker on infrastructure of its own

Kubernetes, a serverless container, a VM with Docker. The program above, as a service. Register a live log
route if your runtime can stream one (below); provision storage if your tasks mount a shared workspace
(below). Run several with different `taskTypes` or filters to partition work.

### A one-shot worker in a warm pool

A microVM or sandbox that takes one task and exits. Give it capacity 1 and stop after the first task:

```java
CountDownLatch done = new CountDownLatch(1);
Dispatcher dispatcher =
    Dispatcher.builder()
        .engine(url, token).name("sandbox").host(sandboxId)
        .taskTypes(List.of("script"))
        .tasks((task, context) -> { try { return run(task, context); } finally { done.countDown(); } }, () -> 1)
        .build();
dispatcher.start();
done.await();
dispatcher.stop();   // the end has been reported by now; the lease ends with the process
```

Each poll asks for at most one task, so the pool never claims more than it can run. A pool controller
decides how many such workers to keep warm. Two things are not built yet: the engine keeps a registration
per name and host with no expiry, so use a stable `name` and let `host` vary, and there is no queue-depth
read to size the pool from (`dispatcher-contract.md`, "Where the contract ends").

### In-process, inside a host product

The host is both the caller that submits runs and the executor that runs them. Build the dispatcher as a
bean in the host's own process, with the host's `RestClient` so its proxy and certificates apply, and
upload logs from the handler, since there is no route for the engine to read them live:

```java
Dispatcher.builder()
    .client(new DispatcherClient(hostRestClient, engineUrl, token))
    .name("archie").taskTypes(List.of("ai"))
    .tasks(handler, () -> 4)
    .build();
```

No `workflows(...)`, no `logs(...)`: storage and live logs are optional in the protocol and an in-process
dispatcher needs neither.

## Filters and pools

`tasks(handler, capacity)` claims every registered type. To claim a subset, or to run different kinds of
work with different capacity, add pools; each polls on its own thread with its own `limit`:

```java
.taskPool("gpu", TaskFilter.none().types("ai"), () -> 2, aiHandler)
.taskPool("rest", TaskFilter.none().types("template", "script").workflowLabel("team", "payments"), () -> 20, handler)
```

A filter narrows by task type, task slug (`tasks("clone", "scan-*")`) and one workflow label; `*` is the
only wildcard. A poll without filters still claims what a filtered one would; reserving work for filtered
dispatchers is not built (flow#499).

## Storage

Add `.workflows(workflowHandler)` to take workflow runs that declare a shared workspace: the handler
provisions the volume and returns; the SDK reports the run's storage ready. Ask the engine which held
volumes may go with `dispatcher.client().releasable(...)`. Skip all of it when your tasks share nothing.

## Logs

Two optional pieces, independent of each other:

- **Store the finished log.** `context.uploadLog(bytes)` or `uploadLog(String)` from the handler, before
  returning. The engine keeps it (when it has an artifact store and the "Store Task Logs" setting is on) and
  serves it ahead of any live read, so the log outlives whatever ran the task. Best effort: a refusal is
  logged and never fails the task, and an engine with no store is asked once.
- **Serve the live log.** `.logs(url, token)` registers where the engine reads a running task's log, with
  `workflowRef`, `workflowRunRef` and `taskRunRef` as query parameters, and the bearer it presents.
  `Dispatcher.Builder.randomLogsToken()` makes one; your route MUST check `dispatcher.logsToken()` and
  refuse anything else, because a task log is unscrubbed until the engine serves it.

## Leases, reconnect and stop

One heartbeat every 30 s renews the lease of every task in flight (`heartbeatInterval` to change it; zero
sends none, and a claim then has no lease). A poll reconnects at once after work or a full window, and waits
5 s after a failure or an immediate empty answer. `stop()` ends polling and waits 10 s (`drainTimeout`) for
the tasks in flight; one still running then reports nothing, its lease lapses, and the engine hands it out
again, so make `cancel` and a re-run of the same task run id safe.

## Testing your handler

Call it directly with a `TaskRun` and `new TaskContext(task, "d-1", null)`. For the client, bind a
`MockRestServiceServer` to the `RestClient` you pass to `DispatcherClient`; the SDK's own tests under
`src/test` show every route's expected request and answer.
