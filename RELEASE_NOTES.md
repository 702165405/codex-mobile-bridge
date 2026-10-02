## v1.2.1 · 消息编辑、会话分支与完整复制

### 本次更新

- **编辑最近消息**：用户消息下方选择“编辑并重新发送”，确认后由原 Codex 会话重新生成回答；任务运行中需要先停止。
- **编辑历史消息**：选择“编辑并新建分支”，保留原会话，在新会话中修改并重新生成选定轮次。
- **从这里分支**：在已完成的回答下创建新会话，只保留截至该轮的上下文，等待你继续输入。聊天详情可返回来源会话。
- **复制完整回复与代码**：复制 Markdown 原文或单独代码块，长回复自动取齐正文。浏览器限制剪贴板时提供选中文本的手动复制方式。
- **可靠的操作恢复**：过期消息会被拒绝，重复请求不重复执行；分支已创建但连接失败时保留修改草稿。中英文与手机窄屏同步适配。

编辑和分支不会自动撤销已经执行的文件修改或命令；新旧会话共享工作目录。分支沿用原主机、模型提供商与认证，创建分支本身不发送模型请求。分支需要 Codex 运行时支持指定轮次及目标续跑延迟，旧版会提示更新电脑 Codex App。

### 安装与升级

提供 Apple Silicon Mac arm64 DMG、Intel Mac x64 DMG、Windows x64 Setup.exe，以及三个 ZIP 更新包。

**v1.1.0 / v1.2.0 用户**：桌面 App → 应用更新 → 更新并重启，然后刷新网页。保留原数据目录即可保留登录、网络、通知和关注聊天配置；临时 HTTPS 地址可能随重启变化。

**Windows beta.7 / 1.0.0 用户**：在托盘选择“停止网关并退出”，再用 1.2.1 Setup.exe 安装到原位置，无需卸载或删除数据。beta.5 / beta.6 用户也需要手动安装一次新版。

Mac 包使用 ad-hoc 完整性签名，尚无 Apple Developer ID 签名与公证；Windows 包未做证书签名。使用 `SHA256SUMS.txt` 校验下载，应用内更新使用签名清单 `bridge-update.json`。

### English

**v1.2.1 adds message editing, conversation branches and complete copying.**

- **Edit and resend** the latest user message through the original desktop chat. Stop the active turn first.
- **Edit in new branch** for an older user message. Keep the original conversation and regenerate the selected turn in a new chat.
- **Branch from here** under a completed answer. Keep history through that turn, wait for the next message and return to the source through chat details.
- Copy complete Markdown replies or individual code blocks, including long content. When clipboard access is restricted, select the text and use the system Copy action.
- Stale messages are rejected, repeated requests do not replay operations, and an edit draft is retained if a new branch cannot connect. Chinese, English and narrow mobile layouts are supported.

Editing and branching do not undo previous file changes or commands. Both chats share the working directory. Branches retain the original execution host, model provider and authentication; creating a branch does not send a model request. Branching requires a Codex runtime with turn-specific forks and deferred goal continuation. Update Codex App if prompted.

**Installers:** Apple Silicon arm64 DMG, Intel x64 DMG and Windows x64 Setup.exe, plus ZIPs for all three targets.

**From v1.1.0 / v1.2.0:** App updates → Update and restart, then refresh the browser. Keep the data directory; temporary HTTPS addresses may change after restart.

**Older Windows versions:** beta.7 / 1.0.0 users should stop the gateway and quit, then install 1.2.1 at the same location. Beta.5 / beta.6 users also need one manual update. Do not uninstall or delete data.

Mac packages are ad-hoc signed but not Apple-notarized; Windows packages have no certificate signature. Verify downloads with `SHA256SUMS.txt`; in-app updates use the signed `bridge-update.json` manifest.
