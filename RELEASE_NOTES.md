## v1.2.2 · 网卡访问选择、PushPlus 与连接恢复

### 本次更新

- **按网卡选择局域网访问**：桌面 App → 网络与登录 → 仅选中的 IPv4 地址。选择需要的地址，排除 WSL、VMware 等虚拟网卡。未选地址不监听网关端口；先停止网关，保存后重新启动。IP 变化后需重新选择，不会自动开放其他网卡。
- **独立控制本机网页入口**：可关闭 127.0.0.1 / localhost 的网页访问，同时保留桌面控制与 HTTPS 隧道所需的内部回环连接。
- **项目入口**：桌面侧栏可直接打开 GitHub 项目主页、Issue 和 PR 页面。
- **PushPlus 通知**：桌面和网页都可保存 Token、发送测试通知，并为聊天开启提醒。接入需付费实名认证，最低 3.9 元；实名普通用户微信渠道每天 200 次请求、每分钟 5 次，相同内容每小时最多 3 条，失败请求也计入额度。详见[官方认证费用](https://www.pushplus.plus/center/real-auth?source=push)和[额度说明](https://www.pushplus.plus/doc/guide/use.html)。配置由网关共享。
- **聊天重命名**：点击聊天标题 → 修改聊天名称，支持本机和 SSH 聊天。
- **临时隧道自动恢复**：锁屏、休眠或网络变化后，失效的 Quick Tunnel 会重建；新地址会替换旧地址。修复连接成功后立即失效时延迟重连的问题。
- **更新恢复加固**：避免并发更新冲突，按安装路径隔离恢复事务，保留未确认恢复的旧版备份。

感谢 [@qybgh](https://github.com/qybgh) 的 [PR #1](https://github.com/try2love/codex-mobile-bridge/pull/1)，以及 [@702165405](https://github.com/702165405) 的 [PR #2](https://github.com/try2love/codex-mobile-bridge/pull/2)。

### 安装与升级

提供 Apple Silicon Mac arm64 DMG、Intel Mac x64 DMG、Windows x64 Setup.exe，以及三个 ZIP 更新包。

v1.1.0–v1.2.1 用户可在「应用更新」选择「更新并重启」，然后刷新手机网页。保留数据目录即可保留登录、网络、通知和关注聊天配置。默认保持原有全部网卡访问；按需改成地址选择。临时 HTTPS 地址可能随重启变化。

Windows beta.7 / 1.0.0 用户请在托盘选择「停止网关并退出」，用 1.2.2 Setup.exe 安装到原位置；beta.5 / beta.6 也需手动升级。若旧更新器提示 `gateway.stop` 访问被拒绝，请退出后覆盖安装，无需删除数据。

Mac 包使用 ad-hoc 完整性签名，尚无 Apple Developer ID 签名与公证；Windows 包未做证书签名。使用 `SHA256SUMS.txt` 校验下载，应用内更新使用签名清单 `bridge-update.json`。

### English

**v1.2.2 adds adapter selection, PushPlus notifications, chat renaming, and connection recovery.**

- Choose individual IPv4 addresses under Network & login. Exclude WSL/VMware adapters; unselected addresses do not listen on the gateway port. Stop, save and restart to apply. Reselect after IP changes; there is no fallback to all adapters.
- Turn local browser access at 127.0.0.1 / localhost off independently. Desktop control and HTTPS tunnels retain an internal loopback connection.
- Open the project home, issues and pull requests from the desktop sidebar.
- Configure and test PushPlus on desktop or web, then enable per-chat reminders. Real-name verification starts at CNY 3.90. Ordinary verified users receive 200 WeChat requests/day and 5/minute, with 3 identical messages/hour; failed requests count. See the official links above. Settings are shared across the gateway.
- Rename local and SSH chats from chat details.
- Recreate expired Quick Tunnels after sleep or network loss, including loss immediately after registration. Use the replacement URL.
- Isolate updater recovery by installation and preserve backups when recovery is unresolved.

Thanks to @qybgh (#1) and @702165405 (#2).

**Installers:** Mac arm64/x64 DMGs, Windows x64 Setup.exe, and ZIPs for all three targets. From v1.1.0–v1.2.1, use App updates → Update and restart, then refresh the browser. Existing settings and data are retained; all-adapter access remains the default. Older Windows clients should stop and quit, then install 1.2.2 over the existing location. Keep the data directory.

Mac packages are ad-hoc signed, not Apple-notarized; Windows packages have no certificate signature. Verify downloads with `SHA256SUMS.txt`; in-app updates use the signed `bridge-update.json` manifest.
