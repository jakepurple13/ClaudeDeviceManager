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

## E2E (Task 12)

Run on 2026-11-05 with the real `~/.claude` config and a real phone (S6003L, adb serial MBX6P240401889). The app ran via `./gradlew :composeApp:run`. Sessions ran as `claude -p --model haiku --output-format stream-json` from throwaway project dirs. Each dir's `.claude/settings.local.json` allowed only `Bash(adb:*)` and `Bash(cdm:*)`. Times below are epoch seconds from the transcripts.

| Step | Result | Evidence |
|---|---|---|
| 1. Auto-install on startup | PASS | Port file showed 47823. `/cdm/ping` returned `cdm-ok`. `settings.json` had 4 entries with `statusMessage` `claude-device-manager`: PreToolUse `Bash` and PreToolUse `Edit\|Write\|MultiEdit\|NotebookEdit` (timeout 2), plus SubagentStop and SessionEnd (timeout 5). `~/.local/bin/cdm` was `-rwxr-xr-x`. `~/.claude/CLAUDE.md` was created with the `<!-- cdm:start/end -->` block. A backup file `settings.json.cdm-backup-20261105-160255` existed. |
| 2. Session A acquires and is pinned | PASS | `cdm acquire` printed "Got S6003L (MBX6P240401889)". `adb shell getprop ro.product.model` printed `S6003L`. `printenv ANDROID_SERIAL` printed `MBX6P240401889`, which proves the rewrite ran. The app title bar showed "1 in use · 1 waiting". The Devices list showed "S6003L phone · projA #bd78" with Queue badge 1. |
| 3. Session B queues behind A | PASS | B's acquire started at 847.4 and printed "Waiting for a phone device… position 1". A's `cdm release` ran at 879.3. B was granted at 879.3: "Got S6003L". The activity log shows `released bd78…:main (SELF_RELEASE)` and `granted 6c3e…:main` in the same ms. |
| 4. A subagent is its own holder | PASS | Session C's main agent acquired the phone, then spawned a general-purpose subagent. The subagent's `cdm status` printed "No device held and not queued", so it did not share main's hold. Its `cdm acquire` queued as `c6740ffe…:a37cbbee77354b46e` at position 1, behind `c6740ffe…:main`. Main was released via `POST /cdm/release?holder=<session>:main`, standing in for main releasing. The subagent was granted in the same ms, then ran getprop (`S6003L`) and `cdm release` (SELF_RELEASE). |
| 5. User release from the app | SKIPPED | Release is UI-only, with no HTTP route, and synthetic clicks are blocked. Unit tests cover it. |
| 6. Permission mirror while holding | PASS | `printf hi \| wc -c` and `python3 -c "print(42)"` were both denied. In `permission_denials` they appear as `export ANDROID_SERIAL=MBX6P240401889; …`, so the mirror returned ask and `-p` denied them. `adb shell ls /sdcard \| head -1` ran and printed `Alarms`. |
| 7. SIGTERM exit uninstalls | PASS | After `pkill -TERM`, cleanup finished within 1 s. No cdm hook entries remained. `cdm`, `CLAUDE.md`, and the port file were gone. `settings.json` sha1 was `ec8f3f8c…`, byte-identical to the pre-test copy. Port 47823 was free. Gradle exit 143 is the expected SIGTERM code. |
| 8. App down | PASS | A fresh session ran `adb devices` normally, with no hook error and no denials. |
| 9. Cmd+Q | PARTIAL | The app restarted and installed again, adding a second backup. macOS blocked the keystroke: "osascript is not allowed to send keystrokes (1002)". `tell application "java" to quit` did not reach the JVM. The fallback SIGTERM uninstalled cleanly again, and `settings.json` was byte-identical. |

Findings:
- In `-p` mode, an ask from the mirror reaches Claude only as the hook reason "Pinned to <serial> by Claude Device Manager". Nothing in that text says permission is needed, and Session A's agent read it as "blocked". Interactive sessions show a normal prompt, so this is UX only.
- `PermissionMirror.DEFAULT_READ_ONLY` is narrower than Claude Code's read-only set. With the app down, `printf hi | wc -c` ran with no prompt. While holding a device, it became an ask. This errs safe (an extra prompt, never an extra allow).
- The Claude Code harness blocks a standalone `sleep N` ("Blocked: standalone sleep"). `adb shell sleep 40` was used to hold the device instead.
- SessionEnd released a hold that was never released explicitly: `released 06a6…:main (SESSION_END)`.
- Backups `settings.json.cdm-backup-*` stay in `~/.claude` after exit, by design (pruned to 5). Both are byte-identical to the original.
