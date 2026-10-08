# 702165405 fork: maintenance rules

- This directory is the working repository for `702165405/codex-mobile-bridge`.
- `origin` must point to `https://github.com/702165405/codex-mobile-bridge.git`.
- `upstream` must point to `https://github.com/try2love/codex-mobile-bridge.git` for fetching only. Never push to upstream.
- For upstream sync requests, run `python scripts/prepare-upstream-sync.py`. It prepares a separate review branch; it never commits, pushes or changes `main`.
- Review incoming changes, especially authentication, IPC, HTTP endpoints, path validation, dependencies, build/update signing and GitHub workflows. Preserve fork-specific fixes.
- Run the full Python test suite, `npm run test:desktop` and `npm run test:updater`; document unavailable platform/GUI checks.
- Merge into this fork's main branch only after diff review and passing relevant checks. Prefer a PR and keep the merge commit so upstream ancestry is preserved.
- The desktop updater must use this fork's releases, not the upstream release feed. Do not bypass signature verification or publish with someone else's signing identity. Configure this fork's own release key separately before publishing signed releases.
- Never commit runtime data, notification tokens, credentials, local diagnostics, backups, `.local`, `.tmp`, build output or `node_modules`.
- Notification retries stop after three failures per event/channel/destination. PushPlus 900 must pause that recipient across automatic senders; no background probes while the account is restricted. Preserve tokens and other channels, redact all provider error bodies.
