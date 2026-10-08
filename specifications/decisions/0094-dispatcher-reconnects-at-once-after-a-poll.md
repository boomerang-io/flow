# 0094 — The dispatcher reconnects at once after a poll

**Status:** accepted · **Date:** 2026-10-02

## Context

The engine already long-polls: it holds a queue request up to 30 s and re-checks every 1 s. The dispatcher
still waited a fixed 5 s after every poll returned, so a task that became ready in that gap waited up to 5 s,
and a chain of short tasks lost about 5 s per hop. The same gap capped one dispatcher at about 4 claims a second.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the fixed 5 s delay | Pickup latency does not matter | Up to 5 s per hop; a 4-claims-per-second ceiling that only exists by accident |
| B. Reconnect at once; an empty or failed poll waits until 5 s after it started | Always | The accidental ceiling goes; no cap on concurrent tasks replaces it |
| C. Wake waiting polls when a task becomes ready | Hops well under 1 s are needed | Touches the admission path; wakes only polls on the same instance |

## Decision

B. Both queue polls run on a 1 ms fixed delay, and `retrieveDispatcherQueue` waits out the rest of 5 s only
when a poll brought nothing back or failed, which keeps a tight loop off an engine that answers at once while
claiming is off (`service-dispatcher/.../client/EngineClient.java:24-33`, `:217-262`). The engine's poll
now also waits its 1 s after a failed claim step instead of retrying at once
(`service-core/.../dispatcher/DispatcherService.java:294-304`).

## Consequences

- A ready task is claimed within about 1 s on every hop, whatever the previous task's duration.
- A dispatcher under a backlog claims as fast as its polls return; the slot cap that bounds it is 0095.
- Option C stays open for a measured need (`performance.md`, limits not yet built).
