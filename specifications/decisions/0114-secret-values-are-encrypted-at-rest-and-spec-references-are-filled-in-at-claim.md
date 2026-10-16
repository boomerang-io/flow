# 0114 — Secret values are encrypted at rest; spec references are filled in at claim

**Status:** accepted · **Date:** 2026-10-16

## Context

Secret parameter values were stored as plaintext in definitions, runs, task runs and schedules. Admission also wrote the task's spec with every reference already substituted, so a secret used in a script was stored in plaintext in the task run's `spec`, where no type marks it.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Encrypt the whole spec field | Any spec may hold a secret | The spec is unreadable on every read and needs decrypting for display, search and the value scrub |
| B. Insert ciphertext tokens into the spec | The dispatcher can decrypt | The key reaches every dispatcher; the claim payload and the protocol change |
| C. Environment-variable references (`$PARAM_X`) in place of values | Every task is a shell | Breaks a `command`/`arguments` that takes no shell, and changes what an author writes |
| D. Freeze values at admission, fill in at claim from a frozen-value field | Nothing may drift | A new stored field holding the same secrets, which has to be encrypted too |
| E. Fill in at claim: task params frozen at admission, global and workspace values read live | A short drift window is acceptable | Global and workspace values written straight into a spec can change between admission and claim |

## Decision

E, with database-level encryption of the typed values. A Spring Data Mongo hook encrypts secret values in the stored document and decrypts them before mapping (`core/config/SecretValueMongoListener.java`), using the settings cipher (`core/SecretCipher.java`). Everything in memory stays plaintext, so masking and the value scrub are unchanged. Admission stores the resolved params and leaves the spec's references in place (`engine/TaskRunService.java` `tryAdmit`). The claim fills in a copy of the spec (`engine/ParameterManager.java` `fillSpec`, `dispatcher/DispatcherService.java`). E adds no stored field and no change to the dispatcher protocol.

## Consequences

- Task params are frozen at admission, so a requeued task gets the same values. No secret is written into a stored spec.
- A workspace or global parameter referenced directly in a spec field is read at claim. It can drift between admission and claim, or across a requeue. This is accepted. Ways to close the gap, if a reproduced incident calls for it:
  - an editor warning for direct references;
  - detecting a change at claim through the parameter's modified time;
  - versioning global and workspace parameters and recording the version in the reference (`$(global.params.x@3)`);
  - a per-run snapshot of the layers.
- Every Flow reference must resolve at admission (`ParamUnresolved` otherwise), so the claim's fill-in fails only for a workspace or global parameter deleted between the two, which ends the task `failed` with `SpecFillFailed`.
- Existing plaintext values stay plaintext until their next save; there is no migration.
- Workflow templates, task results and the parameter annotations on old runs are not encrypted.
