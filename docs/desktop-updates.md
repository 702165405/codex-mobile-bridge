# 桌面版本发布与更新

## 用户操作

从 v0.2.0-beta.5 起，桌面 App 启动后及每 6 小时检查一次 GitHub Release。
在「应用更新」中检查新版、阅读说明，点击「更新并重启」。Beta 用户可接收后续 Beta、RC 和正式版；正式版只接收正式版。
旧版首次升级需要手动安装支持更新的版本。1.1.0 提供 Mac Apple Silicon（arm64）、Mac Intel（x64）和 Windows x64 安装包。Windows ARM 暂无发布包。

下载阶段网关保持在线。签名、哈希、解压路径和包版本检查通过后，App 才退出并停止网关。
应用在原位置替换，原本运行的网关随后恢复；原本停止的网关保持停止。
数据目录独立于应用目录，登录、网络、通知和关注聊天配置保持原样。
使用临时 Cloudflare HTTPS 时，重启后需使用最新地址。

安装目录需要当前用户可写，数据目录不能放在应用安装目录内部。
macOS 更新签名与 Apple Developer ID、公证是不同机制；该功能不会让应用获得 Apple 公证或 Windows 证书签名。

## 发布流程

1. 修改 `package.json` / `package-lock.json` 版本和 `RELEASE_NOTES.md`。
2. 在仓库 Actions Secret 中配置 `UPDATE_SIGNING_KEY`，值为与 `desktop/update-public-key.pem` 对应的 Ed25519 PKCS#8 PEM 私钥。私钥不提交、不放入构建产物，离线保存备份。不要重新生成公钥覆盖现有更新身份。
3. 运行单元测试及 Desktop builds 的三个目标：macOS arm64、macOS x64 和 Windows x64。Mac 网关与 Electron 在对应架构的 runner 上分别构建。验证 DMG 挂载、Applications 快捷方式、复制安装、ZIP 解压、架构及完整性签名、真实 App/网关重启、失败恢复和配置保留。
4. 推送与版本匹配的 `v…` tag。`Signed desktop release` 验证版本及签名身份，构建上述三个目标，上传完整的草稿 Release，最后发布。手动运行时须选择已存在的版本 tag；`publish=false` 只创建草稿。
5. 发布资产包括 macOS arm64 DMG/ZIP、macOS x64 DMG/ZIP、Windows x64 ZIP/Setup、`SHA256SUMS.txt` 和 `bridge-update.json`。DMG 为 Mac 首选安装包，ZIP 继续用于应用内更新；签名清单必须包含全部三个目标的 ZIP，校验文件同时包含 DMG、ZIP 和 EXE。验证 Release 内容及 App 检测结果。工作流不会覆写同名 Release；失败的草稿需检查原因后由维护者处理。

`bridge-update.json` 是签名信封：`payload` 为 JSON 原始字节的 Base64，`signature` 为其 Ed25519 签名。载荷包含 schema、版本、说明、平台文件名、大小和 SHA-256。App 内公钥验签后才接受文件信息，下载地址固定到本仓库 Release，拒绝降级与平台不匹配。

## 安装事务与恢复

私有 stdio 的 `update-prepare` 在应用所在目录建立 `.cmb-update-*`，校验并展开包、验证 macOS 签名和内置运行时，然后复制旧运行时作为独立更新进程。手机 HTTP 接口不提供更新操作。

`update-apply` 等原 App 退出后替换目录，等待新版 App 确认加载，并在需要时恢复网关。失败时尝试还原 `previous` 并启动原版本。Windows ZIP 覆盖更新保留已有卸载程序与快捷方式，并更新对应的当前用户卸载版本信息。

更新进程的工作目录必须是安装目录的父目录。Windows 会锁定进程的工作目录，即使更新程序本身已复制到临时目录，也无法移动仍被它用作工作目录的旧安装目录。不要将工作目录设为事务目录，避免重启后的 App 继承它，影响下次更新时的清理。

1.1.0 已修复更新器工作目录。此前发布的 beta.7 和 1.0.0 没有设置这一工作目录。从安装目录启动后，应用内更新可能出现 `WinError 32` 并恢复原版本。遇到这种情况，在托盘选择「停止网关并退出」，然后运行目标版本的 Windows `Setup.exe`，安装到原位置；不需要先卸载或删除网关数据。已安装的旧版本负责启动更新器，因此仅发布新版下载包无法修复旧版本本次更新的启动行为。

更新结果位于数据目录的 `desktop-update-result.json`，更新进程日志为 `desktop-update.log`。成功后保留一份旧应用，在下次更新准备时清理。失败的事务目录保留供恢复；异常断电、磁盘损坏或权限变化仍可能需要手动恢复 `previous`。这些情况下不要删除备份。
