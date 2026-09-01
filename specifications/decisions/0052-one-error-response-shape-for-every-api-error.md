# 0052 — One error response shape for every API error

**Status:** accepted · **Date:** 2026-08-18

## Context

Errors arise in three places — domain code throwing `BoomerangException`, the authentication
filter rejecting a request before any controller runs, and Spring's own binding failures. Each
had its own default body, so a client could not handle failures uniformly and the webapp's render
gate stalled on bodies it did not recognise.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Framework defaults (Spring `ProblemDetail` / error page) per layer | No custom codes are needed | Three different bodies; the numbered error catalogue and `messages.properties` would be lost |
| B. One `RestErrorResponse` produced by a single `@ExceptionHandler` class, with codes from one enum | Clients and the webapp need stable `code`/`reason` values across every layer | Every new error path must throw `BoomerangException` or be mapped in the handler |

## Decision

Option B. `core/RestExceptionHandler.java:36-55` renders every `BoomerangException` as
`RestErrorResponse` (`timestamp`, `code`, `reason`, `message`, `status`, optional `cause`), and
`:72-84` maps `AuthenticationException` onto the same shape so a `401` has the same body as a
`400`. Codes and HTTP statuses come from the single `BoomerangError` enum
(`lib-common/src/main/java/io/boomerang/common/error/BoomerangError.java`); messages come from
`service-core/src/main/resources/messages.properties`, keyed by `reason`.

## Consequences

- A client can switch on `reason` (stable string) or `code` (stable number) without inspecting the HTTP status.
- New error conditions MUST be added to `BoomerangError` and `messages.properties` together; an enum constant with no message renders as `No message available` (`RestExceptionHandler.java:47`).
- `service-dispatcher` still carries its own `ErrorDetail`; it is not part of the public API shape.
