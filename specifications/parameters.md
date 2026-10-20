# Parameters

How a parameter value reaches a run and a task: the layers a reference reads, when the engine resolves it, what
substitution does to the text, what admission checks, which names are allowed, and how a secret is typed, hidden
and stored. What the dispatcher then receives is in `dispatcher-contract.md` ("What a poll hands out"). Paths
below are under `service-core/src/main/java/io/boomerang/` unless they start with a module name.

## Layers and references

The global, workspace and workflow-context values are not copied onto the run. `ParameterManager` reads them
from their stores each time it resolves (`engine/ParameterManager.java:527`, through
`workflow/ParamLayerService.java:119`): at run start for the run's params (`engine/WorkflowExecutionService.java:70`),
at each task's admission (`TaskExecutionService.java:204`), at its claim for the spec, and for a for-each task's
items at fan-out (`:337`). It reads them for the run's workspace (`boomerang.io/workspace-name`), and takes the
workflow context - name, display name, id, version and workflow tokens - from the run's workflow and the revision
it runs, so a version saved mid-run does not change it. The layers and the revision are served from a per-instance
cache for 10 seconds (`core/ParamLayerCache.java`, `flow.parameters.layer-cache.ttl`; `0s` turns it off), so tasks
admitted together share one read of each store. A global or workspace parameter edited mid-run reaches the tasks
admitted after the edit: at once on the instance that took the edit, which clears its cache, and within the TTL on
the others. Each task run keeps the param values it was admitted with.

Parameter references, by where the value comes from (`engine/ParameterManager.java`, `common/model/ParamLayers.java`):

| Reference | Value from |
| --- | --- |
| `$(params.x)` | The nearest layer that defines `x`: the task, then the workflow, then workspace and global. In a task param named `x` that is the param itself, so it reads the workflow's as `$(workflow.params.x)` |
| `$(workflow.params.x)` | The workflow's parameters |
| `$(workspace.params.x)` | The workspace's parameters. `$(team.params.x)` still resolves, is deprecated, logs a warning once per reference, and is removed in the next major version; the editor suggests only `workspace` |
| `$(global.params.x)` | Global parameters |
| `$(context.params.x)` | Run context (`workflowrun-*` keys) |
| `$(tasks.t.results.r)` | A result of task `t` |

## When a value resolves

The engine fills in `$(params.x)`, `$(tasks.x.results.y)` and the other references in two places, at two times:
the TaskRun's own parameter values at admission (`engine/ParameterManager.java:148`,
`TaskExecutionService.java:204`), frozen by the admission compare-and-set (`TaskRunService.java:329`) with secret
values encrypted; and `spec.script`, `spec.command`, `spec.arguments` and `spec.envs` when a dispatcher claims the
task (`ParameterManager.fillSpec` `:292`, called from `dispatcher/DispatcherService.java:329,381`), where the stored
spec keeps its references and only the copy on the claim carries values. So no secret value is ever written into
a stored spec. At claim, `$(params.x)` reads the task's params as frozen at admission, so a requeued task gets the
same values; `$(tasks.t.results.r)`, `$(workflow.params.x)` and `$(context.params.x)` read the run;
`$(workspace.params.x)` and `$(global.params.x)` written straight into a spec field are read from their stores, so
an edit since admission, or across a requeue, shows (decision 0116). Admission has checked every reference
(below), so the fill-in fails only for a workspace or global parameter deleted since; the task then ends
`failed` with reason `SpecFillFailed` (`DispatcherService.java:387`). The TaskRun's params are the task's declared
params merged with the values authored on the workflow node (`engine/DAGUtility.java:180`).

## Substitution

Substitution writes into string leaves directly, so a replacement's quotes, newlines, backslashes and `$` are
inserted verbatim: a multi-line prompt, a JSON body or a result with a trailing newline reaches the container byte
for byte (`ParameterManager.replaceStringInObject`). It also runs over the values it inserts, so configuration
composes - a workspace value containing `$(global.params.x)` resolves - except for three kinds of value, inserted
as written with their `$(` escaped (`ParameterManager.java:596,836`):

| Inserted as written | Why |
| --- | --- |
| A task result, `$(tasks.t.results.r)` | It is what a task produced, not what anyone in the workspace wrote |
| A supplied run param, read as `$(params.x)` or `$(workflow.params.x)` | On a run started by a webhook, event, GitHub event, Run workflow task or retry, every param whose value differs from its revision default (`:82`, `:562`). Such a param is also kept as it arrived at run start (`:168`). On a manual, scheduled or API-submitted run every value resolves |
| In a spec field, a task's own param, read as `$(params.x)` | The spec is filled in at claim against the params frozen at admission (`:299`), so `script: echo $(params.p)` with `p = $(tasks.t.results.r)` receives the result. A resolved value is final, so a reference inside it - a result's text, say - stays text |

A task that defines `x` itself resolves its own `$(params.x)` even when the run's `x` was supplied. A non-string
replacement interpolated **into** a larger string is written as JSON (`{"k":"v"}`), as the dispatcher encodes it.

An `object`-typed param resolves to the referenced structure itself only when its value is **exactly one
reference** - `"$(params.config)"`, ignoring surrounding whitespace (`ParameterManager.isSingleReference`). Any
other `object` value keeps its shape: the string leaves and keys are substituted in place, so
`{"url": "$(params.host)/api", "retries": 3}` becomes `{"url": "https://example.com/api", "retries": 3}`, and a leaf
that is a whole reference to an object is written as JSON. If substitution fails - a value that resolves to itself,
say - the value is passed through unchanged and the failure is logged with its shape, never its content.

## Admission checks

Admission checks each Flow reference - `$(params.…)`, `$(<scope>.params.…)`, `$(tasks.<name>.results.…)` - in the task's
params, `script`, `command`, `arguments` and env values (`ParameterManager.java:148`); `$(date)` and the like are shell.

| Reference | Declared when | Declared, no value at run time | Not declared |
| --- | --- | --- | --- |
| `$(tasks.t.results.r)` | `t` is a task in the run's workflow revision and declares `r` - on its node for `script`, `custom` and `generic`, else on its catalogue revision (`TaskService.getSpecs`); a task declaring no results allows any `r` (`ParameterManager.java:746-766`) | `""` - skipped, not yet run, failed and continued, or `r` not emitted | `task 't' is not in this workflow` / `task 't' declares no result 'r'` |
| `$(params.x)`, `$(<scope>.params.x)` | A layer that reference reads holds `x`, with or without a value (`:812`) | `""` (`:933`) | `no such param 'x'` (`no such global param 'x'`, …) |
| `$(params.cfg.path)` | `cfg` is declared | `""` when the path matches nothing | `no such param 'cfg'` |

An undeclared reference fails the task before it is claimable - `failed`, reason `ParamUnresolved`, a message naming
task, field, reference and why: `Task 'deploy': script references $(params.registryTokn): no such param 'registryTokn'`
(`TaskExecutionService.java:204`). Values inserted as written are not scanned; a run's own params are not checked.

A task param on a reference cycle among the task's own params fails the same way, because `$(params.x)` reads the
task's own `x` first and the value can never resolve. `x: $(params.x)` fails with `refers to itself; use
$(workflow.params.x) for the workflow's param`; `x: $(params.y)` with `y: $(params.x)` fails both, with `params 'x'
and 'y' reference each other in a cycle` (`ParameterManager.java:173`, `:636`).

## Names

Names MUST match `^[a-zA-Z_][a-zA-Z0-9_-]*$`, and any variant of `names` is reserved because it would fold
to `PARAM_NAMES` (`service-core/.../ParameterUtil.java:83-89`). `item` and `index` are reserved on a for-each task:
the engine adds both to each item, so a workflow whose for-each task's template declares either is refused on save
with `WORKFLOW_INVALID_TASK_FOREACH` (`workflow/WorkflowService.java:1723-1760`). Matching is case-insensitive everywhere:
`$(params.myparam)` resolves a param declared `MyParam` (`ParameterManager.java:864`), and the node-value
merge keeps the declared casing (`ParameterUtil.java:57-65`). An empty or absent value is valid and survives save unchanged — emptiness can be meaningful, and a
substitution can resolve to empty; a task that requires a value fails its own run with a message naming the
parameter (`engine/TaskExecutionService.java`, `runWorkflow`). The safety pair is rejection at save: names that
are case or separator variants of each other (`my-key`, `MY_KEY`) fail with `PARAM_NAME_COLLISION` (code 1209,
`BoomerangError.java:50`) at workflow save (`workflow/WorkflowService.java:1477-1483`) and task save
(`workflow/TaskService.java:366-374`); the dispatcher repeats the check at dispatch (`KubeHelperService.java:123-132`).

## Sensitive parameters

A secret is a parameter type: `ParamType.secret` beside `string`, `array` and `object`
(`service-core/.../enums/ParamType.java:15`), carried on every `RunParam` on the wire
(`common/model/RunParam.java:17`; absent means `string`). A secret is a string only. Values are filtered
on the way up only; the engine, the claim and the container see the real value.

| How a param becomes `secret` | Where |
| --- | --- |
| Declared with the `password` field type on a workflow or catalogue task (the field keeps its name) | `ParameterUtil.getRunParamType` (`ParameterUtil.java:53`), used when the run and each TaskRun are built |
| Sent as `type: secret` on a run request; this raises a declared type and nothing lowers it | `ParameterUtil.addUniqueParam` (`:70`) |
| A workflow node or schedule value whose declared param is `password` - the task template's, or the workflow's latest revision when the schedule is saved - is stamped at save | `ParameterUtil.stampSecretParams` (`:65`), from `WorkflowService` and `schedule/ScheduleService.java:249` |
| A `string` param whose resolution substitutes in a secret's value (taint) | `ParameterManager.resolveParam` (`engine/ParameterManager.java:202`) |

Taint follows the same layer precedence as the values (`ParameterManager.secretReferenceKeys`, `:452`): a
reference is a secret when the workflow or task layer that wins `params.<name>` holds a secret, so
`dsn = "postgres://app:$(params.dbPassword)@db"` becomes a secret with the resolved value. Arrays and
objects keep their type when a secret is substituted into them - a secret is a string only - and rely on
the value scrub below. The global and workspace layers are read from their typed stores
(`ParamLayerService.buildParamLayers`), so a password-typed global or workspace parameter is a secret too:
`$(global.params.registryToken)`, `$(workspace.params.dbPass)` and the flat `$(params.<name>)` that one of
them wins all taint. At submit, a secret whose value is not a string is rejected with
`PARAM_SECRET_NOT_STRING` (1217, `WorkflowService.rejectNonStringSecrets`), and an unknown `type` with
`PARAM_INVALID_TYPE` (1216, `core/RestExceptionHandler.java:101`).

Every layer that holds a secret - global and workspace parameters, a workflow's param defaults and node
values, a catalogue task's defaults, a schedule's values, secured settings, and a run's params - reads and
writes it by one rule (decision 0122). On the way out a set secret reads as `*****` and an unset one as
nothing (`DataAdapterUtil.maskSecretValue`, `common/util/DataAdapterUtil.java:173`); on a definition, a value
that is exactly one Flow reference - `$(workflow.params.aiToken)`, nothing around it - holds no secret and
reads as written, keeping its secret type so the value it resolves to is masked on every run read
(`ParameterUtil.isSingleFlowReference`, `ParameterUtil.java:52`; decision 0121). A run holds resolved values, so
its reads mask every set secret (`filterRunParamValueByFieldType` `:229`). Masks are copies, because a model
built with `BeanUtils.copyProperties` shares its lists with the entity. On the way in:

| A client sends | Means | Where it is decided |
| --- | --- | --- |
| `*****`, `null`, or no value | Keep the stored secret; with nothing stored, no value at all (never the marker) | `DataAdapterUtil.keepSecretValue` (`:132`), called by `keepParamSpecValueByFieldType` (`:143`) and `keepRunParamValueByFieldType` (`:289`) on every layer, and for the marker by `SettingsService.patchGroup` (`core/SettingsService.java:101`) and `ParameterService.patch` (`workflow/ParameterService.java:73`) |
| `""` | Clear it | the same |
| `null` on a JSON Merge Patch (settings, global parameters) | Clear it: on a merge patch a null member removes the value, so only `*****` or leaving the member out keeps it | `SettingsService.patchGroup`, `ParameterService.patch` (decision 0123) |
| Any other value | Replace it | the same |

A write that changes a secret names it in the audit trail and never records its value, a hash of it or its
length: the event's `payload.detail` lists the secrets the write set or replaced under `secretsChanged` and the
ones it cleared under `secretsCleared`, by name - a parameter's or task default's own name, `params.<name>` and
`tasks.<node>.<param>` on a workflow, `<group>.<key>` on settings. A kept secret is not listed, and a failed
write lists none (`core/audit/SecretChangeHolder.java:63`, collected around each audited call by
`AuditAspect.java:62-74`).

A stored value is matched by param name in any case, a node's by node name and then param name, and a
setting's by key; a workspace PATCH replaces a case variant rather than adding a second entry. A node secret
left with no value is dropped from the node so the task's default applies
(`workflow/WorkflowService.java:1643`). A run request - submit, start, retry - follows the same rule against the
declared defaults or the stored run: a param sent with no value, or as `*****` for a secret, leaves the value in
place and `""` sets it empty (`ParameterUtil.suppliedParams`, `ParameterUtil.java:215`, called at
`WorkflowService.java:1955` and `WorkflowRunService.java:940,1032`); the engine's own merge of node values over
task specs is separate and unchanged.

| Read | Filter |
| --- | --- |
| Workspace-scoped run `get` and `query` | Type, name join and value scrub (`workflow/WorkflowRunService.java:183-207`) |
| Submit, start, cancel, pause, resume and retry responses | Type (`WorkflowService.java:543`, `WorkflowRunService.java:213`); start and retry requests reject a non-string secret with 1217 like submit |
| `full` status events | Type, then a value scrub of the run's own secret values from results, status message, annotations and spec (`event/model/RunStatusSummary.java:53,63`) |
| Task log stream; manual action instructions | Value scrub (`WorkflowRunService.java:445-480`; `ActionService.java:266`, which keeps the instructions' text and hides only the secret) |
| Global and workspace parameters | The read shape, with `hiddenValue: true` on every `password` param (`workflow/ParameterService.java:171`, `workspace/WorkspaceService.java:793-794`) |
| Catalogue task `get`, `query`, create and apply responses (global and workspace routes) | The read shape (`workflow/TaskService.java:210`). The Tekton YAML read carries each param's default and description under `spec.params`, a `password` default as `*****`, which a YAML apply keeps (`workflow/tekton/TektonConverter.java:33-44`). `get(ref)`, `getSpecs` and `retrieveAndValidateTask`, which the engine and workflow save use, are not filtered |
| Workflow `get`, `query`, export, create, apply and duplicate responses, and the canvas (`composeGet`, the `composeApply` response) the editor and the run page load | The read shape on `password` defaults and on node secret values, the node's type stamped from the task spec on read for revisions saved before stamping (`WorkflowService.java:283`, canvas `:821`, `:840`). Duplicate copies the unfiltered workflow |
| Schedule `get`, `query`, create and apply responses | The read shape (`schedule/ScheduleService.java:252`); `internalGet`, which fires the run, is not filtered |
| Admin settings | The read shape on a `secured` entry (`core/SettingsService.java:149`) |

The name join and value scrub remain for runs stored before params carried a type and for secrets that
land in free text. The join takes the definitions: the workflow revision's param spec and each
catalogue task's own spec (reached through `TaskRun.taskRef` + `taskVersion`, resolved in one batch by
`TaskService.getSpecs`); each blanks its own password-typed params by name. The **union** of those values
and of every secret-typed param's value is scrubbed run-wide - from results and from every task's params,
spec fields (script, command, arguments, envs) and results, because substitution moves a value into any of
them under another name (`DataAdapterUtil.java:298-420`).

No value of a secret reaches a log, at any level. Log lines name params, tasks and Kubernetes objects
and never print a value (`ParameterUtil.runParamNames`, `ParameterUtil.java:108`; the executors log
`Created Job|TaskRun <name> in <namespace> (image ...)`). Behind them, `toString` prints a secret
`RunParam` and a `password` field's default and value as `*****`, an env var by name only, and leaves a
run spec's script, command and arguments out (`RunParam.java:36`, `AbstractParam.java:50`,
`TaskEnvVar.java:24`, `TaskRunSpec.java:18-22`). Boomerang loggers default to INFO; DEBUG is opt-in.

The task log stream is wrapped in `FilterValuesOutputStream`, a line-buffered scrub of the same union over
every task of the owning run (`service-core/.../FilterValuesOutputStream.java:21`). The dispatcher ends the
stream when the pod finishes (`kube/KubeLogService.java:24`); its asynchronous completion skips a second
authorization (`core/security/SecurityConfiguration.java:94-95`). A resolved value shorter than four characters
is blanked by name but not value-scrubbed, which would mangle unrelated text (decision 0043).

The union also holds the current values of the run workspace's password-typed **global and workspace**
parameters (`workflow/ParamLayerService.java:173`, read uncached once per workspace per response;
`WorkflowRunService.java:190,452`), which no definition declares yet substitution writes into task params,
scripts and results. A run that used a value since rotated keeps showing it.

Downward, the dispatcher reads `type` (`dispatcher-sdk/.../model/RunParam.java`, `contracts/dispatcher-v1.yaml`)
and builds `PARAM_<NAME>` for a secret exactly as for a string. There is no per-task Kubernetes Secret yet;
see decision 0115.

Secret values are encrypted at rest and plain in memory (decision 0116). `core/config/SecretValueMongoListener.java`
works on the BSON document: it encrypts on save (`:59`) and decrypts on load before the document is mapped
(`:66`), so every read - repository, template, find-and-modify, aggregation - hands out plaintext, the masking
above works unchanged, and nothing in Flow sees ciphertext. The two admission writes set params through a Mongo
update, which never reaches the save hook, so they seal the values themselves (`sealRunParams` `:76`;
`TaskRunService.java:329`, `WorkflowRunStateHelper.java:164`). The cipher is `core/SecretCipher.java`, shared with
secured settings: AES-256-GCM under `flow.encrypt.secret`/`flow.encrypt.salt`, written as
`crypt_v1{AESGCM|<ciphertext>}`. Only that whole label counts as encrypted (`isEncrypted`, `:38`): a value that
merely starts with `crypt_v1` is plaintext and is encrypted, a labelled value is never encrypted twice, and an
encryption failure throws rather than storing nothing (`:50`). An unlabelled value from an earlier release is
encrypted on its next save, and one that will not decrypt is kept as stored - by the listener on load, and by
settings on the next save of its group (`SettingsService.java:161`); fields: `data-model.md`, "Secret values at
rest". A node's value for a `password` task param is stamped `type: secret` at workflow save.
