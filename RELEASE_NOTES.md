## v1.2.0 · 跨设备工作模式、附件与会话管理

### 本次更新

- **网页开启计划与目标模式**：在发送栏选择普通、计划或目标模式。计划生成后，可直接在网页阅读、执行或提交修改意见；目标经原生状态确认后显示进度。
- **更紧凑的发送栏**：工作模式位于发送方式与发送按钮之间。目标栏可隐藏，并通过发送旁的“目标进度”重新显示；长模型名自动省略，推理强度保持可见，模型面板可查看完整 ID。
- **多个文件和图片一起发送**：附件入口位于发送方式左侧，支持多选、移除和失败重试，草稿按聊天保存。每条消息最多 10 个附件，单个不超过 20 MiB，合计不超过 100 MiB；本机与 SSH 聊天使用各自的附件目录。
- **一眼查看多会话状态**：绿点表示运行中，蓝点表示完成后未查看，橙点表示失败或停止，灰点表示运行状态暂时失联。进入聊天后清除完成标识，也可在显示设置中关闭。
- **电脑端管理通知监控**：“手机通知 → 已关注聊天”可查看对应会话、主机与目录，切换完成通知，或删除所选监控。新增关注仍从网页聊天中操作。
- **官方登录 Fast 开关**：账号、模型和工作区支持时，模型面板可开启或关闭 Fast，从下一轮生效；API、自定义提供商和不支持的环境不会显示可用开关。Fast 的速度与用量规则以原 Codex 服务为准。
- **双语产品介绍与演示**：官网补齐功能说明、跨设备使用流程与约两分钟的实际界面演示，支持中英文切换、双语字幕和章节跳转。

这些功能继续使用原 Codex App 会话的上下文、主机、模型与认证。复杂 MCP 表单和部分特殊审批仍需在桌面处理。目标暂停、恢复与预算管理尚无独立网页控件；附件副本保留在数据目录，当前不自动清理。

### 安装包

- **Intel Mac（英特尔）**：`Codex-Mobile-Bridge-1.2.0-macOS-x64.dmg`
- **Apple Silicon Mac（M 系列）**：`Codex-Mobile-Bridge-1.2.0-macOS-arm64.dmg`
- **Windows x64**：`Codex-Mobile-Bridge-1.2.0-Windows-x64-Setup.exe`

Mac 打开 DMG 后，将 App 拖入“应用程序”。三个平台同时提供 ZIP，用于应用内更新或手动替换；Windows ZIP 需完整解压后运行。`SHA256SUMS.txt` 提供六个安装/更新包的校验值，`bridge-update.json` 提供签名更新清单。

### 升级说明

**v1.1.0 用户**可在桌面 App 的“应用更新”中检查新版并选择“更新并重启”。登录、网络、通知和关注聊天配置保持原样。升级后刷新网页，加载新的功能入口；临时 HTTPS 地址可能随网关重启变化。

**Windows beta.7 / 1.0.0 用户**建议手动覆盖升级一次：在托盘选择“停止网关并退出”，再用 **1.2.0 Setup.exe** 安装到原位置；无需卸载或删除数据。旧更新器可能遇到 `WinError 32`，1.1.0 起已修复后续更新的目录占用问题。beta.5 / beta.6 的更新检查存在 HTTP 415 问题，也需要手动安装一次新版。

Mac 包具有 ad-hoc 完整性签名，尚无 Apple Developer ID 签名与公证；Windows 包未做证书签名。首次打开说明见 [README](https://github.com/try2love/codex-mobile-bridge#macos-first-launch)。Windows ARM 暂无专用安装包。

### English

**v1.2.0 adds browser work modes, attachments and richer chat management.**

- Start Plan or Goal mode from the composer. Read a generated plan, implement it or request changes in the browser. Goal progress appears after confirmation from the native chat state.
- Keep the work-mode selector beside Send. Hide and restore the goal bar without changing the goal. Long model names truncate while reasoning effort stays visible; the model panel shows the full ID.
- Attach multiple files and images, remove selections and retry failed uploads. Drafts stay with their chat. Limits: 10 attachments per message, 20 MiB per file and 100 MiB total, with separate local and SSH storage.
- Track concurrent chats: green for running, blue for completed and unread, orange for failure or interruption, and gray when a running chat loses its connection. Opening a chat clears its completion marker; indicators can be disabled in display settings.
- Manage followed chats from the desktop notification settings: inspect the chat and host, toggle completion alerts or remove a watch. Add new watches from the browser chat.
- Toggle Fast for official-login chats when the account, model and workspace allow it. Changes apply to the next turn. API/custom providers and unsupported environments do not expose an available toggle; speed and usage follow the original Codex service.
- Explore the bilingual product website, full feature list and a chaptered walkthrough with Chinese and English subtitles.

Existing chat context, execution host, model and authentication are retained. Complex MCP forms and some special approvals still need the desktop. Goal pause/resume and budget controls do not yet have dedicated browser buttons. Uploaded copies remain in the data directory without automatic cleanup.

**Installers:** Intel Mac x64 DMG, Apple Silicon arm64 DMG and Windows x64 Setup.exe, plus ZIPs for all three targets. Verify downloads against `SHA256SUMS.txt`.

**Upgrading from v1.1.0:** use App updates → Update and restart. Keep your existing data directory and refresh the browser afterward. Temporary HTTPS addresses may change after restart.

**Windows beta.7 / 1.0.0:** stop the gateway and quit from the tray, then install 1.2.0 at the same location without uninstalling or deleting data. The older updater may encounter `WinError 32`; the fix shipped in 1.1.0 applies to subsequent updates. Beta.5 / beta.6 users also need one manual upgrade because of their HTTP 415 update-check issue.

Mac packages are ad-hoc signed but not Apple-notarized; Windows packages have no certificate signature. Windows ARM packages are not available.
