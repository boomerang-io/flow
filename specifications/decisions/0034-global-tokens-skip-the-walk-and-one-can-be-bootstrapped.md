# 0034 — A global token skips the relationship walk, and one can be bootstrapped from configuration

**Status:** accepted · **Date:** 2026-10-15

## Context

A `global` token is an admin or machine credential for the whole platform. Routes such as
`/api/v2/workspace/{workspace}/workflowrun/{id}` pass the path's workspace to `RelationshipService.check`, and a
secured install needs a first such credential before any person signs in, as when a host's backend is its only caller.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Check: walk the containment for global tokens too | A global caller must be told it named the wrong workspace | Extra queries on every admin call, for an error the right URL avoids |
| Check: a global token passes with no walk | Global means the whole platform | The path segment is not validated for a global caller |
| First credential: a person signs in and mints one | A person and an identity provider exist | Neither exists for a host product's backend |
| First credential: a token from configuration | The caller is a machine that already holds secrets | One more secret, and it is full admin |
| First credential: trust a host identity provider's signed tokens | A second caller with its own issuer exists | A verifier, key discovery and claim mapping before anyone needs them |

## Decision

A global token passes `check` once its permissions cover the resource type, with no walk
(`service-core/src/main/java/io/boomerang/core/RelationshipService.java:362,408-410`); `filter` anchors it at `root`
and still walks, so list endpoints honour the workspace (`:507-511`). Ignoring the segment grants nothing: the same
caller reaches the object through its true workspace URL. `BootstrapTokenService` registers
`flow.security.bootstrap-token` at startup as a global `**/**` service token under the fixed id `bootstrap-token`;
a value that is not `bfg_` plus at least 32 characters stops the start, the same value again changes nothing, and a
new value replaces the stored hash and evicts the old one (`core/security/BootstrapTokenService.java:62-104`). It
registers whether or not security is on. It is one property, and the hosts that need it already carry secrets.

## Consequences

- Admin tooling MAY address any object under any workspace path; `key` tokens still walk from their own node.
- Ownership writes MUST NOT trust the path segment for a global caller: a submit finds the workflow inside the named
  workspace (`workflow/WorkflowService.java:567-578`), and a retry takes the owner from the run's or its workflow's
  edge (`workflow/WorkflowRunService.java:483-498,613-666`).
- The bootstrap token is full admin: it lives in a secret and rotates by changing the value and restarting. The
  webapp cannot use it, as `/api/v2/profile` accepts only session and user tokens
  (`workspace/ProfileControllerV2.java:51-54`). Verifying host-issued tokens is not built.
