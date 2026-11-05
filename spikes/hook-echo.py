#!/usr/bin/env python3
"""Throwaway spike: log each hook POST body; optionally rewrite adb commands.

SPIKE_MODE=rewrite -> prefix adb commands via updatedInput.
SPIKE_PREFIX      -> prefix string (default "export ANDROID_SERIAL=FAKE; ")
SPIKE_ADB_REPL    -> if set, replace leading "adb " with this instead of prefixing
SPIKE_DECISION    -> if set (allow|ask|deny), also emit permissionDecision
"""
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hook-log.jsonl")
MODE = os.environ.get("SPIKE_MODE", "off")
PREFIX = os.environ.get("SPIKE_PREFIX", "export ANDROID_SERIAL=FAKE; ")
REPL = os.environ.get("SPIKE_ADB_REPL")
DECISION = os.environ.get("SPIKE_DECISION")


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        with open(LOG, "a") as f:
            f.write(body.replace("\n", " ") + "\n")
        reply = {}
        try:
            p = json.loads(body)
            ti = p.get("tool_input") or {}
            cmd = ti.get("command", "")
            if (MODE == "rewrite" and p.get("hook_event_name") == "PreToolUse"
                    and isinstance(cmd, str) and cmd.startswith("adb")):
                new = (REPL + cmd[4:]) if REPL else (PREFIX + cmd)
                hso = {"hookEventName": "PreToolUse",
                       "updatedInput": {**ti, "command": new}}
                if DECISION:
                    hso["permissionDecision"] = DECISION
                reply = {"hookSpecificOutput": hso}
        except Exception:
            pass
        out = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", 47899), H).serve_forever()
