---
name: release
description: Cut a Boomerang Flow product release — pre-flight checks, an optional pre-release CVE gate, then one annotated 5.x semver tag that builds and pushes the whole compatible image set via ci-release.yml. Manually triggered via /release.
argument-hint: <semver>   e.g. 5.0.0 or 5.1.0-beta.1 or 5.0.0-rc.2
---

# Release

v5 ships as **one product version line** (DD-03 / AM-9): a single annotated tag in the form
**`5.x.y`** (optionally `5.x.y-beta.z` or `5.x.y-rc.z`) fires `.github/workflows/ci-release.yml`,
which builds and pushes the whole compatible image set to Docker Hub — `flow-service-core`,
`flow-service-dispatcher`, `flow-service-loader`, `flow-client-web` (the exact image names are in
that workflow's `docker/build-push-action` steps) — each tagged `:<semver>`, plus `:latest` on
stable tags only (a `-beta.z`/`-rc.z` tag never repoints `:latest`). The
same tag fires `sbom.yml` for the CVE report. There are **no per-service tags** any more; the old
`<svc>@<semver>` scheme (`flow@4.0.1`) is the retired v4 line.

**Usage**: `/release 5.0.0` · `/release 5.1.0-beta.1` · `/release 5.0.0-rc.2`

## Rules

- `<semver>` MUST match `^5\.[0-9]+\.[0-9]+(-(rc|beta)\.[0-9]+)?$` — the workflow triggers only
  on the `5.` product line. Pre-release suffixes push only `:<semver>` — `:latest` moves on
  stable tags alone.
- Releases are cut from `main` only, from a clean, pushed working tree. (While v5 integration
  lives on `feat-v5`, a release from there needs an explicit user decision.)
- One tag releases EVERYTHING — there is no partial release. If only one module changed, the
  whole set still rebuilds and ships at the new version.
- The annotated tag MESSAGE is the human-facing release note — write it before tagging.

## Steps

### 1. Validate
- Parse and validate `<semver>`. Confirm the tag doesn't already exist (`git tag -l '<semver>'`).
- `git status` clean; on `main`; `git rev-parse HEAD` == `git rev-parse origin/main` (fetch first).
- The latest CI runs on HEAD are green for every module workflow (`ci-core.yml`,
  `ci-dispatcher.yml`, `ci-loader.yml`, `ci-web.yml`, `ci-e2e.yml`). Releasing on red CI needs an
  explicit user decision.

### 2. Pre-release CVE gate (optional, human decision — not a pipeline gate)
- Run the **`cve-review`** skill (or `gh workflow run sbom.yml`, watch, download `cve-report`).
- Report ACTIONABLE findings only — CVEs with an available fix on a **shipped/runtime**
  dependency. Group by package with installed → fixed versions.
- Ask the user: bump packages first or accept and proceed. Any bump happens BEFORE the tag;
  re-run this step after.

### 3. Release notes
- Derive the story from `git log <previous-5.x-tag>..HEAD` (newest existing `5.*` tag).
- Write 2–4 plain, confident sentences: lead with what changed for the user/operator; keep the
  framework/dependency detail as background. No marketing, no exclamation marks.
- Show the draft to the user for approval/edit **before** tagging.

### 4. Tag and hand over
```bash
git tag -a '<semver>' -m "<approved release notes>"
git push origin '<semver>'
```
- Watch the pipeline: `gh run watch` on the triggered `CI Release` run — all image jobs must go
  green; `sbom.yml` runs off the same tag.
- Report the image tags and the run URL. Remind the user that pushing images does **not**
  auto-deploy to any cluster — the environment (and the helm chart, which needs the
  `dispatcher.*` config keys since the DD-06 rename) must be pointed at the new tag separately.

## Failure notes
- If `CI Release` didn't trigger, check the tag matches the `5.[0-9]+.[0-9]+` patterns in
  `ci-release.yml` exactly (a `v5.0.0` prefix or a `4.x` version won't match).
- Docker Hub push failures are usually the `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` secrets —
  surface the job log, don't retry blindly.
