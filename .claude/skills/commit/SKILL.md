---
name: commit
description: Create a git commit using conventional commits format
disable-model-invocation: true
allowed-tools:
  - Bash(git add:*)
  - Bash(git diff:*)
  - Bash(git status:*)
  - Bash(git commit:*)
  - Bash(git log:*)
argument-hint: [optional message or scope hint]
---

# Conventional Commit

1. Run `git status` and `git diff --staged` to understand changes
2. If nothing is staged, ask whether to stage all changes or specific files
3. Analyze the nature of the changes and determine the correct type:
   - `feat:` — new feature
   - `fix:` — bug fix
   - `docs:` — documentation only
   - `style:` — formatting, no logic change
   - `refactor:` — restructuring without behavior change
   - `test:` — adding/updating tests
   - `chore:` — build, tooling, dependencies
   - `ci:` — CI/CD changes
   - `perf:` — performance improvement
4. Determine scope from the primary area of change. Prefer this repo's module/domain
   names: `flow`, `engine`, `agent`, `common` (the Maven modules), or a finer domain
   like `security`, `workflow`, `token`, `taskrun`. Examples: `feat(engine):`, `fix(security):`.
5. Write the commit message:
   - Subject: `<type>(<scope>): <imperative description>` — 50 chars max
   - Body (if needed): explain _why_, not _what_ — wrap at 72 chars
   - Footer: reference issues if applicable (e.g., `Closes #42`)
6. If $ARGUMENTS is provided, use it as guidance for the message
7. Present the draft message for confirmation before committing
8. Run `git commit -m "message"` only after approval

Never use `--amend` unless explicitly requested.
Never force push.
