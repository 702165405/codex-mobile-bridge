# 安卓首版测试结果

验证日期：2026-10-05。上游 v1.3.2，基准提交 `38e61873b6790a5226b0d872411f6afa5b0b85b8`。

## 环境

macOS 13.7.7 / Intel，JDK 17，Gradle 8.11.1，Android SDK 35 / Build Tools 35.0.0。安卓模拟器为 Android 11（API 30）x86_64；模拟器、SDK 和构建缓存使用项目临时目录。

APK：版本 1.0.0 / versionCode 1，包名 `io.github.codexmobilebridge.android`，minSdk 26 / targetSdk 35。包括 arm64-v8a、armeabi-v7a、x86、x86_64。

## 自动检查

| 检查 | 结果 |
| --- | --- |
| Android 协议单元测试 | 10 项通过，0 失败 |
| Android 模拟器完整交互测试 | 7 项通过，0 失败 |
| 深色长消息／公式、离线重试针对性复测 | 各 1 项通过，0 失败 |
| Android Lint | 0 错误，12 条建议（固定依赖版本、同步状态提交等） |
| 现有网页／桌面 JS 回归 | 128 项通过，0 失败 |
| 现有 Python 后端回归 | 共 398 项：394 通过，4 项按平台条件跳过 |
| 正式 APK 签名校验 | APK Signature Scheme v2 通过，RSA 3072 |
| 正式包安装／同签名覆盖升级 | 通过；连接卡片与 Keystore 加密状态保留 |

单元测试使用真实 HTTPS MockWebServer，覆盖根地址校验、双连接 Cookie／CSRF／Origin／User-Agent 隔离、Cookie 恢复、取消等待响应和慢速响应正文、401、无效 TLS 证书、重定向拒绝、消息分页／增量／epoch 重置、稳定请求标识、附件限制、数学预处理保留代码。

模拟器内启动两个独立 HTTPS 测试网关，覆盖独立登录、电脑切换、草稿／上次聊天恢复、正确目标上的认证发送、退出隔离、AES/GCM 持久化、长消息和原生 Markdown、前后台轮询、原生问题回复、审批／账户请求、历史分页／锚点恢复、附件失败重试、未知发送结果保留原请求标识、离线草稿、登录过期；主 Activity 验证键盘、系统返回、旋转、深色模式与 Activity 重建。测试 CA 仅由测试代码注入；正式版没有跳过证书验证的选项。

本机 VPN 虚拟网卡 `100.64.0.1` 会干扰现有真实 LAN 监听测试。直接运行原始后端套件时，此项出现失败／超时；测试运行器仅过滤该虚拟地址，使用物理 LAN 地址 `192.168.0.62` 重跑后，完整后端套件通过。未修改网关代码、VPN 或系统网络配置。

## 安装与签名

最终交付 APK 的 SHA-256：

```text
a9350a9d39bfee2f37e84a24640b28d24943d4ec40234238d055774bfe04250e
```

签名证书 SHA-256：

```text
0d486fd5493df33492b2f6b9363ff9f980218bdad13746aaf528bd11d08557ce
```

正式包已在模拟器安装、启动，并执行 `adb install -r` 覆盖。覆盖前后均可见 `Upgrade-Test` 测试连接及其 HTTPS 地址，证明正式签名和 Android Keystore 状态可以连续升级。调试版使用 `.debug` 后缀独立安装。

密钥与随机密码保存在 `.local/android-signing/`，目录 0700、文件 0600；已检查 Git 忽略规则。源码包、APK 和报告不包含密钥、密码、SDK 或本机登录状态。备份与重建方式见 [构建说明](README.md)。

## 验证范围

本次端到端环境是 Android 11 模拟器和两个 HTTPS 测试网关。Android 8 最低支持由清单和依赖检查确认；ARM 真机、其他安卓版本及生产网关仍需实际设备验收。没有切换真实 Codex 账户、消耗真实重置卡或向真实 PushPlus／ntfy 通道发送测试消息。

原始 XML、Lint、签名信息及覆盖升级截图保存在本机 `dist/android/validation/`。没有发布应用商店或自动创建上游 PR。
