## v1.3.4 · Quick Tunnel、更新链路与图片预览修复

### 本次更新

- 修复 Quick Tunnel 在 Cloudflare API 重试时被提前判定失败的问题；`api.trycloudflare.com` 不再被误认为隧道入口。
- 更新链路支持 GitHub 镜像兜底，检查更新状态更明确；更新助手可靠停止自有网关，并保留 App/网关启动诊断和历史结果。
- 手机网页可渲染模型回答中的本地图片嵌入和 `ImageView` 预览；兼容 `ImageView` / `imageView` 命名，保留保存历史证据，并移除重复文件名与空占位。模型 Markdown 图片仍限制在会话工作区，只有运行时 `ImageView` 证据允许桌面图片特例。自定义模型优先读取运行时原生模型目录，模型列表和推理强度与桌面端一致。进入会话后模型与 Skill 独立异步加载；Skill 使用按 `CODEX_HOME + cwd` 分桶的 SQLite 缓存，支持搜索、分页、已选项补全、虚拟滚动、后台刷新和发送前内容一致性校验。

**English**

**v1.3.4 fixes Quick Tunnel startup, the in-app update path, and model image previews.** GitHub requests can use a restricted mirror fallback while manifest signatures and archive hashes stay authoritative. The update helper reliably stops only the managed gateway and preserves launch diagnostics. Mobile web renders embedded model images and runtime ImageView previews while keeping model-authored Markdown images inside workspace roots. Skill catalogs now use per-CODEX_HOME/cwd SQLite buckets with search, pagination, selected-item hydration, virtual scrolling, background refresh, and send-time consistency checks.

---


## v1.3.3 · 能耗与通知优化

- **按需监控会话**：持续监听运行中和等待处理的会话，结束后解除通知订阅；通过实时事件和每 30 秒的变更元数据检查发现新任务，减少反复读取历史会话。
- **通知重试更独立**：完成通知发送失败时保留重试记录，不必持续订阅已结束会话。补齐会话再次运行、断线恢复、通知开关变化及完成状态先后到达的处理。
- **优化能耗**：状态查询复用进程；窗口隐藏时暂停刷新，状态不变时不重绘界面。
- **稳定窗口标题**：统一管理原生窗口标题，避免页面标题反复覆盖；切换语言时按需更新。

Mac / Windows v1.1.0 及后续版本可在“应用更新”选择“更新并重启”。保留数据目录即可保留登录、网络和通知设置。通过临时 HTTPS 远程升级前，请确认 v1.3.2 引入的“网关启动与入口通知”已开启且手机能够收到。Linux 仍为实验性支持，请停止网关并退出 App 后手动安装新版。

提供 Mac Apple Silicon / Intel 的 DMG、ZIP，Windows x64 的 Setup.exe、ZIP，以及 Ubuntu x64 / ARM64 的 DEB、AppImage。使用 `SHA256SUMS.txt` 校验下载；Mac / Windows 应用内更新使用签名的 `bridge-update.json`。Mac 仍为 ad-hoc 签名、未公证；Windows 无证书签名。

---

## English

**v1.3.3 reduces notification monitoring and desktop refresh overhead.**

- Monitor running chats and pending requests, then release notification subscriptions when idle. Live events and a metadata check every 30 seconds discover new activity without repeatedly loading historical chats.
- Retry failed completion alerts independently of subscriptions. Handle resumed chats, reconnection, notification preference changes, and completion updates arriving in separate steps.
- Reuse the desktop status worker, pause refreshes while the window is hidden, and skip unchanged UI renders.
- Keep the native window title stable and update it only when the language changes.

**Upgrade:** Mac / Windows v1.1.0 and later support App updates → Update and restart. Retain the data directory to preserve settings. Before a remote update over temporary HTTPS, enable and test the startup and entry notifications introduced in v1.3.2. Linux remains experimental and requires a manual upgrade after stopping the gateway and quitting the App.

Packages include Mac arm64/x64 DMG and ZIP, Windows x64 installer and ZIP, and Ubuntu x64/ARM64 DEB and AppImage. Verify downloads with `SHA256SUMS.txt`; Mac/Windows in-app updates use the signed manifest. Mac builds are ad-hoc signed, not notarized; Windows builds have no certificate signature.
