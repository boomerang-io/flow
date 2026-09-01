# 0033 — One property, `flow.security.enabled`, gates authentication and authorization, defaulting by mode

**Status:** accepted · **Date:** 2026-08-15

## Context

Authentication (the servlet filter chain) and authorization (the `@AuthCriteria` interceptor) were switched by
two separate properties, so an operator could disable one half and leave the other on — the interceptor then
answered 401 for every annotated route because no identity existed. The merge of flow and engine into one
deployable also introduced `flow.mode`, whose `engine` value has no user-facing surface to secure.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep two properties | operators want to disable authorization while keeping authentication | the half-on state is always wrong; two knobs to document and test |
| B. One property, default `true` everywhere | every mode is secured the same way | `engine` mode would need an explicit override in every embedded deployment |
| C. One property whose default derives from `flow.mode` | `standalone` is the secured product; `engine` is a headless embedded process | the default is implicit; an operator must know the rule |

## Decision

Option C. `FlowSecurityProperties.isSecurityEnabled` returns the property when set, otherwise `true` for
`standalone` and `false` for `engine` (`service-core/src/main/java/io/boomerang/core/security/FlowSecurityProperties.java:23-28`).
`SecurityConfiguration`, `SecurityInterceptorConfiguration` and `SecurityDisabledConfiguration` all key off the
same condition classes (`SecurityEnabledCondition`, `SecurityDisabledCondition`). The deciding reason is that
the two halves are only meaningful together.

## Consequences

- `flow.security.enabled=false` MUST be set explicitly to run the standalone product unsecured (the compose stack does so).
- The dispatcher chain on `/api/v1/**` is deliberately outside this switch; `flow.dispatcher.auth.enabled` governs it.
- `flow.authorization.basic.password` is unrelated — it is the password for Basic authentication.
