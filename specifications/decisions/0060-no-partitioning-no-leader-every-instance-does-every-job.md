# 0060 — No partitioning and no leader election: every instance does every job

**Status:** accepted · **Date:** 2026-07-23

## Context

Several `service-core` instances run against one MongoDB and all of them receive inbound events, serve
dispatcher claim polls, and run the recovery sweeps. The earlier scaling brief proposed routing each
workflow run's events to one owning instance and electing a leader for the sweeps, so that only one
instance would act on a given run at a time.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Partition runs across instances (sticky routing by run id) | Handlers are not safe to run twice | Needs consistent hashing or sticky load balancing, membership tracking, and rebalancing when an instance dies — the same failure class as leader election. Does not give ordering either. |
| B. Elect a leader for the sweeps | Sweeps are expensive or destructive | Leader death stalls every sweep until re-election; split-brain still forces per-write fencing, so compare-and-set (CAS) is needed anyway. |
| C. Every instance does everything; every action is a single-document CAS | Handlers are idempotent and duplicates are cheap | Losers pay one no-op `findAndModify`; overlapping sweeps cost a few extra indexed queries. |

## Decision

Option C. Every state change is a CAS whose query encodes the expected prior state, so a second
instance acting on the same run finds the state already changed and performs no side effects
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:232-270`;
`engine/WorkflowRunStateHelper.java`). The sweeps run on every instance with a random start offset
and act only through those primitives (`engine/WorkflowWatcher.java:36-41,116-119`). Partitioning
would only save the loser's no-op, and a leader would add the one failure mode the sweeps exist to
remove.

## Consequences

- Deployment stays a plain load balancer in front of N identical instances; adding an instance needs no
  coordination and losing one loses nothing but latency (bounded by one sweep interval).
- In-process events are latency hints only; the sweeps are the guarantee, so a lost event costs at most
  one interval.
- Sweep cost grows linearly with instances (a few indexed queries per instance per interval). If it ever
  matters, the escalation is cooperative `_id`-hash sharding of the sweep page, still without a leader.
