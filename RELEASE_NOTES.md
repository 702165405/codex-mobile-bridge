## v1.3.1 · 手机端目标控制与交互优化

### 本次更新

- **目标模式**：本机聊天支持创建、暂停、恢复、修改和关闭原生 Goal，同步目标状态及 Token 用量。减少创建与暂停前的历史读取等待；操作按钮集中在同一行。修改前须暂停，保存后保持暂停并保留预算、重置用量。暂停或关闭目标不会中断当前回复，需要立即中断时使用“停止”。SSH 聊天暂不支持目标控制。
- **计划与消息模式**：保留“发送新消息、完成后发送、补充当前任务”和“普通模式、计划模式、目标模式”。有修改意见时突出“按照修改意见继续规划”，禁用“按照当前规划结果执行计划”，避免忽略意见直接执行。
- **手机布局与附件**：上传、消息类型、工作模式、目标入口和发送按钮在窄屏中保持同一行；支持附件预览，修复草稿、排队消息及附件保留问题。
- **列表与历史**：保留按项目或最近交互展示，交互后及时更新最近排序；较早会话优先读取最近完整轮次，向上按需加载，减少首次等待。
- **通知与主页设置**：分别控制全会话待处理请求和运行完成通知，单一会话可继承全局设置或独立开启、关闭。会话提醒打开该会话的设置；主页 PushPlus 通知和账户管理入口可分别显示或隐藏。通知仍需配置并启用相应通道。

### 贡献致谢

感谢 [@qybgh（Luoran Yau）](https://github.com/qybgh) 在 [PR #8](https://github.com/try2love/codex-mobile-bridge/pull/8) 中对移动端计划、目标模式、附件预览和界面体验的贡献。本版本保留原始提交，并在此基础上完成审阅修订与用户验收优化。

### 安装与升级

提供 Apple Silicon / Intel Mac 的 DMG 和 ZIP、Windows x64 的 Setup.exe 和 ZIP，以及 Ubuntu x64 / ARM64 的 `.deb` 和 AppImage。

Mac / Windows v1.1.0–v1.3.0 用户可在“应用更新”选择“更新并重启”，随后刷新网页。较老的 Windows 版本请先停止网关并退出，再用新版 Setup.exe 安装到原位置。Linux 仍为实验性支持，暂不支持应用内更新；停止网关并退出 App 后手动安装或替换 AppImage。保留数据目录即可保留登录、网络、通知和已保存接入。

Mac 包采用 ad-hoc 完整性签名，未获 Apple 公证；Windows 包未做证书签名。下载可用 `SHA256SUMS.txt` 校验，Mac / Windows 应用内更新使用签名的 `bridge-update.json`。Goal 能力依赖本机 Codex 桌面运行时支持。

---

## English

**v1.3.1 improves mobile Goal controls, planning, attachments and chat notifications.**

- Create, pause, resume, edit and close native goals in local chats, with confirmed state and token usage. Goal controls share one action row and avoid unnecessary full-history reads. Editing requires a paused goal, retains its budget, resets usage and leaves it paused. Pause/close does not stop an active reply; use Stop. Goal controls are not yet supported for SSH chats.
- Retain the existing send/queue/steer choices and Default/Plan/Goal modes. Revision feedback highlights further planning and disables immediate implementation, preventing feedback from being ignored.
- Keep upload, send type, work mode, Goal access and Send on one row on narrow screens. Improve attachment previews and preserve attachments across drafts and queues.
- Retain project/recent grouping, update recency after interaction, and load recent complete turns first for older chats, with older history available on demand.
- Configure request and completion notifications independently, with global defaults and per-chat overrides. Choose whether PushPlus and account shortcuts appear on the home page. Notification channels still require configuration and activation.

**Thanks to [@qybgh (Luoran Yau)](https://github.com/qybgh) for [PR #8](https://github.com/try2love/codex-mobile-bridge/pull/8).** This release preserves the original contribution and adds reviewed, user-tested refinements.

**Upgrade:** Mac/Windows v1.1.0–v1.3.0 users can choose App updates → Update and restart, then refresh the browser. Older Windows clients should stop and quit before installing over the existing location. Linux requires a manual upgrade after stopping the gateway and quitting the app. Keep the data directory to retain settings and saved connections.

Packages include Mac arm64/x64 DMG and ZIP, Windows x64 installer and ZIP, and experimental Ubuntu x64/ARM64 DEB and AppImage. Mac packages are ad-hoc signed, not Apple-notarized; Windows packages have no certificate signature. Verify downloads with `SHA256SUMS.txt`; Mac/Windows in-app updates use the signed manifest. Goal support depends on the local Codex desktop runtime.
