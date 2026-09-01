# 0062 — Custom HTTP client configuration is a product requirement every upgrade must preserve

**Status:** accepted · **Date:** 2026-07-23

## Context

Enterprises run Flow behind private networks, forward proxies, and internal certificate authorities.
The outbound client configuration (`RestConfig`) carries proxy routing, a trust-all template for
self-signed internal certificates, explicit per-template timeouts, and a dedicated long-read streaming
template. Framework upgrades (including any move from `RestTemplate` to `RestClient`) tempt a
simplification onto framework defaults that would drop these knobs.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Simplify onto framework defaults at each upgrade | All deployments reach the internet directly with public certificates | Proxy-only and private-CA deployments break silently: connections hang or fail TLS with no product-level error. |
| B. Keep the custom configuration as a named requirement; every upgrade must provide equivalent behaviour | Any deployment sits behind a proxy or an internal CA, or streams logs for minutes | Each upgrade carries a small porting cost and a check that the four knobs still work. |

## Decision

Option B. `service-core/src/main/java/io/boomerang/core/config/RestConfig.java` is the reference
behaviour: proxy routing from `proxy.host`/`proxy.port` (`:42-46,58-78`), a trust-all TLS strategy for
the insecure and streaming templates (`:120-123`), connect 10 s / idle read 60 s / pool lease 10 s on
every control template (`:48-52,167-185`), and a 10 min idle read on `streamingRestTemplate` so a
quiet-but-healthy log stream is not cut (`:53,110-116`). An upgrade MUST preserve each of these with
equivalent behaviour; "the framework default is fine" is not an acceptable outcome for any of them.

## Consequences

- Proxy and private-CA deployments keep working across framework upgrades without operator changes.
- Upgrade reviews MUST check the four knobs explicitly: proxy, trust, per-template timeouts, streaming
  template. A migration to `RestClient` is allowed only with the same knobs exposed.
- The trust-all template is a deliberate weakening for internal endpoints; it is scoped to the
  `insecureRestTemplate` and `streamingRestTemplate` beans and MUST NOT spread to the external template.
