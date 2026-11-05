# Hook findings (Task 0 spike)

Claude Code run with `--settings <file> --setting-sources project,local --permission-mode default --model haiku -p`, http hooks to `spikes/hook-echo.py` on 127.0.0.1:47899. Device: real, `adb version` 1.0.41 (35.0.2).

## Gate result: BLOCKED on prefix rewrite (agent_id is fine)

- `agent_id` present on subagents and forks: YES.
- No prefix form keeps **allow rules** matching. A command rewritten via `updatedInput` is re-checked against rules *using the rewritten string*.
- Workaround found (not a product decision): return `permissionDecision:"allow"` together with `updatedInput`.

## 1. Payload shapes (session ids shortened)

PreToolUse, main agent (no `agent_id` / `agent_type` keys at all):
```json
{"session_id":"6274a121-...","permission_mode":"default","hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"adb version","description":"Print adb version"},"tool_use_id":"toolu_01Minh..."}
```
Also carries `transcript_path`, `cwd`, `prompt_id`, `effort:{level}`.

SubagentStart (general-purpose):
```json
{"session_id":"6274a121-...","agent_id":"ad8f9e5ea735442a9","agent_type":"general-purpose","hook_event_name":"SubagentStart"}
```
(plus `transcript_path`, `cwd`, `prompt_id`). Keys: agent_id, agent_type, cwd, hook_event_name, prompt_id, session_id, transcript_path.

PreToolUse inside subagent: same as main plus `"agent_id":"ad8f9e5ea735442a9","agent_type":"general-purpose"`.

SubagentStop (exact, trimmed paths):
```json
{"session_id":"c85c2af1-...","transcript_path":".../c85c2af1-....jsonl","cwd":"...","prompt_id":"f0faf544-...","permission_mode":"bubble","agent_id":"a0f874788f81263cb","agent_type":"fork","effort":{"level":"medium"},"hook_event_name":"SubagentStop","stop_hook_active":false,"agent_transcript_path":".../c85c2af1-.../subagents/agent-a0f874788f81263cb.jsonl","last_assistant_message":"Output of `adb devices`: ...","background_tasks":[{"id":"a0f874788f81263cb","type":"subagent","status":"running","description":"Run adb devices","agent_type":"fork"}],"session_crons":[]}
```
(general-purpose variant identical shape, `permission_mode:"default"`.)

SessionEnd (exact; only these fields plus transcript_path/cwd):
```json
{"session_id":"6274a121-...","transcript_path":"...","cwd":"...","prompt_id":"80037a60-...","hook_event_name":"SessionEnd","reason":"other"}
```
`reason` was `"other"` for `-p` exit. No `agent_id`.

## 2. session_id sharing

Subagent and fork events carry the **same `session_id` as the parent**. Holder identity for subagents must therefore be `session_id` + `agent_id`; the main agent is `session_id` with no `agent_id`.

Note: a SubagentStop can arrive while `background_tasks` still shows the agent "running" (async fork). Observed order for the fork: SubagentStart, PreToolUse, SubagentStop, SessionEnd. A `Agent`-tool PreToolUse was not logged because the matcher was `Bash` only.

## 3. Forks

`Agent` with `subagent_type:"fork"` worked in this build. Hook events carry `agent_id` and `agent_type:"fork"`, `permission_mode:"bubble"`. Same `session_id` as parent.

## 4. updatedInput rewrite vs permission rules

Settings allow: `Bash(adb version)`, `Bash(adb devices:*)`. Control (SPIKE_MODE=off): both commands ran without prompt.

| Rewrite of `adb version` / `adb devices` | Result |
|---|---|
| `export ANDROID_SERIAL=FAKE; adb version` | DENIED: "This Bash command contains multiple operations. The following part requires approval: export ANDROID_SERIAL=FAKE" |
| `ANDROID_SERIAL=FAKE adb version` | DENIED: "This command requires approval" |
| `ANDROID_SERIAL=FAKE adb devices` (rule `adb devices:*`) | DENIED: "This command requires approval" (wildcard rule also fails) |
| `env ANDROID_SERIAL=FAKE adb version` | DENIED: "This command requires approval" |
| `adb -s FAKE version` | DENIED: "This command requires approval" |
| `ANDROID_SERIAL=FAKE adb version` + hook `permissionDecision:"allow"` | RAN (no denial) |

Conclusions:
- Allow rules are evaluated against the rewritten command; no tested prefix form (`export X; `, `X=Y `, `env X=Y `, `adb -s X`) keeps them matching.
- With `permissionDecision:"allow"` in the same hook response, the rewrite executes. Proof: `adb shell echo hi` rewritten to `ANDROID_SERIAL=FAKE adb shell echo hi` failed with `adb: device 'FAKE' not found`.
- A user `deny` rule still wins over hook allow: with deny `Bash(adb reboot:*)`, rewrite+allow of `adb reboot --help` returned "Permission to use Bash with command ANDROID_SERIAL=FAKE adb reboot --help has been denied."
- Caveat: hook `allow` bypasses the user's own allow/ask rules for those commands (everything rewritten becomes auto-approved unless denied). Product must decide whether that is acceptable (e.g. only emit allow when the original command would have matched an allow rule, which the app cannot see) or use another route (wrapper script on PATH, `ANDROID_SERIAL` via SessionStart env file, etc.).
- Untested: pure-env route (`CLAUDE_ENV_FILE` in SessionStart), user rules written with the prefix.

## 5. statusMessage

`"statusMessage":"claude-device-manager"` on an http hook entry was accepted: settings loaded and hooks fired with no validation error.

## 6. Misc

- Hook response `{}` is fine for all events.
- `--max-turns` is not a listed flag in this CLI; runs were bounded by task size (each ~US$0.001-0.01, haiku).
- `--setting-sources project,local` plus `--settings` isolated the experiment from `~/.claude/settings.json`.
