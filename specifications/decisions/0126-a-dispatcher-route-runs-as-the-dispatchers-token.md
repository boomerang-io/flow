# 0126 — A dispatcher route runs as the dispatcher's token

**Status:** accepted · **Date:** 2026-10-20

## Context

The dispatcher wire checked its bearer token but left the request with no identity. Code a dispatcher route
reached that read the caller (`RelationshipService.check` and `filter`) failed on the missing identity; preparing
an artifact task hit exactly that, and its claim was never handed out.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The dispatcher's own token is the identity | The token is already the caller; it is a `global` token with a machine actor kind | Audit names the dispatcher's token as the actor on request-thread events, where it named `system` |
| B. The synthetic `system` identity on every dispatcher request | One fixed caller for the protocol is wanted | Hides which dispatcher acted; a secured install would run the wire as a caller nobody authenticated |
| C. A new dispatcher scope or actor kind | Dispatcher routes need grants no existing token class expresses | A new token class, its permission rules and a migration, for no grant that exists today |
| D. No identity; make each reader identity-free | Only a few readers are ever reached | Every new reader on the wire repeats the same failure until someone finds it |

## Decision

Option A: `DispatcherAuthFilter` installs the validated token as the request's identity, the same identity the
bearer carries on `/api/v2`, so it grants nothing the token did not already grant
(`dispatcher/DispatcherAuthFilter.java:88-100`). With `flow.dispatcher.auth.enabled=false` nothing is checked and
the request runs as the security-off synthetic admin (`:66-71`). The identity ends with the request.

## Consequences

- Any code on the wire can read its caller as code on `/api/v2` does, in both security modes.
- Reads that should not depend on the caller stay caller-free: the artifact quota is read from the run's workspace
  (`workflow/QuotaService.artifactStorage`).
- Revisit if dispatcher tokens ever carry less than a `global` grant, for example one dispatcher per workspace:
  the identity would then narrow what its routes see.
