# 0120 — Flow hands out trace context; tasks export their own spans

**Status:** accepted · **Date:** 2026-10-19

## Context

An embedding product asked Flow to store model and tool traces. Dedicated products (Langfuse, LangSmith, Phoenix, Opik)
already store, search and score traces and accept OpenTelemetry; what Flow can give is the shape that makes a run's
spans read as one trace. LangChain, Langflow, Dify and n8n all export to such a backend rather than store traces.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A trace store in Flow | Flow means to compete with trace products | A second product inside the engine: storage, retention, search and a viewer |
| B. Tasks and dispatchers export OpenTelemetry to the operator's backend, under a trace context Flow derives per run | The operator has, or can run, a trace backend | Spans arrive only where an exporter is configured |
| C. Nothing beyond the JSON-lines trace in the task log | Nobody needs spans across tasks | No cross-task view and no backend tooling |

## Decision

B. Every task run gets a W3C `traceparent` whose trace id is its run's id and whose parent id is its task run's
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/model/TaskRun.java:50-59`). Flow's Kubernetes dispatcher
hands it to every pod as `TRACEPARENT` and, when `flow.dispatcher.ai.otel.endpoint` is set, gives `ai` pods the
exporter settings and resource attributes (`service-dispatcher/.../executor/TaskImageResolver.java:87-105`); the
`task-ai` worker and embedding dispatchers emit their spans under it. Derived from ids already on the wire, it changes
no route. Flow is the record of runs; the trace backend is the record of calls.

## Consequences

- The JSON-lines trace in the task log stays the in-Flow record; a backend is optional.
- A run's spans share one trace id; a retry is a new run, so it is its own trace.
- `service-core` emits no run or task spans, only its own request tracing when enabled, so a backend may show task
  spans under a parent it never received. Revisit, by emitting run and task spans, when an operator needs them.
- The exporter headers, usually a backend key, sit in the pod spec, as `flow.dispatcher.ai.token` does.
