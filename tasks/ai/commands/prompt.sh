#!/bin/bash
#
# One chat completion against an OpenAI-compatible endpoint.
#
# Inputs arrive as PARAM_<NAME> environment variables, exactly as they do for every Flow task type
# (decision 0040); results are written to RESULTS_PATH, which is a directory on Tekton and a single
# JSON file on Kubernetes Jobs. The token is never echoed, never logged and never placed on the
# curl command line (it goes in a 0600 config file, so it stays out of /proc/*/cmdline too).
#
# Kept to bash 3.2 features so tasks/ai/test/run.sh runs on a developer's macOS shell as well as in
# the image.
set -euo pipefail

readonly DEFAULT_TEMPERATURE=0.7
readonly DEFAULT_MAX_TOKENS=1024
readonly DEFAULT_RESPONSE_FORMAT=text
readonly DEFAULT_MAX_CONTEXT_BYTES=65536
readonly MAX_ATTEMPTS=3
# The portable termination-message ceiling the engine enforces (flow.engine.task.results.max-bytes,
# decision 0041). Warn rather than truncate: silently cutting a model's answer is worse than an
# engine-side RESULTS_TOO_LARGE that names the cap.
readonly RESULTS_FILE_WARN_BYTES=4096

WORK_DIR=""

fail() {
  echo "ai/prompt: $*" >&2
  exit 1
}

log() {
  echo "ai/prompt: $*"
}

cleanup() {
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

require() {
  local name="$1"
  local value="$2"
  if [ -z "$value" ]; then
    fail "MISSING_PARAM - the '$name' param is required"
  fi
}

is_number() {
  case "$1" in
    '' | *[!0-9.+-]*) return 1 ;;
  esac
  # Reject the strings that pass the character test but are not numbers (".", "-", "1.2.3").
  printf '%s' "$1" | grep -Eq '^-?([0-9]+|[0-9]*\.[0-9]+)$'
}

is_integer() {
  printf '%s' "$1" | grep -Eq '^-?[0-9]+$'
}

trim() {
  printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

byte_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

#
# Inputs
#
ENDPOINT="${PARAM_ENDPOINT:-}"
TOKEN="${PARAM_TOKEN:-}"
MODEL="${PARAM_MODEL:-}"
SYSTEM_PROMPT="${PARAM_SYSTEMPROMPT:-}"
PROMPT="${PARAM_PROMPT:-}"
TEMPERATURE="${PARAM_TEMPERATURE:-$DEFAULT_TEMPERATURE}"
MAX_TOKENS="${PARAM_MAXTOKENS:-$DEFAULT_MAX_TOKENS}"
RESPONSE_FORMAT="${PARAM_RESPONSEFORMAT:-$DEFAULT_RESPONSE_FORMAT}"
SEED="${PARAM_SEED:-}"
FILES="${PARAM_FILES:-}"
MAX_CONTEXT_BYTES="${PARAM_MAXCONTEXTBYTES:-$DEFAULT_MAX_CONTEXT_BYTES}"
RESULTS_PATH="${RESULTS_PATH:-/tekton/results}"

require endpoint "$ENDPOINT"
require token "$TOKEN"
require model "$MODEL"
require prompt "$PROMPT"

is_number "$TEMPERATURE" || fail "INVALID_PARAM - temperature must be a number, got '$TEMPERATURE'"
is_integer "$MAX_TOKENS" || fail "INVALID_PARAM - maxTokens must be an integer, got '$MAX_TOKENS'"
is_integer "$MAX_CONTEXT_BYTES" \
  || fail "INVALID_PARAM - maxContextBytes must be an integer, got '$MAX_CONTEXT_BYTES'"
case "$RESPONSE_FORMAT" in
  text | json) ;;
  *) fail "INVALID_PARAM - responseFormat must be 'text' or 'json', got '$RESPONSE_FORMAT'" ;;
esac
if [ -n "$SEED" ] && ! is_integer "$SEED"; then
  fail "INVALID_PARAM - seed must be an integer, got '$SEED'"
fi

WORK_DIR="$(mktemp -d)"
readonly BODY_FILE="$WORK_DIR/request.json"
readonly RESPONSE_FILE="$WORK_DIR/response.json"
readonly CURL_CONFIG="$WORK_DIR/curl.cfg"
readonly USER_CONTENT_FILE="$WORK_DIR/user.txt"

#
# File context: each readable path is appended to the user message as a fenced block under its own
# heading, until the byte budget is spent. What did not fit is named in the prompt, so the model is
# told the context is partial rather than silently working from half of it.
#
printf '%s' "$PROMPT" > "$USER_CONTENT_FILE"

if [ -n "$FILES" ]; then
  context_used=0
  omitted=""
  # bash 3.2 has no readarray; split on commas with the field separator instead.
  old_ifs="$IFS"
  set -f          # a path list must not be glob-expanded on the way in
  IFS=','
  set -- $FILES
  IFS="$old_ifs"
  set +f
  for raw_path in "$@"; do
    file_path="$(trim "$raw_path")"
    [ -z "$file_path" ] && continue
    if [ ! -f "$file_path" ] || [ ! -r "$file_path" ]; then
      fail "FILE_NOT_READABLE - the 'files' param names '$file_path', which is not a readable file on the workspace"
    fi
    file_bytes="$(byte_size "$file_path")"
    if [ $((context_used + file_bytes)) -gt "$MAX_CONTEXT_BYTES" ]; then
      omitted="${omitted:+$omitted, }$file_path"
      continue
    fi
    context_used=$((context_used + file_bytes))
    {
      printf '\n\n## %s\n\n```\n' "$file_path"
      cat "$file_path"
      printf '\n```\n'
    } >> "$USER_CONTENT_FILE"
  done
  if [ -n "$omitted" ]; then
    printf '\n\nNote: the file context above is truncated at %s bytes. These files were omitted: %s\n' \
      "$MAX_CONTEXT_BYTES" "$omitted" >> "$USER_CONTENT_FILE"
    log "file context truncated at $MAX_CONTEXT_BYTES bytes; omitted: $omitted"
  fi
  log "appended $context_used bytes of file context"
fi

#
# Request body
#
jq -n \
  --arg model "$MODEL" \
  --arg system "$SYSTEM_PROMPT" \
  --rawfile user "$USER_CONTENT_FILE" \
  --argjson temperature "$TEMPERATURE" \
  --argjson maxTokens "$MAX_TOKENS" \
  --arg responseFormat "$RESPONSE_FORMAT" \
  --arg seed "$SEED" \
  '{
     model: $model,
     messages:
       ((if $system == "" then [] else [{role: "system", content: $system}] end)
        + [{role: "user", content: $user}]),
     temperature: $temperature,
     max_tokens: $maxTokens
   }
   + (if $responseFormat == "json" then {response_format: {type: "json_object"}} else {} end)
   + (if $seed == "" then {} else {seed: ($seed | tonumber)} end)' > "$BODY_FILE"

# The Authorization header goes through a config file rather than -H so the token never appears in
# the process command line. curl's config parser takes a double-quoted string with backslash
# escapes, so escape both.
escaped_token="$(printf '%s' "$TOKEN" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
printf 'header = "Authorization: Bearer %s"\n' "$escaped_token" > "$CURL_CONFIG"
chmod 600 "$CURL_CONFIG"

URL="${ENDPOINT%/}/chat/completions"
log "POST $URL (model=$MODEL, responseFormat=$RESPONSE_FORMAT, maxTokens=$MAX_TOKENS, requestBytes=$(byte_size "$BODY_FILE"))"

#
# Send, retrying only what is worth retrying: 429, 5xx and a transport failure. A 4xx is the
# request itself being wrong and will be wrong again, so it fails at once.
#
attempt=1
http_code=""
while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
  set +e
  http_code="$(curl -sS --config "$CURL_CONFIG" \
    -o "$RESPONSE_FILE" -w '%{http_code}' \
    -X POST "$URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    --data-binary "@$BODY_FILE" 2> "$WORK_DIR/curl.err")"
  curl_status=$?
  set -e

  if [ "$curl_status" -ne 0 ]; then
    http_code="000"
  fi

  case "$http_code" in
    2??)
      break
      ;;
    429 | 5??  | 000)
      if [ "$attempt" -eq "$MAX_ATTEMPTS" ]; then
        if [ "$http_code" = "000" ]; then
          fail "REQUEST_FAILED - could not reach $URL after $MAX_ATTEMPTS attempts: $(cat "$WORK_DIR/curl.err")"
        fi
        fail "REQUEST_FAILED - $URL returned HTTP $http_code on all $MAX_ATTEMPTS attempts: $(head -c 512 "$RESPONSE_FILE")"
      fi
      backoff=$((attempt * 2))
      log "HTTP $http_code from the endpoint; retrying in ${backoff}s (attempt $attempt of $MAX_ATTEMPTS)"
      sleep "$backoff"
      ;;
    *)
      # 4xx other than 429: a bad request, a rejected token, an unknown model. The response body
      # carries the provider's reason and never carries the token.
      fail "REQUEST_REJECTED - $URL returned HTTP $http_code: $(head -c 512 "$RESPONSE_FILE")"
      ;;
  esac
  attempt=$((attempt + 1))
done

#
# Response
#
if ! jq -e . "$RESPONSE_FILE" > /dev/null 2>&1; then
  fail "INVALID_RESPONSE - $URL returned HTTP $http_code with a body that is not JSON: $(head -c 512 "$RESPONSE_FILE")"
fi
if ! jq -e '.choices[0].message.content != null' "$RESPONSE_FILE" > /dev/null 2>&1; then
  fail "NO_COMPLETION - the response carried no choices[0].message.content: $(head -c 512 "$RESPONSE_FILE")"
fi

OUTPUT="$(jq -r '.choices[0].message.content' "$RESPONSE_FILE")"
FINISH_REASON="$(jq -r '.choices[0].finish_reason // ""' "$RESPONSE_FILE")"
SERVED_MODEL="$(jq -r '.model // ""' "$RESPONSE_FILE")"
PROMPT_TOKENS="$(jq -r '.usage.prompt_tokens // 0' "$RESPONSE_FILE")"
COMPLETION_TOKENS="$(jq -r '.usage.completion_tokens // 0' "$RESPONSE_FILE")"
TOTAL_TOKENS="$(jq -r '.usage.total_tokens // 0' "$RESPONSE_FILE")"

if [ "$RESPONSE_FORMAT" = "json" ] && ! printf '%s' "$OUTPUT" | jq -e . > /dev/null 2>&1; then
  fail "INVALID_RESPONSE - responseFormat=json but the model returned content that is not JSON"
fi

#
# Results. RESULTS_PATH is a directory of one file per result on Tekton and a single JSON object on
# Kubernetes Jobs (the container termination message); both executors are handled here so the same
# image is portable between them (decision 0046).
#
write_result_file() {
  printf '%s' "$2" > "$RESULTS_PATH/$1"
}

if [ -d "$RESULTS_PATH" ]; then
  write_result_file output "$OUTPUT"
  write_result_file promptTokens "$PROMPT_TOKENS"
  write_result_file completionTokens "$COMPLETION_TOKENS"
  write_result_file totalTokens "$TOTAL_TOKENS"
  write_result_file finishReason "$FINISH_REASON"
  write_result_file model "$SERVED_MODEL"
else
  # -c: the file channel is the 4096-byte termination message, so every byte of indentation is a
  # byte of answer that does not fit.
  jq -cn \
    --arg output "$OUTPUT" \
    --arg promptTokens "$PROMPT_TOKENS" \
    --arg completionTokens "$COMPLETION_TOKENS" \
    --arg totalTokens "$TOTAL_TOKENS" \
    --arg finishReason "$FINISH_REASON" \
    --arg model "$SERVED_MODEL" \
    '{output: $output, promptTokens: $promptTokens, completionTokens: $completionTokens,
      totalTokens: $totalTokens, finishReason: $finishReason, model: $model}' > "$RESULTS_PATH"
  results_bytes="$(byte_size "$RESULTS_PATH")"
  if [ "$results_bytes" -gt "$RESULTS_FILE_WARN_BYTES" ]; then
    echo "ai/prompt: warning - results are $results_bytes bytes, above the $RESULTS_FILE_WARN_BYTES-byte cap; the engine will end this task RESULTS_TOO_LARGE. Lower maxTokens, or have the model write its answer to a workspace file and return the path." >&2
  fi
fi

log "completed: ${#OUTPUT} chars, $TOTAL_TOKENS tokens, finishReason=$FINISH_REASON, model=$SERVED_MODEL"
