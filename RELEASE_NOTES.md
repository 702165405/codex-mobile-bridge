## v1.3.0 · 多账号、API 接入与 Ubuntu 支持

### 本次更新

- **账号与接入**：仅在 Bridge 桌面端添加多个官方 ChatGPT 账号、自定义 API，或扫描导入本机已有配置；桌面端与 Web 均可切换已保存接入。切换会重启官方 Codex 桌面应用，Bridge 网关保持运行。运行中、等待确认或发送结果未确定的任务会阻止切换；失败时恢复原配置。
- **额度集中展示**：当前接入置顶并禁用重复切换，官方账号旁紧凑显示剩余额度和重置卡数量。首次打开自动查询，页面可见时每五分钟更新；点击“查看剩余额度”立即查询并重新计时。重置操作保留单独确认。
- **API 配置与模型**：支持获取上游模型和手动输入模型 ID；修复切换 API 后仍向官方地址请求的问题。网页聊天按原提供商和执行主机读取上游模型，旧模型不在列表时提示重新选择并应用；查询失败不会退回默认 GPT 列表。上游仍需兼容 Responses API。
- **Computer use 授权**：网页根据桌面实际请求显示支持的授权范围，并传递“允许此对话”或“始终允许”的选择，避免把持续授权错误处理为单次允许。
- **Ubuntu x64 / ARM64（实验性）**：新增原生 `.deb` 与 AppImage，适配运行时发现、桌面启动和网络接口选择。各架构由原生 CI 验证安装、启动、登录、通知和隔离网关；具体 Codex 桌面版本的 IPC、Wayland/FUSE 与其他发行版仍需实机验证。
- **界面与切换修复**：网页固定显示当前接入入口，移除桌面“反馈问题”和“贡献代码”按钮；修复局域网 HTTP 下手机切换未发出请求，以及断开的旧任务状态误阻止切换。

### 安装与升级

提供 Apple Silicon / Intel Mac 的 DMG 和 ZIP、Windows x64 的 Setup.exe 和 ZIP，以及 Ubuntu x64 / ARM64 的 `.deb` 和 AppImage。Windows ARM 和 32 位 Linux ARM 不在支持范围内。

Mac / Windows v1.1.0–v1.2.2 用户可在“应用更新”选择“更新并重启”，随后刷新网页。较老的 Windows 版本请先停止网关并退出，再用新版 Setup.exe 安装到原位置。Linux 暂不支持应用内更新，请停止网关并退出 App 后手动安装或替换 AppImage。保留数据目录即可保留登录、网络、通知、关注聊天和已保存接入。

Mac 包采用 ad-hoc 完整性签名，未获 Apple 公证；Windows 包未做证书签名。下载可用 `SHA256SUMS.txt` 校验，Mac / Windows 应用内更新使用签名的 `bridge-update.json`。

账号凭据保存在本机受权限保护的文件中。添加和管理凭据不开放给 Web；切换不修改聊天历史。使用说明见[账号与接入](https://github.com/try2love/codex-mobile-bridge/blob/v1.3.0/docs/account-switching.md)和 [Linux 文档](https://github.com/try2love/codex-mobile-bridge/blob/v1.3.0/docs/linux.md)。

---

## English

**v1.3.0 adds multiple accounts, custom API connections and experimental Ubuntu support.**

- Add official ChatGPT accounts, custom APIs or existing local configurations in the Bridge desktop app. Switch saved connections on desktop or Web. Switching restarts Codex while keeping the Bridge gateway online; active tasks and pending approvals block switching, and failures restore the previous configuration.
- Keep the active connection first and show official quota and reset credits beside each account. Quota loads on first opening, refreshes every five minutes while visible, and resets its timer after a manual query.
- Discover upstream API models or enter an ID manually. API requests use the configured endpoint; Web chat model pickers use the chat's upstream instead of the default GPT catalog. An unavailable old model requires an explicit selection and Apply. The upstream must support the Responses API.
- Preserve supported computer-use approval scopes, including conversation and persistent approval.
- Ship native Ubuntu x64/ARM64 DEB and AppImage packages, with native CI installation and isolated gateway checks. Linux remains experimental; specific Codex desktop IPC, Wayland/FUSE and other distributions need real-device testing.
- Fix mobile switching over LAN HTTP and stale disconnected-task blockers; keep account controls and quota in a compact, consistent location.

**Upgrade:** Mac/Windows v1.1.0–v1.2.2 users can choose App updates → Update and restart, then refresh the browser. Older Windows clients should stop and quit before installing over the existing location. Linux requires a manual upgrade after stopping the gateway and quitting the app. Keep the data directory to retain settings and saved connections.

Mac packages are ad-hoc signed, not Apple-notarized; Windows packages have no certificate signature. Verify downloads with `SHA256SUMS.txt`; Mac/Windows in-app updates use the signed manifest. Windows ARM and 32-bit Linux ARM are not included.
