## v1.0.0

Codex Mobile Bridge 1.0.0 正式版。

### 安装包

- **Intel Mac（英特尔）**：`Codex-Mobile-Bridge-1.0.0-macOS-x64.dmg`
- **Apple Silicon Mac（M 系列）**：`Codex-Mobile-Bridge-1.0.0-macOS-arm64.dmg`
- **Windows x64**：`Codex-Mobile-Bridge-1.0.0-Windows-x64-Setup.exe`

Mac 打开 DMG 后，将 App 拖入“应用程序”即可安装。三个平台同时提供 ZIP，供应用内更新或手动替换；Windows ZIP 也可完整解压后直接运行。`SHA256SUMS.txt` 包含所有安装包的校验值，`bridge-update.json` 提供签名更新清单。

### 本次更新

- 新增 Intel Mac 支持，两种 Mac 均提供 DMG 安装包与对应架构的网关运行时。
- 官方 ChatGPT 账号可在电脑和手机查看剩余额度、恢复时间及可用重置卡。使用重置卡需桌面授权并逐次确认；中断后沿用原请求重试，避免重复消费。
- 手机首次进入自动读取账号状态，显示加载提示，临时失败会自动重试。API 接入显示不可点击的状态文字，未登录和读取失败有独立提示。
- 手机图标、名称和语言选择合并到顶部，收拢列表工具与底栏入口，扩大聊天列表的可滚动区域。
- 保留本机及 SSH 聊天、模型与 Skill 选择、待确认操作、Bark / ntfy 通知、登录有效期、设备管理和应用内更新。

### 升级说明

beta.7 用户可通过“应用更新”升级；beta.5 / beta.6 的更新检查存在 HTTP 415 问题，请手动安装一次 1.0.0。手动替换前停止网关并退出 App，保留原数据目录。临时 HTTPS 连接在重启后可能变化，请以 App 显示的最新地址为准。

Mac 包使用 ad-hoc 完整性签名，尚无 Apple Developer ID 签名与公证；Windows 包未做证书签名。首次打开说明见 [README](https://github.com/try2love/codex-mobile-bridge#macos-first-launch)。Windows ARM 暂无专用安装包。

### English

**Codex Mobile Bridge 1.0.0 is the first stable release.**

- Installers: Intel Mac x64 DMG, Apple Silicon arm64 DMG, and Windows x64 Setup.exe. All three targets also include ZIP packages for updates or manual replacement.
- Native ChatGPT accounts can view remaining usage, reset times and available usage resets on desktop and phone. Consuming a reset requires desktop permission and explicit confirmation; retries retain the original request ID.
- The phone automatically loads account status and retries temporary failures. API connections show non-clickable status text, with separate states for signed-out accounts and unavailable status.
- A shared top bar for the logo, name and language selector, with consolidated list controls and footer, leaves more space for scrolling.
- Includes existing local/SSH chats, model and Skill selection, approvals, Bark/ntfy notifications, login validity, device management and in-app updates.

Beta.7 users can update in the App. Beta.5 / beta.6 users should manually install 1.0.0 once due to the older HTTP 415 update-check issue. Preserve the data directory and stop the gateway before manual replacement. Temporary HTTPS addresses may change after restarting.

Mac packages are ad-hoc signed but not Apple-notarized; Windows packages have no certificate signature. Windows ARM packages are not available. Verify downloads against `SHA256SUMS.txt`.
