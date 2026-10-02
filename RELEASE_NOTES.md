## v0.2.0-beta.7

- 修复应用内更新查询 GitHub 版本列表时的 HTTP 415 错误；版本列表与安装包分别使用正确的请求格式。
- 包含 beta.6 的全部手机通知与登录设备功能：
- 新增可配置登录有效期（默认 12 小时，0 表示不自动过期），网关重启保留登录；修改认证设置后重启会要求重新登录。
- 新增电脑端登录设备列表，显示 IP、浏览器和最近访问；支持撤销登录、封禁 IP，以及立即生效的 IP 白名单和黑名单。局域网与可信代理均可识别访问来源。
- macOS 与 Windows 同步新增 Bark 推送。iPhone 可使用 Bark，Android 继续使用 ntfy；两个通道可分别启用，也可同时接收提醒。
- Bark 与 ntfy 提供独立的配置、测试通知和发送状态，支持 Bark 官方服务及自建服务。使用 Bark 手机首页推送地址中的 Device Key，保存后密钥不回显。
- 每个已订阅会话可单独选择“运行完成后通知”，默认关闭。仅提醒当前正在运行及之后正常完成的任务；失败、手动停止和开启前已完成的历史不通知。
- 两个通道分别去重和重试；一个通道失败不影响另一个。新增接收设备不补发已完成历史，原有 ntfy 配置、投递记录和会话订阅保留。
- 通知默认只包含状态，可按需显示聊天标题；点击通知返回对应会话。局域网链接需手机与电脑处于同一网络。

**beta.5 / beta.6 用户请从本页手动下载安装一次。** 旧版更新检查会遇到 HTTP 415，无法靠发布新版自动修复旧客户端。先停止网关并退出 App，再替换程序，保留原数据目录和配置。beta.7 修复该问题，后续可使用“应用更新”。使用临时 HTTPS 时，请重新打开最新地址。

### English

- Fix HTTP 415 when checking GitHub releases: metadata requests now accept JSON while asset downloads request bytes. Includes all beta.6 notification and device-management features.
- Configurable login validity (12 hours by default; 0 disables automatic expiry), preserved across gateway restarts. Authentication-setting changes invalidate prior logins on restart.
- Desktop login-device list with IPs, browser identifiers and last activity; revoke logins, block IPs and apply allow/block lists immediately. MAC addresses are not used; IP rules affect devices sharing an address.
- Bark notifications are available on both macOS and Windows. Use Bark for iPhone, ntfy for Android, or enable both channels.
- Each channel has separate settings, a test button, delivery status, deduplication and retries. Official and self-hosted Bark servers are supported; saved keys are hidden.
- Each watched chat can opt into successful-run completion notifications. The option is off by default and excludes failed, stopped and previously completed runs.
- Existing ntfy settings, delivery records and chat subscriptions are preserved. New destinations do not replay completed history.
- **beta.5 / beta.6 users must manually install this release once** because their update checker sends an unsupported Accept header. Stop the gateway and quit the App before replacing it; preserve the data directory. Subsequent updates can use the fixed in-app updater. LAN notification links require the phone to be on the same network.
