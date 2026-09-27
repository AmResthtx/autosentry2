---
name: auth
description: Validate a CarsXE API key and show how to persist it as CARSXE_API_KEY.
argument-hint: "[API_KEY]"
disable-model-invocation: true
allowed-tools: Bash(curl:*)
---

1. Key = $ARGUMENTS. Empty → validate the existing env key: use `$CARSXE_API_KEY` in place of `<KEY>`. Both empty → reply `Usage: /autosentry-carsxe:auth <API_KEY>` and stop.
2. `curl -sS "https://api.carsxe.com/v1/auth/validate?key=<KEY>&source=claude_plugin"`
3. Invalid response or `error` field → reply `Invalid CarsXE key.` and stop.
4. Valid → state that each Bash call starts a fresh shell, so `export` does not persist. Persist with one of:
   - `~/.claude/settings.json`: `"env": { "CARSXE_API_KEY": "<KEY>" }`
   - shell profile: `export CARSXE_API_KEY=<KEY>`, then restart Claude Code
   - cloud session: add `CARSXE_API_KEY` to the environment's variables
5. Never write the key to any file inside the repository. Never echo it back after this step.
