# 0020 — One error response shape for API errors: an unknown reference is 404, a malformed request is 400

**Status:** accepted · **Date:** 2026-09-16

## Context

Errors arise in domain code, in the authentication filter before any controller runs, and in Spring's request binding,
and each layer had its own default body. One constant also served both "the addressed resource does not exist" and
"the input is blank or unusable", so a client could not tell a typo in a URL from a bad body without reading the code.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Shape: A. Framework defaults (`ProblemDetail`, error page) per layer | No custom codes are needed | Three bodies; the numbered error catalogue and its messages are lost |
| Shape: B. One `RestErrorResponse` from one handler class, codes from one enum | Clients need a stable `code` and `reason` across layers | Every new error path throws `BoomerangException` or is mapped in the handler |
| Status: C. Every "not found" family on 400, or all on 404 | One of the two conditions never occurs | 400 hides a missing resource from HTTP tooling; 404 for blank input is worse |
| Status: D. `*_INVALID_REF*` on 404 for the addressed resource, `*_INVALID_REQ` on 400 for blank or unusable input | Both conditions occur in one method | Every throw site is classified, and a new one must pick the right family |

## Decision

B and D. `core/RestExceptionHandler.java:50-72` renders every `BoomerangException` as `RestErrorResponse`
(`timestamp`, `code`, `reason`, `message`, `status`, optional `cause`); `:79-91` maps a `@Valid` body failure,
`:98-116` an unreadable parameter type, `:143-171` an `AuthenticationException`, `:177-182` a denied permission (403),
`:188-198` any other exception (500, fixed message) and `:206-221` every framework error, at its own status, onto it. Codes and statuses come from `common/error/BoomerangError.java`; messages from `messages.properties`, keyed by
`reason`. A `*_INVALID_REF*` code answers 404 and means the resource the request path addresses does not exist
(`BoomerangError.java:51,56,79,87`); blank or unusable input answers 400 under the matching `*_INVALID_REQ` code. A
reference that does not resolve inside a body is 400, since the route exists and the payload is wrong:
`WORKFLOW_INVALID_TASK_REF` (`:60`). What decided it: a client can act on the status alone and switch on `reason`.

## Consequences

- A new error condition is added to `BoomerangError` and `messages.properties` together; a constant with no message
  renders `No message available` (`RestExceptionHandler.java:60-61`).
- A framework error carries the HTTP status as `code` and `reason` and Spring's detail as `message`; map it to its
  own constant when a client needs to tell it apart. A failure inside a servlet filter still answers Spring Boot's
  default error body.
