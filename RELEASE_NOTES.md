## v1.3.2 · 网关启动与入口通知

### 本次更新

- **每次启动都发送入口**：在电脑 App“手机通知 → 网关启动与入口通知”开启并保存后，每次启动网关都会向已启用的 PushPlus、Bark、ntfy 通道发送当前地址，即使地址没有变化。支持可选网关名称。
- **汇总多种访问方式**：包含已启用的局域网、NAS / 已有反代固定域名、自有服务器（SSH 转发建立后）及临时 HTTPS（隧道就绪后）。过滤关闭的网卡、关闭的入口和回环地址；局域网需要手机在同一网络，固定域名需要完成部署。
- **入口变化后补发**：稍晚就绪的隧道或地址变化会更新通知。同一次运行内去重，各通道独立退避重试；只发送当前入口，停止网关或关闭通知后不再开始新发送。
- **设置与测试**：提供“发送当前入口测试通知”和应用更新前的配置提示。入口通知默认关闭，与聊天提醒独立，不包含密码或登录令牌，也不沿用聊天通知的固定跳转地址。

### 使用与升级

先配置并启用至少一个推送通道，打开入口通知开关并保存；启动网关，再发送测试通知，以手机实际收到为准。通知服务接受请求不等于手机已收到，电脑断网或网关启动失败时无法保证送达。

Mac / Windows v1.1.0–v1.3.1 用户可在“应用更新”选择“更新并重启”。旧版尚不支持入口通知，首次升级后需要开启并测试，后续重启才能自动通知新入口。较老 Windows 版本请先停止网关并退出，再将 Setup.exe 安装到原位置。Linux 仍为实验性支持，请停止网关并退出后手动升级。保留数据目录即可保留原有配置。

提供 Mac Apple Silicon / Intel 的 DMG、ZIP，Windows x64 的 Setup.exe、ZIP，以及 Ubuntu x64 / ARM64 的 DEB、AppImage。Mac 包采用 ad-hoc 完整性签名，未获 Apple 公证；Windows 包未做证书签名。可用 `SHA256SUMS.txt` 校验下载；Mac / Windows 应用内更新使用签名的 `bridge-update.json`。

---

## English

**v1.3.2 sends gateway access addresses on startup and when entries change.**

- Enable **Phone notifications → Gateway startup and entry notifications** in the desktop App. Every gateway start sends the current addresses through enabled PushPlus, Bark and ntfy channels, even when unchanged. An optional gateway name identifies the host.
- Include enabled LAN addresses, NAS / existing reverse-proxy domains, personal servers once SSH forwarding connects, and temporary HTTPS once ready. Disabled interfaces, disabled entries and loopback addresses are excluded. LAN links require the same network; fixed domains require completed deployment.
- Send updates when an entry becomes ready later or its address changes. Deduplicate within one run and retry each channel independently with backoff. New sends stop when the gateway stops or the switch is disabled.
- Test current-entry delivery and check configuration before updating. Entry notifications are off by default, independent of chat alerts, contain no passwords or login tokens, and use current entries instead of the chat click URL.

**Setup:** enable and save at least one push channel and the entry notification switch, start the gateway, then send a test and confirm receipt on your phone. Service acceptance does not guarantee phone receipt; delivery requires a working gateway and network.

**Upgrade:** Mac / Windows v1.1.0–v1.3.1 users can choose App updates → Update and restart. Earlier versions do not support entry notifications; configure and test after this first upgrade before relying on them for later restarts. Older Windows clients should stop and quit before installing over the existing location. Linux requires a manual upgrade after stopping and quitting. Keep the data directory to retain settings.

Packages include Mac arm64/x64 DMG and ZIP, Windows x64 installer and ZIP, and experimental Ubuntu x64/ARM64 DEB and AppImage. Mac packages are ad-hoc signed, not Apple-notarized; Windows packages have no certificate signature. Verify downloads with `SHA256SUMS.txt`; Mac/Windows in-app updates use the signed manifest.
