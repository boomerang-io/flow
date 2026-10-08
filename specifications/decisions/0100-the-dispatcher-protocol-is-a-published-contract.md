# 0100 — The dispatcher protocol is a published contract, and the SDK owns its own wire models

**Status:** accepted · **Date:** 2026-10-08

## Context

ARCHIE will run its own in-process dispatcher, and the non-container executors under discussion are dispatchers
too. The protocol was described only inside `task-runtime.md` and implemented only inside `service-dispatcher`,
tied to Kubernetes and Tekton, and its wire models sit in `lib-common`, which Flow plans to fold into its owners.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Leave the protocol internal | `service-dispatcher` is the only dispatcher | Every other dispatcher reverse-engineers it and breaks silently on change |
| B. A generated OpenAPI file checked in and tested, a semantics document, a compatibility policy; an SDK that owns its wire models | Several dispatchers, in and out of this repository | The engine's models and the SDK's are two copies, kept in step by the contract |
| C. As B, with one shared wire-model module for engine and SDK | The models must be one source | A module that outlives the lib-common fold and couples the engine's release to the SDK's |

## Decision

B. `DispatcherContractConfiguration` publishes `/api/v1/dispatcher/**` as its own OpenAPI group, checked in at
`contracts/dispatcher-v1.yaml`; `DispatcherContractTest` fails on any drift
(`service-core/.../dispatcher/DispatcherContractConfiguration.java`). `dispatcher-contract.md` gives the semantics
and the compatibility policy: additive changes stay on v1, breaking ones go to `/api/v2/dispatcher`. The
`dispatcher-sdk` module (flow#504) owns its wire models as plain Jackson types and is the only artifact
published to GitHub Packages; the engine keeps its own models.

## Consequences

- A dispatcher can be written outside this repository against a versioned document.
- A change to a dispatcher route or wire model changes the checked-in contract in the same pull request.
- A conformance suite for dispatcher implementations is not built; revisit when a second dispatcher ships.
