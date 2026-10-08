# 702165405 fork: maintenance rules

- This directory is the working repository for `702165405/codex-mobile-bridge`.
- `main` is the primary development and integration branch. Local feature branches merge into this fork's `main`; preserve Android and other custom features.
- `origin` must point to `https://github.com/702165405/codex-mobile-bridge.git`.
- `upstream` must point to `https://github.com/try2love/codex-mobile-bridge.git` for fetching only. Never push to upstream.
- For each update, first fetch `upstream/main` and `origin/main`; stop if either fetch fails. On a clean local `main`, run `python3 scripts/prepare-upstream-sync.py`. It starts a review branch from local `main`, merges `origin/main` first, then prepares an uncommitted merge of `upstream/main`. It may commit the fork-remote merge on the review branch, but never pushes or changes `main`.
- Review incoming changes, especially authentication, IPC, HTTP endpoints, path validation, dependencies, build/update signing and GitHub workflows. Preserve fork-specific fixes.
- Run the full Python test suite, `npm run test:desktop` and `npm run test:updater`; document unavailable platform/GUI checks.
- Merge into this fork's main branch only after diff review and passing relevant checks. Prefer a PR and keep the merge commit so upstream ancestry is preserved. Resolve conflicts in favor of this fork's intended behavior while retaining applicable upstream security fixes; never blindly use `-X ours` or overwrite with upstream files. Report incoming features, security findings, test results and remaining limitations.
- When Android changes, also run its unit tests, Lint and debug build using the existing local SDK/JDK; document any device tests that were not run.
- The desktop updater must use this fork's releases, not the upstream release feed. Do not bypass signature verification or publish with someone else's signing identity. Configure this fork's own release key separately before publishing signed releases.
- Never commit runtime data, notification tokens, credentials, local diagnostics, backups, `.local`, `.tmp`, build output or `node_modules`.
- Notification retries stop after three failures per event/channel/destination. PushPlus 900 must pause that recipient across automatic senders; no background probes while the account is restricted. Preserve tokens and other channels, redact all provider error bodies.
