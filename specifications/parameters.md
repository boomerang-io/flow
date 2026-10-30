# Parameters

How a parameter value reaches a run and a task: the layers a reference reads, when the engine resolves it, what
substitution does to the text, what admission checks, which names are allowed, and how a secret is typed,
hidden and stored. What the dispatcher then receives is in `dispatcher-contract.md` ("What a poll hands out").
Paths below are under `service-core/src/main/java/io/boomerang/` unless they start with a module name.

## Layers and references

The global, workspace and workflow-context values are not copied onto the run. `ParameterManager` reads them
from their stores each time it resolves (`engine/ParameterManager.java:534-571`, through
`workflow/ParamLayerService.java:140-180`): at run start for the run's params
(`engine/WorkflowExecutionService.java:70`), at each task's admission (`engine/TaskExecutionService.java:201`),
at its claim for the spec (`engine/ParameterManager.java:306`), and for a for-each task's items at fan-out
(`engine/TaskExecutionService.java:672`). It reads them for the run's workspace, which is the workspace that owns
the run's workflow in the relationship graph, by id, so a rename does not move it
(`workflow/ParamLayerService.java:91`). The workflow context (name, display name, id and version) comes from
the run's workflow and the revision it runs, so a version saved mid-run does not change it.

The layers and the revision are served from a per-instance cache for 10 seconds (`core/ParamLayerCache.java:41-49`,
`flow.parameters.layer-cache.ttl`; `0s` turns it off), so tasks admitted together share one read of each store.
A global or workspace parameter, setting, workflow or revision write clears the cache on the instance that
took it (`workflow/ParameterService.java:104`, `workspace/WorkspaceService.java:216`,
`core/SettingsService.java:166`, `workflow/WorkflowService.java:1393`); other instances see it within the cache
lifetime. A task admitted after an edit gets the new value; each task run keeps the param values it was
admitted with.

Parameter references, by where the value comes from (`engine/ParameterManager.java:814-843`,
`common/model/ParamLayers.java:121-134`):

| Reference | Value from |
| --- | --- |
| `$(params.x)` | The flattened layers, each overriding the one before: global, workspace, workflow, then context. In a task param's value that is all it reads, so `url: $(params.url)` passes the workflow's `url` through; in the task's spec (script, command, arguments, env) the task's own params come before context and after workflow, so the spec reads the task's `x` (`engine/ParameterManager.java:144-150,534-570`) |
| `$(workflow.params.x)` | The workflow run's parameters |
| `$(workspace.params.x)` | The workspace's parameters. `$(team.params.x)` resolves too, is deprecated, logs a warning once per reference (`engine/ParameterManager.java:936-944`) and is removed in the next major version; the editor suggests only `workspace` |
| `$(global.params.x)` | Global parameters |
| `$(context.params.x)` | Run context: `workflowrun-trigger`, `workflowrun-initiator`, `workflowrun-ref`, `workflowrun-status` and `workflowrun-status-reason` (the run's status as the task resolves: final for a finally task, `running` for any other), `workflow-name`, `workflow-displayname`, `workflow-ref`, `workflow-version`, `taskrun-ref`, `taskrun-name`, `taskrun-type`, `webhook-url`, `wfe-url`, `event-url` (`workflow/ParamLayerService.java:275-294`, `engine/ParameterManager.java:548-566`). No parameter carries a token: a task that needs a credential takes it from a `secret` parameter |
| `$(tasks.t.results.r)` | A result of task `t` |
| `$(<reference>.<path>)` | A JSON path into an `object` value, such as `$(params.cfg.path)` |

A layer is read only while its feature setting is on: `features.globalParameters` and
`features.workspaceParameters` (`workflow/ParamLayerService.java:161-170`).

## When a value resolves

The engine fills in references in two places, at two times. The task run's own parameter values resolve at
admission (`engine/ParameterManager.java:144-238`, `engine/TaskExecutionService.java:201`) and are frozen by
the admission compare-and-set with secret values encrypted (`engine/TaskRunService.java:362-377`).
`spec.script`, `spec.command`, `spec.arguments` and `spec.envs` are filled in when a dispatcher claims the task
(`ParameterManager.fillSpec`, `engine/ParameterManager.java:288-314`, called from
`dispatcher/DispatcherService.java:451-458`): the stored spec keeps its references and only the copy on the
claim carries values, so no secret value is ever written into a stored spec.

At claim, `$(params.x)` reads the task's params as frozen at admission, so a requeued task gets the same
values; `$(tasks.t.results.r)`, `$(workflow.params.x)` and `$(context.params.x)` read the run; and
`$(workspace.params.x)` and `$(global.params.x)` written straight into a spec field are read from their stores,
so an edit since admission, or across a requeue, shows. Admission has checked every reference (below), so the
fill-in fails only for a workspace or global parameter deleted since; the task then ends `failed` with reason
`SpecFillFailed` (`dispatcher/DispatcherService.java:460-469`). The task run's params are the task's declared
params merged with the values authored on the workflow node (`engine/DAGUtility.java:273-276`).

## Substitution

Substitution writes into string leaves directly, so a replacement's quotes, newlines, backslashes and `$` are
inserted verbatim: a multi-line prompt, a JSON body or a result with a trailing newline reaches the container
byte for byte (`ParameterManager.replaceStringInObject`, `engine/ParameterManager.java:972`). It also runs
over the values it inserts, so configuration composes (a workspace value containing `$(global.params.x)`
resolves), except for three kinds of value, which are inserted as written with their `$(` escaped
(`engine/ParameterManager.java:613-641,761-763`):

| Inserted as written | Why |
| --- | --- |
| A task result, `$(tasks.t.results.r)` | It is what a task produced, not what anyone in the workspace wrote |
| A supplied run param, read as `$(params.x)` or `$(workflow.params.x)` | On a run started by a webhook, event, GitHub event, Run workflow task or retry, every param whose value differs from its revision default (`engine/ParameterManager.java:80-86,579-602`). Such a param is also kept as it arrived at run start (`:164-166`). On a manual, scheduled or API-submitted run every value resolves |
| In a spec field, a task's own param, read as `$(params.x)` | The spec is filled in at claim against the params frozen at admission (`:307-310`), so `script: echo $(params.p)` with `p = $(tasks.t.results.r)` receives the result. A resolved value is final, so a reference inside it (a result's text, say) stays text |

A task that defines `x` itself, or a context key named `x`, resolves `$(params.x)` even when the run's `x` was
supplied. A non-string replacement interpolated into a larger string is written as JSON (`{"k":"v"}`), as the
dispatcher encodes it (`engine/ParameterManager.java:1052`).

An `object`-typed param resolves to the referenced structure itself only when its value is exactly one
reference, `"$(params.config)"`, ignoring surrounding whitespace (`common/util/ParameterUtil.java:35-45`). Any
other `object` value keeps its shape: the string leaves and keys are substituted in place, so
`{"url": "$(params.host)/api", "retries": 3}` becomes `{"url": "https://example.com/api", "retries": 3}`, and a
leaf that is a whole reference to an object is written as JSON. If substitution fails (a value that resolves to
itself, say) the value is passed through unchanged and the failure is logged with its shape, never its content
(`engine/ParameterManager.java:1078`).

## Admission checks

Admission checks each Flow reference (`$(params.…)`, `$(<scope>.params.…)`, `$(tasks.<name>.results.…)`) in
the task's params, `script`, `command`, `arguments` and env values (`engine/ParameterManager.java:144-238`);
`$(date)` and the like are shell and stay text (`:844-846`).

| Reference | Declared when | Declared, no value at run time | Not declared |
| --- | --- | --- | --- |
| `$(tasks.t.results.r)` | `t` is a task in the run's workflow revision and declares `r`: on its node for `script`, `custom` and `generic`, else on its catalogue revision (`TaskService.getSpecs`); a task declaring no results allows any `r`, and a result already emitted counts (`engine/ParameterManager.java:671-729`) | `""`: skipped, not yet run, failed and continued, or `r` not emitted | `task 't' is not in this workflow` / `task 't' declares no result 'r'` |
| `$(params.x)`, `$(<scope>.params.x)` | A layer that reference reads holds `x`, with or without a value (`:737-755`) | `""` (`:855-859`) | `no such param 'x'` (`no such global param 'x'`, …) |
| `$(params.cfg.path)` | `cfg` is declared | `""` when the path matches nothing | `no such param 'cfg'` |

An undeclared reference fails the task before it is claimable: `failed`, reason `ParamUnresolved`, with a
message naming task, field, reference and why, such as
`Task 'deploy': script references $(params.registryTokn): no such param 'registryTokn'`
(`engine/ParameterManager.java:252-260`, `engine/TaskExecutionService.java:200-208`). Values inserted as
written are not scanned; a run's own params are not checked.

A task's params never read each other: a task param's `$(params.y)` names the outer `y`, and with no outer `y` it is
reported like any undeclared reference. Only the spec reads the task's own params, as in v3, where a task's inputs
"are only able to be referenced within the Task template or by the Task code itself"; the secret taint follows the
same layers (`engine/ParameterManager.java:459-490`). Decision 0016 records why. A for-each item's `item` and
`index` are context the engine set when it fanned out, so an item's own params do read `$(params.item)` and
`$(params.index)` (`engine/ParameterManager.java:566-574`).

The editor warns when a saved version reads a workspace parameter the workspace does not provide, so such a run
fails here. It reads `$(workspace.params.x)` and `$(team.params.x)` anywhere in a node's values, and in its task's
defaults where the node leaves a param out (or leaves a `password` empty) and pins the task version the editor
holds; names match in any case, and a parameter with no value, or any while workspace parameters are off, counts
as missing. The save goes ahead (`client-web/src/Utils/workspaceParamReferences.ts:23-59`,
`client-web/src/Features/WorkflowEditor/Editor.tsx:186-190,207-216`).

## Names

Names MUST match `^[a-zA-Z_][a-zA-Z0-9_-]*$`, and any variant of `names` is reserved because it would fold to
`PARAM_NAMES` (`common/util/ParameterUtil.java:173-179`). `item` and `index` are reserved on a for-each task:
the engine adds both to each item, so a workflow whose for-each task's template declares either is refused on
save with `WORKFLOW_INVALID_TASK_FOREACH` (`workflow/WorkflowService.java:1686-1696`). Matching is
case-insensitive everywhere: `$(params.myparam)` resolves a param declared `MyParam`
(`engine/ParameterManager.java:789`), and the node-value merge keeps the declared casing
(`common/util/ParameterUtil.java:123-144`). An empty or absent value is valid and survives save unchanged:
emptiness can be meaningful, and a substitution can resolve to empty; a task that requires a value fails its
own run with a message naming the parameter. Names that are case or separator variants of each other
(`my-key`, `MY_KEY`) are rejected with `PARAM_NAME_COLLISION` (code 1209,
`common/error/BoomerangError.java:55`) at workflow save (`workflow/WorkflowService.java:1580-1588`) and task
save (`workflow/TaskService.java:362-372`); the dispatcher repeats the check at dispatch
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:142-155`).

## Parameters an integration manages

An integration whose template declares `fields` shares its credentials with a workspace's tasks as ordinary
workspace parameters, so a task reads them as `$(workspace.params.<name>)` and the engine never calls an
integration. GitHub declares `github-token`, the App's installation token, and `github-url`,
`https://api.github.com`; Slack declares `slack-token`, the Flow app's bot token for the linked Slack team
(`service-core/src/main/resources/seed/integration-templates.json`). The 17 GitHub catalogue tasks default `token`
and `url` to GitHub's, and the 10 Slack tasks that call the Slack API default `token` to Slack's; a value typed
on the node wins.

A template's `kind` says where the values come from. GitHub and Slack are `app`: Flow installs them and fills the
credential. Google (`google-service-account-key`, a service account's whole JSON key) and Microsoft
(`microsoft-tenant-id`, `microsoft-client-id`, `microsoft-client-secret`, an Entra app's) are `form`: the
workspace enters every field, and that is the link, with no install, record or relationship edge; the integration
is linked while the workspace holds its fields (`integrations/enums/IntegrationKind.java:3-10`,
`integrations/IntegrationService.java:86-96`).

| Rule | Where |
| --- | --- |
| Sharing is a separate choice after linking: the token carries the App's permissions on the installation for every workflow author in the workspace | `integrations/IntegrationService.java:130-187` |
| A Slack bot token does not expire, so it is shared as stored and never refreshed; an installation with Slack's token rotation on, whose token expires with a refresh token, is refused with `400 INTEGRATION_TOKEN_ROTATES` (1909) | `integrations/SlackService.java:686-703` |
| Sharing needs `features.workspaceParameters` on and never overwrites a parameter the user made; both are checked before a token is minted | `integrations/IntegrationParameterService.java:91-105,165-180` |
| A form integration's `values` are checked against its fields: an unknown name is `400 INTEGRATION_FIELD_UNKNOWN` (1911), a required field left without a value `400 INTEGRATION_FIELD_REQUIRED` (1910), and values sent to an `app` integration `400 INTEGRATION_VALUES_NOT_ACCEPTED` (1912). A secret sent as `*****`, or left out, keeps the stored one and any other field left out keeps its value, so sharing again edits them | `integrations/IntegrationParameterService.java:107-145`, `integrations/IntegrationService.java:137-187` |
| The share's audit event names each secret it set, replaced or cleared, never a value | `integrations/IntegrationParameterService.java:148-163,182-203` |
| A managed parameter is stored read-only, and only its integration writes or removes it: a workspace write or delete of it is `409 PARAMS_MANAGED_BY_INTEGRATION` (1804), and a client's `readOnly` is ignored, so a parameter keeps the read-only it was stored with | `workspace/WorkspaceService.java:528-537,555-565,589` |
| A read-only parameter no template declares, such as one carried from v3, is not managed | `integrations/IntegrationParameterService.java:67-81` |
| Unlinking or uninstalling removes the managed parameters; a user's stay. Disconnecting Slack removes one workspace's link and parameter; uninstalling the Slack app removes them from every workspace linking that team. A form integration is disconnected by sharing off | `integrations/GitHubService.java:412-416`, `integrations/IntegrationService.java:230-250`, `integrations/SlackService.java:651-684,961-981`, `integrations/IntegrationParameterService.java:147-163` |
| A GitHub installation token lives an hour. Every instance replaces each shared token every 45 minutes and within a minute of starting, with one targeted write that encrypts the value, touches no other parameter, raises no audit event and clears the layer cache | `integrations/GitHubService.java:191-228`, `integrations/IntegrationParameterService.java:222-243` |

A GitHub or Slack task left on its default in a workspace that shares nothing fails at admission with
`no such workspace param 'github-token'` or `'slack-token'`, and the editor warns when the workflow is saved
(Admission checks, above). The editor shows a `password` field holding
exactly one reference as text, so the default reads as written; anything typed over it is masked
(`client-web/src/Features/Reactflow/components/shared/inputs.tsx:137-146`).

## Sensitive parameters

A secret is a parameter type: `ParamType.secret` beside `string`, `array` and `object`
(`common/enums/ParamType.java:15`), carried on every `RunParam` on the wire (`common/model/RunParam.java:17-18`;
absent means `string`). A secret is a string only. Values are filtered on the way up only; the engine, the claim
and the container see the real value.

| How a param becomes `secret` | Where |
| --- | --- |
| Declared with the `password` field type on a workflow or catalogue task (the field keeps its name) | `ParameterUtil.getRunParamType` (`common/util/ParameterUtil.java:86-90`), used when the run (`workflow/WorkflowService.java:1867`) and each task run (`engine/DAGUtility.java:275`) are built |
| Sent as `type: secret` on a run request; this raises a declared type and nothing lowers it | `ParameterUtil.addUniqueParam` (`common/util/ParameterUtil.java:130-140`) |
| A workflow node or schedule value whose declared param is `password` (the task template's, or the workflow's latest revision when the schedule is saved) is stamped at save | `ParameterUtil.stampSecretParams` (`common/util/ParameterUtil.java:98-112`), from `workflow/WorkflowService.java:1479,1649-1651` and `schedule/ScheduleService.java:228-231` |
| A `string` param whose resolution substitutes in a secret's value (taint) | `ParameterManager.resolveParamLayers` (`engine/ParameterManager.java:187-192`) |

Taint follows the same layer precedence as the values (`ParameterManager.secretReferenceKeys`,
`engine/ParameterManager.java:459`): a reference is a secret when the layer that wins `params.<name>` holds a
secret, so `dsn = "postgres://app:$(params.dbPassword)@db"` becomes a secret with the resolved value. Arrays and
objects keep their type when a secret is substituted into them and rely on the value scrub below. The global
and workspace layers are read from their typed stores (`workflow/ParamLayerService.java:234-266`), so a
password-typed global or workspace parameter is a secret too: `$(global.params.registryToken)`,
`$(workspace.params.dbPass)` and the flat `$(params.<name>)` that one of them wins all taint. At submit, start
and retry, a secret whose value is not a string is rejected with `PARAM_SECRET_NOT_STRING` (1217,
`workflow/WorkflowService.java:1934-1944`), and an unknown `type` with `PARAM_INVALID_TYPE` (1216,
`core/RestExceptionHandler.java:101`).

Every layer that holds a secret (global and workspace parameters, a workflow's param defaults and node values,
a catalogue task's defaults, a schedule's values, secured settings, and a run's params) reads and writes it by
one rule. On the way out a set secret reads as `*****` and an unset one as nothing
(`DataAdapterUtil.maskSecretValue`, `common/util/DataAdapterUtil.java:173-178`). On a definition, a value that
is exactly one Flow reference (`$(workflow.params.aiToken)`, nothing around it) holds no secret and reads as
written, keeping its secret type so the value it resolves to is masked on every run read
(`common/util/ParameterUtil.java:52-63`). A run holds resolved values, so its reads mask every set secret
(`common/util/DataAdapterUtil.java:249-262`). Masks are copies, because a model built with
`BeanUtils.copyProperties` shares its lists with the entity. On the way in:

| A client sends | Means | Where it is decided |
| --- | --- | --- |
| `*****`, `null`, or no value | Keep the stored secret; with nothing stored, no value at all (never the marker) | `DataAdapterUtil.keepSecretValue` (`common/util/DataAdapterUtil.java:132-137`), called by `keepParamSpecValueByFieldType` (`:143`) and `keepRunParamValueByFieldType` (`:289`) on every layer, and for the marker by `SettingsService.patchGroup` (`core/SettingsService.java:101-122`) and `ParameterService.patch` (`workflow/ParameterService.java:73,110-115`) |
| `""` | Clear it | the same |
| `null` on a JSON Merge Patch (settings, global parameters) | Clear it: on a merge patch a null member removes the value, so only `*****` or leaving the member out keeps it | `SettingsService.patchGroup`, `ParameterService.patch` |
| Any other value | Replace it | the same |

A write that changes a secret names it in the audit trail and never records its value, a hash of it or its
length: the event's `payload.detail` lists the secrets the write set or replaced under `secretsChanged` and the
ones it cleared under `secretsCleared`, by name (a parameter's or task default's own name, `params.<name>` and
`tasks.<node>.<param>` on a workflow, `<group>.<key>` on settings). A kept secret is not listed, and a failed
write lists none (`core/audit/SecretChangeHolder.java:63`, collected around each audited call by
`core/audit/AuditAspect.java:62-74`).

A stored value is matched by param name in any case, a node's by node name and then param name, and a
setting's by key; a workspace PATCH replaces a case variant rather than adding a second entry. A node secret
left with no value is dropped from the node so the task's default applies
(`workflow/WorkflowService.java:1544-1547`). A run request (submit, start, retry) follows the same rule against
the declared defaults or the stored run: a param sent with no value, or as `*****` for a secret, leaves the
value in place and `""` sets it empty (`ParameterUtil.suppliedParams`, `common/util/ParameterUtil.java:215-232`,
called at `workflow/WorkflowService.java:1900` and `workflow/WorkflowRunService.java:832,1004`); the engine's own
merge of node values over task specs is separate.

| Read | Filter |
| --- | --- |
| Workspace-scoped run `get` and `query` | Name join, value scrub, then type (`workflow/WorkflowRunService.java:199-223`) |
| Submit, start, cancel, pause, resume and retry responses | Type (`workflow/WorkflowService.java:574`, `workflow/WorkflowRunService.java:229-232`) |
| `full` status events | A value scrub of the run's own secret values from results, status message, annotations and spec, then type (`event/model/RunStatusSummary.java:53-71`) |
| Task log stream; manual action instructions | Value scrub (`workflow/WorkflowRunService.java:533-586`; `workflow/ActionService.java:325-341`, which keeps the instructions' text and hides only the secret) |
| Task-run query rows | Nothing to filter: a row leaves out params, results, spec, annotations and status message (`workflow/WorkflowRunService.java:361-365`) |
| Insights task view | No param is read; a row groups on labels and sums the usage results of `ai` task runs |
| Server labels | Never written from a secret: `boomerang.io/model` is skipped when the `model` param is secret-typed, and `boomerang.io/item` when the item contains the value of any secret in the run's parameter layers (`engine/TaskExecutionService.java:228-238,747-755,1184-1188`, `engine/ParameterManager.java:345`) |
| Global and workspace parameters | The read shape, with `hiddenValue: true` on every `password` param (`workflow/ParameterService.java:178-183`, `workspace/WorkspaceService.java:820-821`) |
| Catalogue task `get`, `query`, create and apply responses (global and workspace routes) | The read shape (`workflow/TaskService.java:206-216`). The Tekton YAML read carries each param's default and description under `spec.params`, a `password` default as `*****`, which a YAML apply keeps (`workflow/tekton/TektonConverter.java:33-44`). `get(ref)`, `getSpecs` and `retrieveAndValidateTask`, which the engine and workflow save use, are not filtered |
| Workflow `get`, `query`, export, create, apply and duplicate responses, and the canvas (`composeGet`, the `composeApply` response) the editor and the run page load | The read shape on `password` defaults and on node secret values, the node's type stamped from the task spec on read for revisions saved before stamping (`workflow/WorkflowService.java:295-319`, canvas `:775`). Duplicate copies the unfiltered workflow (`:752-758`) |
| Schedule `get`, `query`, create and apply responses | The read shape (`schedule/ScheduleService.java:235-238`); `internalGet`, which fires the run, is not filtered |
| Admin settings | The read shape on a `secured` entry (`core/SettingsService.java:183-189`) |

The name join and value scrub cover runs stored before params carried a type, and secrets that land in free
text. The join takes the definitions: the workflow revision's param spec and each catalogue task's own spec
(reached through `TaskRun.taskRef` + `taskVersion`, resolved in one batch by `TaskService.getSpecs`,
`workflow/WorkflowRunService.java:247-272`); each blanks its own password-typed params by name. The union of
those values and of every secret-typed param's value is scrubbed run-wide, from results and from every task's
params, spec fields (script, command, arguments, envs) and results, because substitution moves a value into any
of them under another name (`common/util/DataAdapterUtil.java:337-475`).

The union also holds the current values of the run workspace's password-typed global and workspace
parameters (`workflow/ParamLayerService.java:201-214`, read uncached once per workflow per response,
`workflow/WorkflowRunService.java:593-597`), which no definition declares yet substitution writes into task
params, scripts and results. A run that used a value since rotated keeps showing it. A value shorter than four
characters is blanked by name but not value-scrubbed, which would mangle unrelated text
(`common/util/DataAdapterUtil.java:26,468`, `workflow/ParamLayerService.java:219-229`).

No value of a secret reaches a log, at any level. Log lines name params, tasks and Kubernetes objects and
never print a value (`ParameterUtil.runParamNames`, `common/util/ParameterUtil.java:161`; the executors log
`Created Job|TaskRun <name> in <namespace> (image ...)`). Behind them, `toString` prints a secret `RunParam` and
a `password` field's default and value as `*****`, an env var by name only, and leaves a run spec's script,
command and arguments out (`common/model/RunParam.java:36-38`, `common/model/AbstractParam.java:49-57`,
`common/model/TaskEnvVar.java:24`, `common/model/TaskRunSpec.java:18-22`). Boomerang loggers default to INFO;
DEBUG is opt-in (`service-core/src/main/resources/application.properties:165-176`).

The task log stream is wrapped in `FilterValuesOutputStream`, a line-buffered scrub of the same union over
every task of the owning run (`common/util/FilterValuesOutputStream.java:23`). The dispatcher ends the stream
when the pod finishes (`service-dispatcher/src/main/java/io/boomerang/kube/KubeLogService.java:25-27`); its
asynchronous completion skips a second authorization (`core/security/SecurityConfiguration.java:104-105`).

Downward, the dispatcher reads `type`
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/model/RunParam.java`, `contracts/dispatcher-v1.yaml`)
and builds `PARAM_<NAME>` for a secret exactly as for a string. There is no per-task Kubernetes Secret.

Secret values are encrypted at rest and plain in memory. `core/config/SecretValueMongoListener.java` works on
the BSON document: it encrypts on save (`:63-67`) and decrypts on load before the document is mapped
(`:70-74`), so every read (repository, template, find-and-modify, aggregation) hands out plaintext, the masking
above works unchanged, and nothing in Flow sees ciphertext. Three writes set params through a Mongo update,
which never reaches the save hook, so they seal the values themselves (`sealRunParams`, `:80-93`): the task run
and workflow run admissions (`engine/TaskRunService.java:375`, `engine/WorkflowRunStateHelper.java:167`) and the
params a dispatcher's start request adds (`engine/TaskRunService.java:964-966`). The cipher is
`core/SecretCipher.java`, shared with secured settings: AES-256-GCM under `flow.encrypt.secret` and
`flow.encrypt.salt`, written as `crypt_v1{AESGCM|<ciphertext>}`. Only that whole label counts as encrypted
(`isEncrypted`, `:38-43`): a value that merely starts with `crypt_v1` is plaintext and is encrypted, a labelled
value is never encrypted twice, and an encryption failure throws rather than storing nothing (`:50-60`). An
unlabelled value is encrypted on its next save, and one that will not decrypt is kept as stored, by the
listener on load (`core/config/SecretValueMongoListener.java:163-170`) and by settings on the next save of its
group (`core/SettingsService.java:175-178,205-210`). The fields are listed in `data-model.md`, "Secret values
at rest".
