# 0110 — A machine token can be bootstrapped from configuration

**Status:** accepted · **Date:** 2026-10-15

## Context

A secured install needs a first credential before anything can call it. Where a person signs in, the first
user becomes admin and mints the rest. Where no person will sign in — a host product's backend is the only
caller — nothing can mint that first token, and turning security off to get one leaves a window with every
request running as admin.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A first user signs in and mints one | A person and an identity provider exist | Neither exists for a host product's backend |
| B. A token from configuration (Prefect's auth string, Kestra's basic user, Windmill's super-admin secret) | The caller is a machine that already holds secrets | One more secret, and it is full admin |
| C. Trust a host identity provider's signed tokens (Temporal, Airflow 3) | A second caller with its own issuer exists | A verifier, key discovery and claim mapping to build before anyone needs it |

## Decision

Option B. `BootstrapTokenService` registers `flow.security.bootstrap-token` at startup as a global `**/**`
service token under the fixed id `bootstrap-token`; the value must be `bfg_` and at least 32 further characters
or the start fails, a restart with the same value changes nothing, and a changed value replaces the hash and
evicts the old one (`service-core/src/main/java/io/boomerang/core/security/BootstrapTokenService.java:63-89`).
It registers whether or not security is on, so the token works the moment security is turned on. The deciding
reason: it is one property, the hosts that need it already carry secrets, and C is deferred until a second
caller with its own issuer exists.

## Consequences

- The value is full admin, so it lives in a secret and is rotated by changing it and restarting.
- The webapp cannot use it: `/api/v2/profile` refuses every global token (`EndpointAuthorizationResponseTest`).
- Local sign-in without an identity provider is #526; verifying host-issued tokens is not built.
