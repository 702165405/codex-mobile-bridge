## v1.1.0 · 移动端界面升级与个性化显示

### 本次更新

- **更宽敞的聊天页面**：手机对话详情隐藏品牌与语言顶栏，压缩标题区域。点击标题可查看完整名称、项目、运行设备与模型信息。
- **输入区一键收起**：模型、提醒、Skill 右侧的箭头可隐藏输入框与发送/停止栏，保留草稿和工具入口。发送成功不会自动收起，发送失败会重新展开错误提示。
- **选择想看的内容**：“显示设置”中可独立开关思考摘要与执行过程。执行过程包含命令、工具调用、文件变更及中途进度说明；关闭两项可专注回复。用户消息、错误、审批与问题卡片仍显示；缺少阶段标记的旧回复继续保留。
- **按习惯调整外观**：浅色、深色、跟随系统，自定义强调色、正文字号、代码字号和阅读间距。设置即时生效，保存在当前浏览器，电脑网页同样支持。
- **阅读位置保持**：收起输入区、切换内容显示与加载历史时尽量保持当前阅读位置；隐藏的过程更新不会触发新内容提示。
- **Windows 更新修复**：修复更新器工作目录占用安装目录、导致 `WinError 32` 和恢复旧版本的问题。

### 安装包

- **Intel Mac（英特尔）**：`Codex-Mobile-Bridge-1.1.0-macOS-x64.dmg`
- **Apple Silicon Mac（M 系列）**：`Codex-Mobile-Bridge-1.1.0-macOS-arm64.dmg`
- **Windows x64**：`Codex-Mobile-Bridge-1.1.0-Windows-x64-Setup.exe`

Mac 打开 DMG 后，将 App 拖入“应用程序”即可安装。三个平台同时提供 ZIP，用于应用内更新或手动替换；Windows ZIP 可完整解压后直接运行。`SHA256SUMS.txt` 提供安装包校验值，`bridge-update.json` 提供签名更新清单。

### 升级说明

**Windows beta.7 / 1.0.0 用户建议手动覆盖升级一次。**旧版本负责启动本次更新器，仍可能触发目录占用。请在托盘选择“停止网关并退出”，再运行 **1.1.0 Setup.exe**，安装到原位置；无需卸载或删除网关数据。1.1.0 中的修复用于后续更新。

beta.5 / beta.6 的更新检查存在 HTTP 415 问题，需要手动安装一次新版。其他支持更新的版本可在“应用更新”中检查新版。手动替换前停止网关并退出 App，保留原数据目录。临时 HTTPS 地址可能随重启变化；更新后刷新手机网页以加载新界面。

Mac 包具有 ad-hoc 完整性签名，尚无 Apple Developer ID 签名与公证；Windows 包未做证书签名。首次打开说明见 [README](https://github.com/try2love/codex-mobile-bridge#macos-first-launch)。Windows ARM 暂无专用安装包。

### English

**v1.1.0 brings a roomier mobile chat interface and customizable display settings.**

- Compact navigation; tap the title to view full chat details.
- Manually hide or show the composer beneath Model, Notifications and Skill without losing the draft. Failed sends reveal the error; successful sends do not auto-collapse.
- Independently show or hide reasoning summaries and execution activity, including tools, commands, file changes and progress updates. Replies, user messages, errors and pending requests remain available. Older replies without phase metadata are retained.
- Light, dark or system theme, custom accent colors, text/code sizes and reading spacing, saved per browser on phone and desktop.
- Preserve the reading position while changing the layout or loading history. Hidden activity does not trigger a new-content alert.
- Fix the Windows updater working-directory lock that caused `WinError 32` and rollback.

**Installers:** Intel Mac x64 DMG, Apple Silicon arm64 DMG, Windows x64 Setup.exe, plus ZIPs for all three targets. Verify downloads against `SHA256SUMS.txt`.

**Windows beta.7 / 1.0.0:** stop the gateway and quit from the tray, then run the 1.1.0 installer at the same location. Preserve your data; uninstalling is unnecessary. The old app launches the updater for this upgrade, so the new fix applies to subsequent updates. Beta.5 / beta.6 users also need one manual upgrade due to the older HTTP 415 update-check issue. Refresh the phone page after updating.

Mac packages are ad-hoc signed but not Apple-notarized; Windows packages have no certificate signature. Windows ARM packages are not available.
