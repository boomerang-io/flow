# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 5.x     | Yes       |
| 4.x and earlier | No — please upgrade |

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: <https://github.com/boomerang-io/flow/security/advisories/new>.
Do not open a public issue for a security problem.

You will get an acknowledgement within 5 working days. We aim to publish a fix and an advisory within 90 days
of a confirmed report (sooner for actively exploited issues) and will credit you in the advisory unless you ask
otherwise.

## Scope

The `boomerang-io/flow` monorepo (service-core, service-dispatcher, service-loader, client-web) and the images
built from it. Container-image CVEs are tracked by the SBOM pipeline (`.github/workflows/sbom.yml`).
