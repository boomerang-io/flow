# 0062 — Custom HTTP client configuration is a product requirement every upgrade must preserve

**Status:** accepted · **Date:** 2026-07-23

## Context

Enterprises run Flow behind forward proxies and internal certificate authorities, and `service-core` reads task logs
from dispatchers in streams that can stay quiet for minutes. Framework upgrades, including a move from `RestTemplate` to
`RestClient`, tempt a simplification onto framework defaults that would drop the settings these deployments need.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Simplify onto framework defaults at each upgrade | Every deployment reaches the internet directly with public certificates | Proxy-only and private-certificate deployments break silently: connections hang or fail the handshake |
| B. Keep the configuration as a named requirement | Any deployment sits behind a proxy or an internal certificate authority, or streams long logs | A small porting cost and an explicit check at each upgrade |

## Decision

Option B. `service-core/src/main/java/io/boomerang/core/config/RestConfig.java` is the reference behaviour, and an
upgrade MUST keep each of its four settings with equivalent behaviour: proxy routing from `proxy.host`/`proxy.port` on
the external template (`:42-46,56-78`); a trust-all TLS strategy on the insecure and streaming templates (`:118-123`);
connect 10 s, idle read 60 s and pool lease 10 s on every control template (`:50-52,167-185`); and a 10-minute idle read
on `streamingRestTemplate` (`:53,108-116`), which the log client uses (`engine/LogClient.java:56`) so a quiet but
healthy log stream is not cut. "The framework default is fine" is not an acceptable outcome for any of the four.

## Consequences

- Proxy and private-certificate deployments keep working across framework upgrades with no operator change.
- Upgrade reviews MUST check proxy, trust, per-template timeouts and the streaming template explicitly; a move to
  `RestClient` is allowed only with the same settings exposed.
- Trust-all is a deliberate weakening for internal endpoints, scoped to `insecureRestTemplate` and
  `streamingRestTemplate`; it MUST NOT spread to `externalRestTemplate`, which the identity provider and the GitHub
  calls use, the App's installation tokens included (`core/security/OidcTokenVerifier.java:68`,
  `integrations/GitHubService.java:88,143-168`). The GitHub installation lookup still goes through the bundled GitHub
  client, which reads none of these settings (`integrations/GitHubService.java:102,209-222`).
