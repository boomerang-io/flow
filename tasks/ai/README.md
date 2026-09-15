# `flow-task-ai` — the worker image behind the `ai` task type

An `ai` task runs one prompt against an OpenAI-compatible endpoint and returns the completion as a
task result. Its author never builds a container and never names an image: the dispatcher resolves
`flow.dispatcher.ai.image` and the `prompt` command for every TaskRun of type `ai`
(`service-dispatcher/src/main/java/io/boomerang/executor/TaskImageResolver.java`). The image is
built from this directory and shipped on the product tag alongside the four service images, so
`boomerangio/flow-task-ai:5.1.0` always matches the service-core and service-dispatcher of that
release.

Everything else is the ordinary task contract (`specifications/task-runtime.md`): params arrive as
`PARAM_<NAME>` environment variables and results are written to `RESULTS_PATH`, so the image runs
unchanged on the Tekton and Kubernetes Jobs executors.

## Params in

A param named `maxTokens` reaches the container as `PARAM_MAXTOKENS` — the name upper-cased with
any character outside `[A-Za-z0-9_]` replaced by `_` (`ParameterUtil.envFold`), so camel case is
simply flattened.

| Param | Environment variable | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `endpoint` | `PARAM_ENDPOINT` | yes | — | OpenAI-compatible base URL; `/chat/completions` is appended (a trailing `/` is trimmed) |
| `token` | `PARAM_TOKEN` | yes | — | Bearer token. Declare it `password`-typed so it is filtered out of run reads and log streams (decision 0043) |
| `model` | `PARAM_MODEL` | yes | — | Model id to request |
| `prompt` | `PARAM_PROMPT` | yes | — | The user message |
| `systemPrompt` | `PARAM_SYSTEMPROMPT` | no | none | Prepended as a `system` message when non-empty |
| `temperature` | `PARAM_TEMPERATURE` | no | `0.7` | Number |
| `maxTokens` | `PARAM_MAXTOKENS` | no | `1024` | Integer, sent as `max_tokens` |
| `responseFormat` | `PARAM_RESPONSEFORMAT` | no | `text` | `json` sends `response_format: {"type": "json_object"}` and fails the task if the completion is not JSON |
| `seed` | `PARAM_SEED` | no | none | Integer, for reproducible sampling where the provider supports it |
| `files` | `PARAM_FILES` | no | none | Comma-separated paths on the run workspace; each is appended to the user message as a fenced block under a `## <path>` heading |
| `maxContextBytes` | `PARAM_MAXCONTEXTBYTES` | no | `65536` | Byte budget for the file context; files that would exceed it are skipped and named in the prompt so the model knows the context is partial |

An unreadable path in `files` fails the task rather than quietly sending less context.

## Results out

| Result | Source |
| --- | --- |
| `output` | `choices[0].message.content` |
| `promptTokens` | `usage.prompt_tokens` |
| `completionTokens` | `usage.completion_tokens` |
| `totalTokens` | `usage.total_tokens` |
| `finishReason` | `choices[0].finish_reason` |
| `model` | `model` — the model as served, which may be more specific than the one asked for |

`RESULTS_PATH` is a directory on Tekton (`/tekton/results`, one file per result) and a single JSON
file on Kubernetes Jobs (`/dev/termination-log`); both are handled. On the file channel the engine
caps results at `flow.engine.task.results.max-bytes` (4096, decision 0041), and a long completion
will exceed it — the command warns on stderr rather than truncating the answer. For long output,
lower `maxTokens`, or have the model write to a workspace file and return the path.

## Failure

Any failure exits non-zero with the reason on stderr, and the engine ends the task `failed`. The
reasons are prefixed so they are greppable: `MISSING_PARAM`, `INVALID_PARAM`, `FILE_NOT_READABLE`,
`REQUEST_FAILED`, `REQUEST_REJECTED`, `INVALID_RESPONSE`, `NO_COMPLETION`.

`429`, `5xx` and transport failures are retried three times with a 2s then 4s backoff. Any other
`4xx` fails at once — a rejected token or an unknown model will be rejected again. The token is
never printed, and it is passed to curl through a `0600` config file rather than on the command
line, so it stays out of `/proc/*/cmdline` as well as out of the logs.

## Network zone

The endpoint is usually the only thing this container needs to reach. A deployment that wants AI
traffic confined registers a dispatcher for the `ai` type alone
(`flow.dispatcher.task-types=ai`) in a namespace whose egress policy allows the endpoint and
nothing else — see `service-dispatcher/README.md`.

## Tests

```bash
bash tasks/ai/test/run.sh
```

Runs `commands/prompt.sh` against `test/mock_openai.py`, a scripted stand-in for the endpoint, and
asserts the request body, the six results on both results channels, the file-context truncation,
the retry and failure paths, and that the token never appears in the output. Needs `bash`, `curl`,
`jq` and `python3` — no image build, no network.
