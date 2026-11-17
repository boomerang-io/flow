---
name: release
description: Cut a Boomerang Flow product release — pre-flight checks, an optional pre-release CVE gate, then one annotated 5.x semver tag that builds and pushes the whole compatible image set via ci-release.yml. Manually triggered via /release.
argument-hint: <semver>   e.g. 5.0.0 or 5.1.0-beta.1 or 5.0.0-rc.2
---

# Release

v5 ships as **one product version line**: a single annotated tag in the form
**`5.x.y`** (optionally `5.x.y-beta.z` or `5.x.y-rc.z`) fires `.github/workflows/ci-release.yml`,
which builds and pushes the whole compatible image set to Docker Hub — `flow-service-core`,
`flow-service-dispatcher`, `flow-client-web` (the exact image names are in
that workflow's `docker/build-push-action` steps) — each tagged `:<semver>`, plus `:latest` on
stable tags only (a `-beta.z`/`-rc.z` tag never repoints `:latest`). The
same tag fires `sbom.yml` for the CVE report. There are **no per-service tags** any more; the old
`<svc>@<semver>` scheme (`flow@4.0.1`) is the retired v4 line.

**Usage**: `/release 5.0.0` · `/release 5.1.0-beta.1` · `/release 5.0.0-rc.2`

## Rules

- `<semver>` MUST match `^5\.[0-9]+\.[0-9]+(-(rc|beta)\.[0-9]+)?$` — the workflow triggers only
  on the `5.` product line. Pre-release suffixes push only `:<semver>` — `:latest` moves on
  stable tags alone.
- Every tag, stable or `-beta.N`/`-rc.N`, is cut from `main`, the integration and release branch.
  The tree is clean and pushed; any other branch needs an explicit user decision.
- **Betas are the default pre-release.** Tag the next `-beta.N` unless the user asks for a
  release candidate. A release candidate is an existing beta promoted: tag the commit that
  already carries a beta with the next `-rc.N` (the rc number keeps climbing; never reuse or
  restart it). The rc tag fires the release again, so the same commit is rebuilt and pushed
  under the rc tag.
- Semver compares pre-release labels alphabetically: every `-beta.N` sorts below every `-rc.N`
  of the same version, even a newer beta (`5.0.0-beta.34` < `5.0.0-rc.8`). Never pick "the
  latest" tag by version sort; sort by date (`git for-each-ref --sort=-creatordate
  --count=1 'refs/tags/5.*'`). Tell consumers, and the chart's `appVersion`, to pin the exact tag.
- One tag releases EVERYTHING — there is no partial release. If only one module changed, the
  whole set still rebuilds and ships at the new version.
- The annotated tag MESSAGE is the human-facing release note — write it before tagging.
- **A stable tag carries its final changelog.** The `CHANGELOG.md` section for `5.x.y` is written,
  reviewed by the user and merged to `main` BEFORE the tag, and the tag is cut on that commit. The
  images are built from the tag, so a changelog fixed afterwards means repointing a released tag
  (see Failure notes). Pre-release tags (`-beta.N`, `-rc.N`) leave the section as `Unreleased`.
- **A stable section records what changed since the previous stable release**, not between
  pre-releases. For 5.0.0 that is v3 (3.5.x): a change that only differs from an earlier beta, the
  tuning of something new in this version, and a fix to a problem introduced during this version's
  own development are left out. Git history and closed issues keep those.
- A stable tag needs the user's explicit approval of both the changelog section and the tag message.

## Steps

### 1. Validate
- Parse and validate `<semver>`. Confirm the tag doesn't already exist (`git tag -l '<semver>'`).
- `git status` clean; on `main`; `git rev-parse HEAD` == `git rev-parse origin/main` (fetch first). Other
  sessions may hold uncommitted work in the main checkout, so tag from a worktree of your own
  when `git status` is not clean.
- The latest CI runs on HEAD are green for every module workflow (`ci-core.yml`,
  `ci-dispatcher.yml`, `ci-web.yml`, `ci-e2e.yml`). Releasing on red CI needs an
  explicit user decision.

### 2. Pre-release CVE gate (optional, human decision — not a pipeline gate)
- Run the **`cve-review`** skill (or `gh workflow run sbom.yml`, watch, download `cve-report`).
- Report ACTIONABLE findings only — CVEs with an available fix on a **shipped/runtime**
  dependency. Group by package with installed → fixed versions.
- Ask the user: bump packages first or accept and proceed. Any bump happens BEFORE the tag;
  re-run this step after.

### 3. Changelog (stable tags only)
- Rewrite the `Unreleased` section of `CHANGELOG.md` against the previous stable release (see Rules):
  check each bullet against that release's code (its tag, e.g. `3.5.54-fix.35` for v3) and drop
  pre-release history. Keep `Upgrading` complete: every step an operator of the previous stable
  release must take.
- Rename the heading to `## 5.x.y (YYYY-MM-DD)` and open an empty `## Unreleased` above it.
- Show the user the result and the list of dropped bullets; on approval, open a pull request, have it
  merged, wait for CI on `main`, and tag THAT commit in step 5.

### 4. Release notes
- Start from the changelog section (stable) or the `Unreleased` section (pre-release): every
  operator-facing change and upgrade step listed there MUST appear in the notes. Then derive the rest
  of the story from `git log <previous-5.x-tag>..HEAD` (the most recently created `5.*` tag, by date,
  not by version). For a release candidate on a beta's commit, reuse that beta's notes.
- A stable release opens with one upbeat sentence for a business reader or end user (what they can do
  now), then the technical change, then upgrading. A pre-release is 2–4 plain sentences on what
  changed for the user or operator. Keep framework and dependency detail as background. No
  exclamation marks.
- Show the draft to the user for approval/edit **before** tagging.

### 5. Tag and hand over
```bash
git tag -a '<semver>' -m "<approved release notes>"
git push origin '<semver>'
```
- Watch the pipeline: `gh run watch` on the triggered `CI Release` run — all image jobs must go
  green; `sbom.yml` runs off the same tag.
- Report the image tags and the run URL. Remind the user that pushing images does **not**
  auto-deploy to any cluster — the environment (and the helm chart, which needs the
  `dispatcher.*` config keys) must be pointed at the new tag separately.

## Failure notes
- If `CI Release` didn't trigger, check the tag matches the `5.[0-9]+.[0-9]+` patterns in
  `ci-release.yml` exactly (a `v5.0.0` prefix or a `4.x` version won't match).
- Docker Hub push failures are usually the `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` secrets —
  surface the job log, don't retry blindly.
- Repointing a released tag is a last resort and needs the user's decision. Delete it on the remote
  and locally (`git push origin :refs/tags/<semver>`, `git tag -d <semver>`), recreate it on the new
  commit with the approved message, and push. The release runs again: the images get new digests
  under `:<semver>` (and `:latest`), while the SDK publish skips a version already published, so the
  jars stay as first built. Tell every consumer to re-pull, and generators that read the tag to fetch
  it again.
