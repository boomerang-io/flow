#!/bin/bash
#
# Unit test for tasks/ai/commands/prompt.sh, against a local mock endpoint (test/mock_openai.py).
# Asserts what the task actually promises: the request body it sends, the six results it writes on
# both results channels, the retry and failure behaviour, and that the token never leaks.
#
#   bash tasks/ai/test/run.sh
#
# Needs bash, curl, jq and python3 - the same tools the image carries, so it runs on a developer's
# machine and in CI without building the image.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
readonly PROMPT_SH="$HERE/../commands/prompt.sh"
readonly MOCK="$HERE/mock_openai.py"
readonly TOKEN="sk-test-do-not-log-me"

TMP="$(mktemp -d)"
MOCK_PID=""
PASSED=0
FAILED=0

stop_mock() {
  if [ -n "$MOCK_PID" ]; then
    { kill "$MOCK_PID"; wait "$MOCK_PID"; } 2> /dev/null
    MOCK_PID=""
  fi
}

cleanup() {
  stop_mock
  rm -rf "$TMP"
}
trap cleanup EXIT

pass() {
  PASSED=$((PASSED + 1))
  echo "  ok   - $1"
}

fail() {
  FAILED=$((FAILED + 1))
  echo "  FAIL - $1"
}

check() {
  # check <description> <actual> <expected>
  if [ "$2" = "$3" ]; then
    pass "$1"
  else
    fail "$1"
    echo "         expected: $3"
    echo "         actual:   $2"
  fi
}

check_contains() {
  if printf '%s' "$2" | grep -Fq -- "$3"; then
    pass "$1"
  else
    fail "$1 (expected to contain '$3', got: $2)"
  fi
}

check_not_contains() {
  if printf '%s' "$2" | grep -Fq -- "$3"; then
    fail "$1 (expected NOT to contain '$3')"
  else
    pass "$1"
  fi
}

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}

# start_mock <responses-json-literal> -> sets ENDPOINT and REQUESTS
start_mock() {
  stop_mock
  local port
  port="$(free_port)"
  REQUESTS="$TMP/requests-$port.jsonl"
  : > "$REQUESTS"
  printf '%s' "$1" > "$TMP/responses-$port.json"
  python3 "$MOCK" "$port" "$REQUESTS" "$TMP/responses-$port.json" &
  MOCK_PID=$!
  ENDPOINT="http://127.0.0.1:$port/v1"
  local waited=0
  until curl -s -o /dev/null "http://127.0.0.1:$port/" 2> /dev/null || [ "$waited" -ge 50 ]; do
    sleep 0.1
    waited=$((waited + 1))
  done
}

completion_response() {
  cat <<'JSON'
[{"status": 200, "body": {
  "id": "chatcmpl-1",
  "model": "gpt-4o-mini-2024-07-18",
  "choices": [{"index": 0, "finish_reason": "stop",
               "message": {"role": "assistant", "content": "The run succeeded in 12 seconds."}}],
  "usage": {"prompt_tokens": 41, "completion_tokens": 9, "total_tokens": 50}
}}]
JSON
}

run_prompt() {
  # run_prompt <results-path> [extra env assignments...] ; sets RC, STDOUT_TXT, STDERR_TXT
  local results="$1"
  shift
  local out="$TMP/stdout.txt"
  local err="$TMP/stderr.txt"
  env -i \
    PATH="$PATH" \
    HOME="$HOME" \
    RESULTS_PATH="$results" \
    PARAM_ENDPOINT="$ENDPOINT" \
    PARAM_TOKEN="$TOKEN" \
    PARAM_MODEL="gpt-4o-mini" \
    PARAM_PROMPT="How did the run go?" \
    "$@" \
    bash "$PROMPT_SH" > "$out" 2> "$err"
  RC=$?
  STDOUT_TXT="$(cat "$out")"
  STDERR_TXT="$(cat "$err")"
}

echo "tasks/ai - prompt command"

#
# 1. The happy path on the Tekton results channel (RESULTS_PATH is a directory).
#
echo
echo "a directory RESULTS_PATH (Tekton) gets one file per result"
start_mock "$(completion_response)"
RESULTS_DIR="$TMP/results"
mkdir -p "$RESULTS_DIR"
run_prompt "$RESULTS_DIR"

check "exits zero" "$RC" "0"
check "output" "$(cat "$RESULTS_DIR/output")" "The run succeeded in 12 seconds."
check "promptTokens" "$(cat "$RESULTS_DIR/promptTokens")" "41"
check "completionTokens" "$(cat "$RESULTS_DIR/completionTokens")" "9"
check "totalTokens" "$(cat "$RESULTS_DIR/totalTokens")" "50"
check "finishReason" "$(cat "$RESULTS_DIR/finishReason")" "stop"
check "model is the model as served, not as asked for" \
  "$(cat "$RESULTS_DIR/model")" "gpt-4o-mini-2024-07-18"

REQ="$(head -1 "$REQUESTS")"
check "posts to <endpoint>/chat/completions" \
  "$(printf '%s' "$REQ" | jq -r '.path')" "/v1/chat/completions"
check "sends the bearer token" \
  "$(printf '%s' "$REQ" | jq -r '.authorization')" "Bearer $TOKEN"
check "sends JSON" "$(printf '%s' "$REQ" | jq -r '.contentType')" "application/json"
check "body model" "$(printf '%s' "$REQ" | jq -r '.body.model')" "gpt-4o-mini"
check "body has one user message and no system message" \
  "$(printf '%s' "$REQ" | jq -r '[.body.messages[].role] | join(",")')" "user"
check "body user content is the prompt" \
  "$(printf '%s' "$REQ" | jq -r '.body.messages[0].content')" "How did the run go?"
check "body temperature defaults to 0.7" \
  "$(printf '%s' "$REQ" | jq -r '.body.temperature')" "0.7"
check "body max_tokens defaults to 1024" \
  "$(printf '%s' "$REQ" | jq -r '.body.max_tokens')" "1024"
check "no response_format by default" \
  "$(printf '%s' "$REQ" | jq -r '.body.response_format // "absent"')" "absent"
check "no seed by default" "$(printf '%s' "$REQ" | jq -r '.body.seed // "absent"')" "absent"
check_not_contains "the token is not echoed on stdout" "$STDOUT_TXT" "$TOKEN"

#
# 2. The Kubernetes Jobs results channel (RESULTS_PATH is a single JSON file).
#
echo
echo "a file RESULTS_PATH (Kubernetes Jobs) gets one JSON object"
start_mock "$(completion_response)"
RESULTS_FILE="$TMP/termination-log"
rm -f "$RESULTS_FILE"
run_prompt "$RESULTS_FILE"

check "exits zero" "$RC" "0"
check "output" "$(jq -r '.output' "$RESULTS_FILE")" "The run succeeded in 12 seconds."
check "all six results are present" \
  "$(jq -r 'keys_unsorted | sort | join(",")' "$RESULTS_FILE")" \
  "completionTokens,finishReason,model,output,promptTokens,totalTokens"

#
# 3. System prompt, temperature, seed and the json response format.
#
echo
echo "optional params shape the request body"
start_mock '[{"status": 200, "body": {"model":"m","choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}}]'
mkdir -p "$TMP/results2"
run_prompt "$TMP/results2" \
  PARAM_SYSTEMPROMPT="You are terse." \
  PARAM_TEMPERATURE="0.1" \
  PARAM_MAXTOKENS="256" \
  PARAM_RESPONSEFORMAT="json" \
  PARAM_SEED="42"

check "exits zero" "$RC" "0"
REQ="$(head -1 "$REQUESTS")"
check "system message comes first" \
  "$(printf '%s' "$REQ" | jq -r '[.body.messages[].role] | join(",")')" "system,user"
check "system content" \
  "$(printf '%s' "$REQ" | jq -r '.body.messages[0].content')" "You are terse."
check "temperature" "$(printf '%s' "$REQ" | jq -r '.body.temperature')" "0.1"
check "max_tokens" "$(printf '%s' "$REQ" | jq -r '.body.max_tokens')" "256"
check "responseFormat=json becomes response_format json_object" \
  "$(printf '%s' "$REQ" | jq -r '.body.response_format.type')" "json_object"
check "seed is sent as a number" "$(printf '%s' "$REQ" | jq -r '.body.seed')" "42"

#
# 4. File context: fenced under a heading, and truncated at the byte budget.
#
echo
echo "files are appended as fenced blocks and truncated at maxContextBytes"
start_mock "$(completion_response)"
mkdir -p "$TMP/ws"
printf 'alpha contents\n' > "$TMP/ws/a.txt"
head -c 400 /dev/zero | tr '\0' 'b' > "$TMP/ws/b.txt"
mkdir -p "$TMP/results3"
run_prompt "$TMP/results3" \
  PARAM_FILES="$TMP/ws/a.txt, $TMP/ws/b.txt" \
  PARAM_MAXCONTEXTBYTES="100"

check "exits zero" "$RC" "0"
CONTENT="$(head -1 "$REQUESTS" | jq -r '.body.messages[0].content')"
check_contains "the prompt is still first" "$CONTENT" "How did the run go?"
check_contains "the small file is headed by its path" "$CONTENT" "## $TMP/ws/a.txt"
check_contains "the small file is fenced" "$CONTENT" 'alpha contents'
check_not_contains "the file over budget is not appended" "$CONTENT" "## $TMP/ws/b.txt"
check_contains "the truncation is named in the prompt" "$CONTENT" "These files were omitted: $TMP/ws/b.txt"

echo
echo "an unreadable file fails the task"
start_mock "$(completion_response)"
mkdir -p "$TMP/results4"
run_prompt "$TMP/results4" PARAM_FILES="$TMP/ws/missing.txt"
check "exits non-zero" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "says which file" "$STDERR_TXT" "FILE_NOT_READABLE"
check "no request was sent" "$(wc -l < "$REQUESTS" | tr -d ' ')" "0"

#
# 5. Retry: 429 then 500 then 200.
#
echo
echo "429 and 5xx are retried, and the eventual 200 is the answer"
start_mock '[{"status":429,"body":{"error":"slow down"}},{"status":500,"body":{"error":"boom"}},{"status":200,"body":{"model":"m","choices":[{"finish_reason":"stop","message":{"content":"eventually"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}}]'
mkdir -p "$TMP/results5"
run_prompt "$TMP/results5"

check "exits zero" "$RC" "0"
check "three attempts were made" "$(wc -l < "$REQUESTS" | tr -d ' ')" "3"
check "the last response is the result" "$(cat "$TMP/results5/output")" "eventually"

echo
echo "a persistent 5xx fails after three attempts"
start_mock '[{"status":503,"body":{"error":"unavailable"}}]'
mkdir -p "$TMP/results6"
run_prompt "$TMP/results6"
check "exits non-zero" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check "stopped at three attempts" "$(wc -l < "$REQUESTS" | tr -d ' ')" "3"
check_contains "names the status" "$STDERR_TXT" "HTTP 503"

#
# 6. A 4xx is the request being wrong: fail at once, and never print the token.
#
echo
echo "a 4xx fails immediately without retrying and without leaking the token"
start_mock '[{"status":401,"body":{"error":{"message":"Incorrect API key provided"}}}]'
mkdir -p "$TMP/results7"
run_prompt "$TMP/results7"

check "exits non-zero" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check "only one attempt" "$(wc -l < "$REQUESTS" | tr -d ' ')" "1"
check_contains "names the status" "$STDERR_TXT" "HTTP 401"
check_contains "carries the provider's reason" "$STDERR_TXT" "Incorrect API key provided"
check_not_contains "the token is not in stderr" "$STDERR_TXT" "$TOKEN"
check_not_contains "the token is not in stdout" "$STDOUT_TXT" "$TOKEN"

#
# 7. Required and malformed params fail before any request.
#
echo
echo "required and malformed params fail before any request"
start_mock "$(completion_response)"
mkdir -p "$TMP/results8"

run_prompt "$TMP/results8" PARAM_MODEL=""
check "a missing model fails" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "says which param" "$STDERR_TXT" "MISSING_PARAM - the 'model' param is required"

run_prompt "$TMP/results8" PARAM_RESPONSEFORMAT="yaml"
check "an unknown responseFormat fails" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "says what is allowed" "$STDERR_TXT" "responseFormat must be 'text' or 'json'"

run_prompt "$TMP/results8" PARAM_MAXTOKENS="many"
check "a non-numeric maxTokens fails" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "says what is expected" "$STDERR_TXT" "maxTokens must be an integer"

check "nothing was sent to the endpoint" "$(wc -l < "$REQUESTS" | tr -d ' ')" "0"

#
# 8. responseFormat=json but the model did not return JSON.
#
echo
echo "responseFormat=json rejects a non-JSON completion"
start_mock '[{"status":200,"body":{"model":"m","choices":[{"finish_reason":"stop","message":{"content":"sorry, prose"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}}]'
mkdir -p "$TMP/results9"
run_prompt "$TMP/results9" PARAM_RESPONSEFORMAT="json"
check "exits non-zero" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "says why" "$STDERR_TXT" "INVALID_RESPONSE"

echo
echo "-----"
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
