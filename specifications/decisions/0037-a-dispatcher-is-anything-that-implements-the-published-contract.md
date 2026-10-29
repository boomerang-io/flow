# 0037 — A dispatcher is anything that implements the published contract, and is its own process only where a boundary requires one

**Status:** accepted · **Date:** 2026-10-20

## Context

Flow's Kubernetes dispatcher, an in-process dispatcher in a host product and planned serverless, microVM and
Docker executors all take task runs from the engine. Each needs a protocol it can implement outside this repository, a
wire that does not grow with every field the webapp reads, and a rule for when execution is a separate deployable.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Protocol: A. Internal to `service-dispatcher` | One dispatcher only | Every other dispatcher reverse-engineers it and breaks silently on change |
| Protocol: B. A checked-in OpenAPI contract and a software development kit (SDK) that owns its wire models | Several dispatchers, in and out of this repository | Engine and SDK models are two copies, kept in step by the contract |
| Wire: C. The public run models, or one model with a `@JsonView` per surface | Both audiences read the same fields | Every new public field reaches dispatchers unless its author hides it |
| Wire: D. Dispatch records built from the public model at the controller | The audiences differ | Two small records; a field a dispatcher needs is added on purpose |
| Process: E. Execution folded into `service-core`, or a worker image per executor | One deployment shape; or every executor out of core's reach | Core holds cluster credentials and loses the trust boundary; or a deployable and a hop where no boundary exists |
| Process: F. A process only where a boundary exists; otherwise the SDK embedded | Every executor shape | Topology is a judgement made per executor |

## Decision

B, D and F. `/dispatcher/v1/**` is its own OpenAPI group (`dispatcher/DispatcherContractConfiguration.java:22-37`),
checked in at `contracts/dispatcher-v1.yaml`; one `DispatcherContractTest` fails when the engine drifts from the file,
another when the SDK's models do (`dispatcher-sdk/src/test/java/io/boomerang/dispatcher/sdk/DispatcherContractTest.java:52-68`),
so no shared model module couples the two releases. The wire carries `DispatchTaskRun` and `DispatchWorkflowRun`, mapped
with the answer's status and headers kept (`dispatcher/DispatcherControllerV1.java:260-265`). The SDK depends on
spring-web and Jackson only and is the one published artifact (`dispatcher-sdk/pom.xml:54-62,97-103`). A dispatcher is
its own process when it needs credentials core must not hold, another network zone, isolation of untrusted content, or
placement core cannot reach; a Docker dispatcher (development only) and an in-process one embed the SDK. What decided
it: the contract, not the process, is the boundary, as in durable-execution systems whose workers are an embedded SDK.

## Consequences

- `service-core` never gains Kubernetes, Tekton or Docker dependencies; it reaches a dispatcher only through the log
  route and bearer that dispatcher registered (decision 0039, `engine/LogClient.java:63-79`).
- A field a dispatcher needs is added to the record, the contract and the SDK together; additive changes stay on v1,
  a removal or redefinition ships as `/dispatcher/v2`. Registration expiry and a queue-depth read come with the first
  pool or serverless dispatcher.
