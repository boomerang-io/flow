# 0135 — An integration shares its credentials as managed workspace parameters

**Status:** accepted · **Date:** 2026-10-28

## Context

A catalogue task that calls GitHub or Slack needs a token (and for GitHub an API URL), typed into every node by hand. Comparable products
keep credentials in a connection a task references by name. Flow already has a per-workspace store tasks read by
name, encrypted at rest and masked on read: workspace parameters.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A connections layer: a new collection and a `$(connections.<name>.<field>)` reference scope | Credentials need a lifecycle parameters cannot carry (per-user OAuth, scopes per task) | A new collection, a new reference scope through admission, substitution, taint and the editor, and a second place users look for a value |
| B. The integration writes its credentials into the workspace as read-only parameters, and its catalogue tasks default to them | A credential is one value per workspace | Short-lived tokens need a refresher; the parameter must be protected from edits; a template default is global while the parameter is per workspace |

## Decision

B. Integrations become ordinary parameters to the engine, which never calls an integration and needs no new reference
scope. An integration template's `fields` name the parameters it writes (`integrations/entity/IntegrationTemplateEntity.java:26-27`);
sharing is a choice made after linking (`integrations/IntegrationService.java:118-156`); a managed parameter is stored
read-only and refused to workspace writes and deletes (`workspace/WorkspaceService.java:528-537,555-565`); the GitHub
token is replaced every 45 minutes by a targeted write (`integrations/GitHubService.java:191-228`,
`integrations/IntegrationParameterService.java:136-157`); Slack's bot token does not expire and is shared as stored
(`integrations/SlackService.java:686-703`).

## Consequences

- A user can still type a personal token on a node, which overrides the default; admin tasks the App cannot do keep
  working that way.
- Sharing lets every workflow author in the workspace act with the App's permissions, so it is never automatic.
- A GitHub or Slack task left on its default in a workspace that shares nothing fails at admission with `no such
  workspace param 'github-token'` (or `'slack-token'`), not at save.
- A Slack app with token rotation on cannot share: its token expires and Flow refreshes only GitHub's.
- With `features.workspaceParameters` off nothing can be shared; sharing is refused rather than bypassing the flag.
- Revisit for per-user credentials, or an integration needing several connections in one workspace.
