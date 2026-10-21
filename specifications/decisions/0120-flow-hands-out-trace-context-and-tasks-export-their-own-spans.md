# 0120 — Flow hands out trace context; tasks export their own spans

**Status:** accepted · **Date:** 2026-10-19

## Context

An embedding product asked Flow for a store of model and tool traces. Flow is a workflow engine, not a trace
product: dedicated tools (Langfuse, LangSmith, Phoenix, Opik) already store, search and score traces, and
accept OpenTelemetry. What Flow can give is the shape that makes a run's spans read as one trace.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A trace store in Flow | Flow means to compete with trace products | A second product inside the engine: storage, retention, search and a viewer |
| B. Tasks and dispatchers export OpenTelemetry to the operator's backend, under a trace context Flow derives per run | The operator already has, or can run, a trace backend | Spans arrive only where an exporter is configured |
| C. Nothing beyond the JSON-lines trace in the task log | Nobody needs spans across tasks | No cost, no cross-task view, no backend tooling |

LangChain sends traces to LangSmith through callbacks or OpenTelemetry; Langflow and Dify keep step logs and
configure exporters to LangSmith, Langfuse, Phoenix or Opik by environment; n8n sends its AI nodes to Langfuse.

## Decision

B. Every task run gets a W3C `traceparent` whose trace id is its run's id and whose parent id is its task
run's (`dispatcher-sdk/.../model/TaskRun.java:72`, `dispatcher-contract.md`, "Trace context"). Flow's
Kubernetes dispatcher hands it to every pod as `TRACEPARENT` and, when configured, gives `ai` pods the OTLP
exporter settings (`service-dispatcher/.../executor/TaskImageResolver.java:87-104`); task-ai and embedding
dispatchers emit GenAI-convention spans under it. Derived from ids already on the wire, it changes no route.
Flow is the record of runs; the trace backend is the record of calls.

## Consequences

- The JSON-lines trace in the task log stays the in-Flow record; a backend is an optional add-on.
- A run's spans share one trace id; a retry is a new run, so it is its own trace.
- Flow emits no run or task spans yet, so a backend may show task spans under a parent it never received.
  Revisit, by having service-core emit run and task spans, when an operator needs the run as a span.
- The exporter headers, usually a backend key, sit in the pod spec like the AI token.
