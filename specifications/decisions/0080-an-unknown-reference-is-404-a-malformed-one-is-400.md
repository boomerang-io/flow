# 0080 — An unknown reference is 404; a malformed request stays 400

**Status:** accepted · **Date:** 2026-09-16

## Context

Every "does not exist" answer in the API was a `400` carrying a `*_INVALID_REF*` code, because one
enum constant served two conditions: a path that names a resource nobody has, and a request whose
input is blank or unusable. A client could not tell a typo in a URL from a bad body without reading
the code, and generic HTTP tooling — caches, retries, link checkers — treated a missing workflow as
a client protocol error.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Leave the family on 400 and document it | No consumer distinguishes them | The status stays uninformative; every integration special-cases Flow |
| B. Move the whole family to 404 | The codes only ever mean "not found" | Blank and malformed input would answer 404, which is worse than today |
| C. Split: `*_INVALID_REF*` becomes 404, and every blank/malformed guard moves to a `*_INVALID_REQ` code on 400 | Both conditions exist in the same method, as they do here | Every throw site had to be classified, and a few reason strings change on the wire |

## Decision

Option C. A `*_INVALID_REF*` code now answers `404` and means the resource the request path
addresses does not exist (`lib-common/src/main/java/io/boomerang/common/error/BoomerangError.java`);
blank, missing or unusable input answers `400` under the matching `*_INVALID_REQ` code
(`TEAM_INVALID_REQ`, `WORKFLOW_INVALID_REQ`, `TASK_INVALID_REQ`, `PARAMS_INVALID_REQ` join the
existing `WORKFLOWRUN_INVALID_REQ`, `TASKRUN_INVALID_REQ`, `SCHEDULE_INVALID_REQ`). A reference that
does not resolve *inside a body* is a `400`, not a `404`: the route exists, the payload is wrong —
which is why a workflow node naming a missing Task keeps `WORKFLOW_INVALID_TASK_REF` on `400`.

## Consequences

- A client can act on the status alone; `code` and `reason` stay the precise signal (decision 0052).
- Every new "not found" path MUST use a `*_INVALID_REF*` code and every new input guard a
  `*_INVALID_REQ` one; mixing them in one constant is what this decision undoes.
- The webapp needed no change: it calls through axios, which rejects `400` and `404` identically.
