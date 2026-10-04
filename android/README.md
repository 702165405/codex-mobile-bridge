# Codex Mobile Bridge Android

Kotlin / Jetpack Compose Material 3 原生客户端，无 WebView。Android 8.0+，版本 1.0.0，包名 `io.github.codexmobilebridge.android`。基于上游 v1.3.2（`38e61873b6790a5226b0d872411f6afa5b0b85b8`），开发分支 `codex/android-native`。

## 安装与连接

安装 `dist/android/Codex-Mobile-Bridge-1.0.0.apk`，如系统提示，允许文件管理器安装未知来源应用。桌面网关仍需运行；添加可由手机访问的 HTTPS 根地址，例如 `https://computer.example.com`。拒绝 HTTP、自签名／过期／不匹配证书，以及包含路径、用户名或查询参数的地址。

首页添加多台电脑，保存名称、排序和最近使用时间。点击卡片独立登录；聊天顶部电脑图标切换连接。可粘贴网页扫码登录链接，也可通过安卓系统分享将链接传入 App。免密网关显示免密连接按钮，不保存账号密码。扫码登录链接只在首次打开或新分享时消费，旋转不会重复兑换。

聊天支持搜索、按项目分组、归档筛选、新建、重命名、历史分页、实时增量更新、重连与停止。输入区提供模型、推理强度、Fast、Skill、附件，以及发送／排队／补充和普通／计划／目标模式。聊天菜单集中其他工具。

通知通过网关已有 PushPlus、ntfy 或 Bark 发送；App 不注册额外推送服务。PushPlus 测试按钮会实际发送通知，测试使用已保存的配置；修改后先保存再测试。聊天的审批／完成提醒可继承默认，也可独立开关。

## 功能与协议对应

| 原生界面 | 网关操作 |
| --- | --- |
| 登录与链接导入 | `/api/auth`、`login`、`pair`、`logout` |
| 聊天列表／分组／搜索／归档 | `/api/sessions`、`projects`、`activity` |
| 新建聊天 | `POST /api/sessions`，持久化 UUID |
| 分页、增量和完整长文本 | 会话 `timeline`、`changes`、`detail` |
| 重命名／历史／重连／停止／撤回排队 | 会话 `rename`、`history`、`reconnect`、`stop`、`queue` |
| 模型／推理／Fast／Skill | 会话 `catalog`、`settings`、`send` |
| 三种发送方式／三种工作模式 | 会话 `send`；目标 `goal/edit`、`goal/status`、`goal/cancel` |
| 系统文件选择器／重试上传／保存下载 | 会话 `uploads`、`files`，10 文件、单个 20 MiB、总计 100 MiB |
| 审批／问题／计划／MCP 表单／电脑应用 | 会话 `respond`，依 `supported` 和可选决策显示 |
| 编辑重发／编辑历史分支／普通分支 | 会话 `message-action`，携带 epoch、稳定消息 key、version 与 UUID |
| 账户列表、额度／模型详情、切换 | `/api/accounts`、`accounts/details`、`accounts/switch` |
| 共享额度与重置卡 | `/api/account`、`account/reset`，确认消耗且重用 requestId |
| 聊天提醒／通知默认／PushPlus 配置与测试 | 会话 `notifications`、`/api/notifications/defaults`、`pushplus`、`pushplus/test` |

Markdown 使用原生 TextView / Markwon，支持表格、代码复制、数学公式、可折叠思考与执行消息。网关附件图片用认证请求加载并限制解码尺寸；外部图片不自动请求，附件和生成文件可保存至系统选择的位置。支持系统／浅色／深色、中文／英文、字号和紧凑布局。

每个连接独立 Cookie、CSRF 与 HTTP 客户端，写请求携带对应 Origin 和固定 User-Agent。TLS 不降级、不绕过证书验证、不跟随重定向。登录 Cookie、本地连接、草稿、阅读锚点、附件状态和待提交标识均通过 Android Keystore AES/GCM 加密持久化。系统备份与设备迁移排除这些状态。

聊天状态以连接＋主机＋聊天分隔。切换取消旧协程和旧 HTTP 请求，旧页面的滚动回调也检查归属；仅当前连接轮询，后台停止、前台恢复。历史插入沿用稳定消息 key，保存阅读锚点与偏移。请求结果未知不会自动重复发送，手动重试沿用原标识。分支创建后修改未送达时保留为编辑草稿，不拼接成普通新消息。

账户切换会结束电脑上所有任务并重启 Codex 桌面，必须在原生确认框确认。额度重置也需确认消耗。网关不支持的审批、Fast、目标或账户操作显示限制说明。

## 构建

安装 JDK 17，设置 `JAVA_HOME`。可使用已有 Android SDK（API 35、Build Tools 35.0.0），将路径写入被忽略的 `android/local.properties`；也可在仓库根目录执行：

```sh
python3 android/scripts/bootstrap-sdk.py
export GRADLE_USER_HOME="$PWD/.tmp/android-gradle-cache"
android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

项目工具安装到 `.tmp/android-tools`，Gradle 缓存放到 `.tmp/android-gradle-cache`。首次构建需要访问 Google Maven、Maven Central 和 Gradle 下载服务。Windows 使用 `gradlew.bat`，环境变量按 PowerShell 设置。Gradle Wrapper 固定 8.11.1 并验证发行包 SHA-256。

签名发布包：

```sh
python3 android/scripts/build-release.py
```

首次生成 `.local/android-signing/codex-mobile.jks` 和 `credentials.json`，目录权限 0700、文件 0600；密码随机生成，只通过环境变量传入子进程，不写入命令参数或构建文件。输出签名 APK 和 `SHA256SUMS` 到 `dist/android/`。所有密钥、密码、临时工具和 APK 均已在 `.gitignore` 中排除。

**备份整个 `.local/android-signing/` 目录到自己的加密存储**，保留 JKS、密码文件及 alias；两者缺一不可。以后覆盖升级必须使用同一包名和同一签名，增加 `versionCode`／`versionName` 再运行脚本。密钥丢失无法为已安装版本提供覆盖升级。脚本拒绝在已有签名资料缺失时默默生成替代密钥。不要上传此目录到 GitHub，也不要把密码发到聊天中。

## 测试

单元测试使用 MockWebServer 的真实 HTTPS 和测试专用信任证书，覆盖双网关 Cookie／CSRF／Origin、取消请求、失效登录、无效证书、重定向拒绝、分页／增量／重置、稳定请求标识及附件限制。测试信任只通过测试注入，发布界面没有跳过证书的选项。

模拟器测试：

```sh
android/gradlew -p android :app:connectedDebugAndroidTest
```

需要已启动的安卓设备或模拟器。调试包使用 `.debug` 包名后缀，与正式签名包并存，不覆盖正式连接数据。测试在设备内部启动两个独立 HTTPS 网关，覆盖原生登录、切换、草稿／上次聊天恢复、认证发送、退出隔离、Keystore 加密持久化、长消息／表格／公式、主题、生命周期轮询、账户与审批协议、附件失败重试、结果未知防重、离线及登录过期。主 Activity 测试检查键盘、系统返回、旋转和重建。

详见 [测试结果](TEST_RESULTS.md)。没有自动提交 PR，也没有发布应用商店。
