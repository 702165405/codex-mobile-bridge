# Codex App Mobile Bridge

[简体中文](README.md) · [English](README_EN.md)

Continue **existing chats in the desktop Codex App** from your phone browser, or create an empty chat in a saved project.

The phone and desktop use the same chat. Read replies, send messages, choose models and Skills, and respond to pending confirmations. Local tasks continue on the original computer; SSH tasks continue on the original server. Each chat keeps its model provider and authentication settings.

**Your phone does not need to sign in to the same OpenAI account as the desktop.** The gateway has its own username/password login and an explicit passwordless option. Connect over LAN, a temporary HTTPS tunnel, or your own reverse proxy.

> Community project, not affiliated with OpenAI. Supports macOS and Windows and depends on internal Codex App IPC. See [verification records](VERIFICATION.md) for what was actually tested. App updates may require compatibility changes.

## One prompt for a deployment Agent

Copy this to an Agent on the computer:

```text
Deploy and run https://github.com/try2love/codex-mobile-bridge for me. Identify whether this computer runs Windows or macOS, read the repository's deployment Agent instructions, and complete installation, startup and verification for that platform. Reuse existing Codex App chats and their model authentication. Keep username/password authentication and LAN access enabled by default. For external access, reuse an existing NAS/HTTPS reverse proxy when available; with an owned server and domain, use an SSH return tunnel and fixed HTTPS; otherwise configure a temporary HTTPS tunnel. Connection methods may run together. Preserve any existing LAN port. Verify chat reading, live updates and the available interaction paths. Keep the service running, then return clickable phone URLs, how to obtain login credentials, start/stop commands, verified results and any steps I still need to complete.
```

See [Deployment Agent instructions](#deployment-agent-instructions) below for the full handoff requirements.

## Desktop App and ntfy — experimental branch

The cross-platform integration branch, **`integration/desktop-cross-platform`**, combines the desktop and mobile features from `feature/desktop-ntfy` with the Windows installer, tray and compatibility changes from `codex/windows-desktop-app`. The desktop launcher manages the gateway; the original Codex App still owns execution and model authentication.

### Install and use the App

Download **`v0.2.0-beta.1` prerelease** from [GitHub Releases](https://github.com/try2love/codex-mobile-bridge/releases): Windows x64 Setup / full ZIP, or macOS Apple Silicon (arm64) ZIP. `SHA256SUMS.txt` contains download checksums. Development artifacts are also available from successful **GitHub Actions → Desktop builds** runs, or build locally. On Mac, extract and open the `.app`. Windows x64 provides a per-user `Setup.exe` installer and a ZIP; extract the entire ZIP before running `Codex Mobile Bridge.exe`. Do not move only the executable. The packaged App includes the Python gateway runtime; Python, Node.js and a terminal are not required for everyday use. Builds are currently unsigned and are not notarized.

On Windows, closing the window hides it to the tray; launching again restores it. The tray offers separate actions to stop the gateway and quit, or quit only the controller. Stop the gateway and exit before upgrading or moving the app. Select Chinese or English at the top right; existing Windows language preferences are retained, and tray labels follow the selection. Phone language is independent. Uninstalling does not automatically remove gateway settings or credentials.

The App provides:

- **Overview:** start/stop the gateway, copy/open phone URLs, expand QR sign-in, and locate initial login credentials.
- **Network and login:** the LAN port, independently enabled connection profiles, extra HTTPS addresses, username/password or explicit passwordless access.
- **Runtime settings:** Codex data directory, IPC address, executable paths, gateway data directory, and automatic startup when the App opens.
- **Phone notifications:** ntfy server, topic, token, click URL, title privacy and a test notification.
- **Runtime logs:** newest records first within each source; stack traces within one error keep their original order. Refresh returns to the newest records.

If you already use the command-line gateway, choose its existing `.local` directory under Runtime settings. Stop the gateway before changing network settings or executable paths. The listening port never changes automatically. Closing the launcher leaves the gateway running; **Stop** ends phone access. Login changes apply on the next gateway start; notification changes are read while it runs.

Unsaved changes appear as red dots in the affected sidebar section and save bar. Switching pages or languages preserves edits. Saving successfully, or reverting to the original values, clears the indicators.

`cloudflared` remains an optional external program. The App does not install it automatically; select its existing path when using temporary HTTPS.

### QR sign-in

1. Start the gateway and find a reachable phone address on the overview page.
2. Expand **Scan to sign in** below that address. Each LAN, ready temporary HTTPS or configured fixed HTTPS entry has its own collapsible area; multiple areas can be expanded together. Loopback addresses such as `127.0.0.1` have no phone QR code.
3. Scan with the phone camera and open the link in a browser to sign in without typing a password. The App checks that the entry points to this running gateway before generating a code.
4. Each QR code expires after **5 minutes** and works **once**. Collapsing or refreshing revokes it; restarting the gateway invalidates all codes. A successful browser session lasts 12 hours, independently of QR expiry, until logout or gateway restart.

Treat the QR code as a short-lived login credential and keep screenshots private. It does not contain your username/password, disable password protection or create a network tunnel. Images are generated locally. Opening the ordinary URL still uses the configured login method. LAN reachability or a working public HTTPS entry is required; verify camera scanning and connectivity on your own phone.

### Parallel connections

Under **Network and login → Additional connections**, choose a type and click **Add connection**. Name, enable, disable or delete each profile independently. LAN access works independently of these profiles.

| Connection | When to use it | Configuration |
| --- | --- | --- |
| LAN | Phone and computer share a reachable network | Enable LAN access and keep the existing port |
| Temporary Cloudflare HTTPS | No domain or existing public entry | Add a temporary connection and select cloudflared under Runtime settings |
| Own server + SSH | You have a Linux public server and domain; the computer is behind NAT or on a campus network | Enter the fixed HTTPS URL, existing SSH alias or `user@hostname`, and server loopback port |
| NAS / existing proxy / Docker | The NAS can reach the computer and already has an HTTPS reverse proxy | Enter the fixed HTTPS URL and the computer's HTTP address as reachable from the NAS |

LAN, a temporary Cloudflare tunnel and multiple fixed entries can run together. Only one temporary tunnel is needed per gateway. Profiles targeting the same SSH server need different loopback ports. Different SSH aliases pointing to the same server can still conflict; choose their ports accordingly.

All entries reach the same gateway and share the same gateway login. Saved single-entry settings from earlier versions are converted when read; existing files are not rewritten until you save.

Each enabled fixed profile has **Export deployment ZIP**, **Copy for deployment Agent**, and **Check fixed entry** actions. The ZIP contains concrete configuration and Chinese/English deployment instructions, without passwords, notification tokens, Codex data or SSH private keys. Clipboard instructions use the current UI language.

#### Own server

```text
Phone → server HTTPS → server loopback port → SSH → computer gateway → original Codex App
```

The computer initiates SSH, so the server does not need direct access to the computer's private IP. SSH forwarding starts/stops with the gateway and retries after disconnection. It uses existing system OpenSSH keys or an unlocked ssh-agent. Verify the server fingerprint in a terminal first; the App does not save SSH passwords or bypass host verification.

The server must allow remote forwarding and keep the listening socket on loopback (`GatewayPorts no` or `clientspecified`, not `yes`). For the generated Caddy configuration, DNS must point to the server and public TCP 80/443 must be reachable and free. Caddy obtains and renews certificates. Its generated Docker service uses host networking on Linux Docker Engine, on the same host as SSH; it is not a Mac/Windows Docker Desktop deployment.

If an existing proxy already owns 80/443, keep it. Use the upstream printed in the deployment instructions and preserve the public Host header instead of starting another Caddy instance.

#### NAS and Docker

Docker hosts the access entry. The original computer still needs to stay awake with Codex App and the gateway running. If the NAS already has an HTTPS proxy that can reach the computer, direct forwarding is usually enough. The optional Nginx container is an HTTP intermediary behind the NAS's existing HTTPS termination.

A domain alone does not make separate private networks reachable. A home NAS cannot directly reach an isolated campus computer; use a working VPN route or the own-server SSH option. See [NAS deployment instructions](deploy/nas/README.md) and the English instructions in an exported ZIP.

#### Verification

**Check fixed entry** verifies that the HTTPS endpoint returns the identity of this exact running gateway. It checks from the computer only: also disable phone Wi-Fi and test login and chat over mobile data.

The overview lists all enabled fixed URLs. With the notification click URL empty, notifications prefer the first enabled fixed URL, then another HTTPS entry, then LAN. Enter an explicit notification URL if you want a specific entry. Domain/server costs are determined by the services you choose; this project does not purchase or provision resources.

### What are “Additional HTTPS addresses”?

This is the gateway's **address allowlist**. Add a URL only when its HTTPS reverse proxy or tunnel is already configured elsewhere and you need another domain to reach the gateway. Enter one origin per line, such as `https://codex.example.com`, without a path.

Adding a URL does **not** create a tunnel, configure DNS or obtain a certificate. The proxy must reach the gateway and preserve the public Host header. Fixed URLs in connection profiles are added automatically. Leave this field empty when using only LAN or temporary Cloudflare.

### Chinese and English UI

The desktop App and phone website each have a **Language / 语言** selector and remember their own choice. Switching language does not restart the gateway or translate chat content, commands, model/Skill descriptions, user input or raw logs. Unsaved settings, message drafts and pending-confirmation form input are preserved. Language switching is disabled while a confirmation is being submitted.

### ntfy setup and testing

1. Install ntfy on iPhone or Android and allow system and lock-screen notifications.
2. For an initial test, use `https://ntfy.sh`. Generate a random topic in the desktop App and subscribe to exactly that server and topic on the phone. Public anonymous topics need no token and are created automatically. Anyone who knows such a topic can read and publish; use a long random name and leave chat titles hidden. Consider a protected topic for regular use.
3. Enable notifications, save, then click **Send test notification**. Confirm actual receipt on the phone. This test does not require the gateway to run; server acceptance alone is not proof of phone delivery.
4. Start the gateway, refresh the phone website, open a connected chat and enable **Reminders**. Local and SSH chats are watched separately.
5. New command, file, permission or question requests trigger a notification. Clicking it opens that chat using the normal web login and confirmation flow.

Watched chats remain monitored after the phone page closes, provided the computer, gateway, original App and relevant SSH connections stay online. Persistent deduplication avoids repeated notifications for the same request after reconnection. Failed delivery retries with backoff and rechecks whether the request is still pending; strict exactly-once delivery is not guaranteed during network failures.

An explicitly configured notification click URL takes priority over automatic selection. Old notifications retain their old URLs after a temporary domain changes. Self-hosted ntfy needs an APNs upstream for instant iPhone delivery; Android delivery also depends on background/battery permissions. See the official [phone subscription guide](https://docs.ntfy.sh/subscribe/phone/) and [iOS instant notification setup](https://docs.ntfy.sh/config/#ios-instant-notifications).

## Features

| Feature | Details |
| --- | --- |
| Existing App chats | Read history, live replies and tool output |
| New chats | Create an empty chat in a saved local/SSH project, then hand it to the desktop App; creation itself does not call a model |
| Markdown and math | Headings, lists, tables, code and LaTeX math; assets and fonts are served locally |
| Progressive history | Show the latest 20 records first, silently fill to 100, then load 100 more near the top; expand large tool content on demand |
| Original execution | Send, steer, queue, withdraw queued messages, stop and respond to supported confirmation cards |
| Local and SSH | Execute on the chat's original computer or server |
| Chat navigation | Sort by recent activity or group by expandable projects |
| Model and Skills | Choose model/reasoning effort or send installed Skills from the chat's host |
| Independent authentication | Username/password or explicit passwordless mode |
| Parallel access | LAN, temporary HTTPS and multiple fixed entries |
| File previews | Local chat references inside the working directory, up to 50 MiB per file |
| Bilingual UI | Chinese / English on the desktop and phone |

Large history pages also have a byte budget, so a large reply may require multiple pages. Incremental long polling updates changed records without retransmitting the entire chat. Stale cursors after a gateway restart or replaced desktop snapshot trigger a fresh synchronization. Full model context stays on the computer.

## Requirements

- macOS or Windows 10/11 with the original Codex App running.
- For command-line deployment: native Python 3.9+. The gateway uses only the Python standard library; no WSL or pip dependencies are needed.
- Keep the computer awake, network reachable and gateway running.
- For SSH chats: the host is configured in the App, the SSH alias works non-interactively, and remote Python 3 is available. Model/Skill discovery also needs the remote Codex runtime. On Windows, OpenSSH `ssh.exe` must be on PATH.
- Optional temporary tunnel: an installed `cloudflared` executable.

## Quick start: LAN

### Windows PowerShell

```powershell
git clone https://github.com/try2love/codex-mobile-bridge.git
cd codex-mobile-bridge
git switch feature/desktop-ntfy
py -3 -B .\run.py --lan
```

You can also double-click `start.cmd`; it tries `py -3`, then `python`. Keep the terminal open. Stop with `Ctrl+C`, `py -3 -B .\stop.py`, or `stop.cmd`. With a custom `--config`, pass the same path to the stop command.

The gateway reads `%USERPROFILE%\.codex` or `CODEX_HOME` and uses the local `\\.\pipe\codex-ipc` named pipe. Run it as the same Windows user as the App. `--codex-home` changes the data directory, not the pipe name. `--ipc-path` is a local endpoint override, not a way to connect to another computer.

Runtime discovery includes `%LOCALAPPDATA%\OpenAI\Codex\bin`, common installation directories, the current user's MSIX package and PATH. For custom installations:

```powershell
py -3 -B .\run.py --lan --codex-bin 'C:\path\to\codex.exe'
```

If Windows Firewall prompts, allow only the network scope you need. Files use UTF-8. Restrict access with Windows directory ACLs; POSIX chmod does not replace them.

### macOS

```sh
git clone https://github.com/try2love/codex-mobile-bridge.git
cd codex-mobile-bridge
git switch feature/desktop-ntfy
python3 -B "$PWD/run.py" --lan
```

Alternatively, double-click `启动手机网关.command`. Stop with `Ctrl+C`, `python3 -B stop.py`, or `停止手机网关.command`.

Open the printed LAN URL on a phone on the same network, for example `http://192.168.1.10:8787`. The initial username is `admin`; the generated password is in `.local/首次登录.txt`. Select a chat and wait for **Connected** before sending.

Without `--lan`, the gateway listens only on `127.0.0.1`. It does not install a system login service. Restarting the gateway requires logging in again. If a chat shows saved history only, open it in the desktop App and reconnect on the phone; the gateway does not create a replacement execution owner for an unloaded chat.

## Temporary HTTPS and existing proxies

Install cloudflared from the [official download instructions](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/). Then, using the same configuration and port as your existing gateway:

```sh
python3 -B run.py --lan --tunnel --cloudflared /path/to/cloudflared
```

On Windows, use `py -3 -B .\run.py` and the path to `cloudflared.exe`. Quick Tunnel URLs are temporary, require no purchased domain and may change after restart. The computer must be able to make outbound connections to the tunnel service. The initial tunnel handshake runs in the background so LAN access can become ready independently.

For an already configured HTTPS reverse proxy:

```sh
python3 -B run.py --lan --origin https://codex.example.com
```

Preserve the external Host header, Origin, cookies and CSRF header; disable proxy caching/buffering and allow long requests. Use a dedicated hostname root, not `/codex/`. The proxy must reach the computer. TLS termination and DNS are configured on your own infrastructure. The App's profile export is the guided route for own-server SSH and NAS deployments.

## Phone operations

- **New chat:** choose a saved desktop project and name. Creation stores an empty chat through the official runtime, closes the helper and opens it in the original App. Sending waits for desktop ownership. Creation may change the desktop's visible chat. Saved request IDs prevent blind duplicate creation after an uncertain result.
- **Model/Skills:** settings follow the current chat's host and project. Custom model availability depends on the provider. Changing a model does not change authentication or permission policy. Select up to eight Skills to send with the next message.
- **Send modes:** new message, queue after the active task, or steer the current task. Queued messages can be withdrawn before submission.
- **Confirmations:** respond to supported command, file, permission and question requests. Unsupported requests should be handled in the desktop App.
- **Unknown result:** inspect chat history before trying again. The gateway does not automatically replay an uncertain submission.

## Login settings

The default is independent username/password authentication. To change the password from the CLI:

```sh
python3 -B run.py --set-password
```

Use `py -3 -B .\run.py --set-password` on Windows. Passwords must contain at least 12 characters. Restart the gateway for login changes to take effect. Passwordless access is explicit through the App or `--no-auth`; anyone who can reach that entry can then operate connected chats. Keep authentication for public access.

## Architecture and stored data

The bridge reads desktop chat metadata/history and forwards actions through the App's original IPC owner. It does not need a matching mobile OpenAI login and does not copy model API keys. Empty-chat creation uses a short-lived official app-server helper and then hands ownership to the App. See [architecture](ARCHITECTURE.md).

Gateway data stays in its configured directory, normally `.local`. It includes login hashes, desktop preferences, notification settings/tokens, watched chats, submission/creation deduplication records, caches, process control records and logs. Each SSH connection has separate status/log files. Do not publish this directory or `.tmp`. Keep backups private and use the App's directory selector to adopt an existing installation.

The local desktop management interface uses guarded Electron IPC and a private stdio worker. It is not exposed through the phone HTTP API. Public HTTP access remains constrained by authentication, allowed Host/Origin checks and CSRF validation.

## Known limits

- Internal Codex App IPC is version-sensitive. Revalidate after App updates.
- The computer, original App and relevant SSH owner must remain available. A NAS container alone cannot replace the desktop App.
- macOS and Windows have automated checks; real GUI, phone delivery and server deployments have separate verification scopes.
- File preview is limited to supported local chat references. Complex approval types may need the desktop.
- Fixed-domain HTTPS requires working DNS, server/NAS routing and TLS configuration. Exported configuration is not proof of a deployed public service.
- Never treat a model/Skill list lookup, server acceptance of ntfy, or a successful package build as proof of end-to-end operation.

## Deployment Agent instructions

### 1. Inspect the environment

Read the README and verification records. Identify the OS, project version/branch, running Codex App, current gateway/config directory and protected LAN port. Preserve existing chat owners, model authentication, providers, permission policies and SSH credentials. Reuse an existing gateway rather than starting duplicates.

Choose connections based on actual reachability: LAN, temporary tunnel, an existing NAS proxy, or an owned Linux server with SSH. Profiles can run together. Confirm authorization before installing missing software, changing firewall/DNS/server configuration or creating public exposure. Never publish credentials or copy `.codex` to a NAS.

### 2. Prepare and start

Use the packaged App for the guided workflow, or the OS-specific CLI commands above. Keep default password authentication unless the user explicitly chooses otherwise. Preserve the current port. Use an independent temporary directory for tests, and never commit it.

For fixed entries, export the selected profile's deployment ZIP or copy its Agent instructions. Inspect existing sites and ports before applying them. If server access is unavailable, deliver the configuration and explain what remains unconfigured. Do not claim a service is deployed solely because the files were generated.

Keep the process running using the user's chosen launcher or an authorized process-management method. Verify which process actually owns the port. Stop through the project's control mechanism; do not kill arbitrary processes based on stale PIDs.

### 3. Verify the requested paths

1. Confirm the gateway responds at the preserved LAN URL; anonymous chat APIs must reject access in password mode.
2. Verify login and chat listing, recent history, older-page loading and live synchronization.
3. Use only an explicitly authorized test chat for real sends, creation, approvals or model calls. Do not spend model credits or mutate user chats merely to test a page.
4. For SSH, verify the existing desktop owner rather than replacing it with an unrelated CLI process.
5. For fixed HTTPS, verify the current instance with Check fixed entry and then verify from the phone's external network. Proxy, TLS and phone reachability are separate checks.
6. For ntfy, confirm both phone receipt and click-through to the intended chat. Server acceptance alone is insufficient.
7. State which checks were automated, simulated or performed on actual hardware.

### 4. Diagnose by layer

| Symptom | Check |
| --- | --- |
| Phone cannot reach LAN | Computer wake state, listening address/port, Wi-Fi isolation, VPN/routing, firewall |
| HTTP 502/504 | Proxy upstream, SSH connection and remote loopback port |
| Host/Origin rejection | Exact allowed HTTPS origin and preserved external Host header |
| Login keeps resetting | HTTPS throughout the external path, cookies preserved, caching disabled |
| Only saved history | Open the chat in the original desktop App and confirm owner connectivity |
| Tunnel fails but LAN works | Outbound network restrictions, executable path and tunnel logs |
| ntfy receives but chat link fails | Notification click URL, gateway availability and phone network reachability |
| Missing IPC/runtime | Correct OS/user, App version, explicit executable/endpoint overrides |

### 5. Final handoff to the user

Return concrete results, not just a process-start message:

```text
Status: running / partially configured / blocked
Phone URLs: clickable LAN and configured HTTPS URLs
Login: username and safe credential-retrieval method
Installation: directory, branch/commit, process and log locations
Connections: enabled profiles, server/NAS deployment location, known dependencies
Start / stop / restart: exact commands or App actions
Verified: local/SSH read, live update, send, model/Skill, approval, external access and ntfy — only those actually checked
Not verified / user steps: clear remaining actions and limitations
```

Do not include actual passwords, tokens or API keys in public issues, commits, screenshots or deployment bundles.

## Development and packaging

```sh
git switch feature/desktop-ntfy
npm ci
npm run desktop
```

For packaging, use a project-local Python environment on the target OS:

```sh
python -m pip install -r requirements-desktop.txt
python scripts/build-desktop.py
npm run pack:desktop
```

On Windows, `npm run build:windows` produces a portable executable. Development can use `CMB_DATA_DIR` for isolated gateway data and `CMB_PYTHON` for the Python executable. Packaged builds use their bundled runtime.

```sh
python -B -m unittest discover -s tests -v
npm run test:desktop
```

Report the OS, App/runtime version, branch/commit and redacted reproduction steps when opening an issue. Include logs without credentials or private chat content. Current verification boundaries are recorded in [VERIFICATION.md](VERIFICATION.md).

## Acknowledgements

Thanks to the [LINUX DO](https://linux.do/) community and its members for their support.

## License and references

[MIT License](LICENSE).

- [OpenAI Codex](https://github.com/openai/codex)
- [Cloudflare Tunnel documentation](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [ntfy documentation](https://docs.ntfy.sh/)
- [Docker Compose documentation](https://docs.docker.com/compose/)
- [Caddy documentation](https://caddyserver.com/docs/)
- [Nginx proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
