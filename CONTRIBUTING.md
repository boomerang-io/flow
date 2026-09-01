# Contributing to Boomerang Flow

Thanks for helping. This is the canonical contributing guide for the Boomerang projects; the copy in
[`boomerang-io/.github`](https://github.com/boomerang-io/.github) mirrors it.

## Issues

- **Bug** or **Feature** — open one with the matching form; the form sets the issue type and adds
  `needs-triage`. A maintainer sets the component (`frontend` / `backend`) and priority during triage.
- **Questions and support** — [Slack](https://join.slack.com/t/boomerang-io/shared_invite/zt-pxo2yw2o-c3~6YvWkKNrKIwhIBAKhaw),
  not the issue tracker.
- **Security** — [private vulnerability reporting](https://github.com/boomerang-io/flow/security/advisories/new),
  never a public issue.

Bugs without a way to reproduce them get `needs-info` and are closed after 14 days; reopen any time with the
missing detail.

## Pull requests

Link the PR to an issue (`Fixes #123`). Fork-and-PR or a branch in the repository both work. Keep the commit
subject to 72 characters and use a conventional-commit prefix (`feat:`, `fix:`, `docs:`, …) so release notes can
be generated. Building and running the product locally is described in the [README](README.md); the design
records that explain *why* things are the way they are live in [`specifications/`](specifications/).

## AI assistance

Use whatever tools you like, but the words in an issue or PR must be your own and you must be able to explain
the change: we do not accept issues or pull requests that the author cannot discuss without an AI.

## Versioning

One product tag builds the whole compatible image set (plain semver on the 5.x line). See the README's
"Packaging and releases".
