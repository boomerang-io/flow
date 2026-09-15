#!/usr/bin/env python3
"""A stand-in for an OpenAI-compatible endpoint, for tasks/ai/test/run.sh.

Records every request it receives as one JSON object per line, and answers from a scripted list of
responses so a test can drive the retry path as well as the happy path.

    python3 mock_openai.py <port> <requests.jsonl> <responses.json>

`responses.json` is a list of {"status": int, "body": <any>}; the last entry is repeated once the
list is exhausted.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
REQUESTS_PATH = sys.argv[2]
RESPONSES = json.load(open(sys.argv[3]))

state = {"n": 0}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler's naming
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        try:
            body = json.loads(raw)
        except ValueError:
            body = None
        with open(REQUESTS_PATH, "a") as handle:
            handle.write(
                json.dumps(
                    {
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "contentType": self.headers.get("Content-Type"),
                        "body": body,
                        "raw": raw,
                    }
                )
                + "\n"
            )

        index = min(state["n"], len(RESPONSES) - 1)
        state["n"] += 1
        response = RESPONSES[index]
        payload = json.dumps(response["body"]).encode("utf-8")
        self.send_response(response["status"])
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
