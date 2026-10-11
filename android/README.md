# Codex Mobile Bridge Android

Kotlin / Jetpack Compose Material 3 原生客户端，Android 8.0+，版本 **1.2.0 / versionCode 12**，包名 `io.github.codexmobilebridge.android`。基于上游 v2.0.1 功能接口，以自有仓库 `main` 和原生安卓为主。

## 1.2.0 原生工作台

日常入口全部使用原生页面：扫码与多电脑卡片、Codex／Claude Code 会话切换、聊天、文件、Git、连续终端、临时侧边聊天与子智能体历史。采用新版的蓝色强调、白／灰卡片、深色模式和工具标签设计，保持原生消息、Markdown、输入和滚动。

工具在打开时读取数据；首屏不下载网页 JS/CSS，也不启动 WebView。最近聊天使用最多四条会话的有界内存缓存，先显示历史再后台核验；缓存不显示待审批请求，不作为已连接状态。恢复较早阅读位置时，分页读取完成后一次替换列表。后台停止界面轮询，前台恢复；电脑、客户端和聊天的草稿与操作编号分别保存。

- **Codex／Claude Code**：原生列表、聊天、发送／排队／补充、模型、Skill、审批及问题回答、账号接入和通知设置。Codex 继续保留计划／目标、编辑／分支等原有能力。Claude 功能按桌面实际提供的能力显示，不包含 Cowork、普通 Claude Chat 或其他客户端。
- **文件**：目录、筛选、隐藏文件、分页、文本／代码预览、图片缩放、上传、目录 ZIP 与流式下载；下载使用 ETag／Range 续传，内容变化时重新下载。上传沿用网关 20 MiB 限制，不覆盖已有不同内容。
- **Git**：工作区／已暂存／冲突、差异、提交历史与文件差异、暂存／撤回、提交、创建／切换分支、合并和中止合并。写操作携带读取时的版本，提交及分支操作须在原生界面确认。
- **终端**：按电脑和聊天保持 shell，发送命令和交互输入，Ctrl+C、Tab、Esc、方向键、清屏和关闭。输入编号跨页面保存，未知结果须核验原输入；终端失效时可确认放弃待核验输入，不自动重发命令。当前原生文本终端支持常用光标／清屏控制，复杂全屏 TUI 和完整 ANSI 颜色仍需外部终端；不执行输出中的剪贴板控制序列。
- **侧边聊天**：在当前本地 Codex 项目中显式创建临时聊天，支持模型、Skill、附件、权限、发送／排队／补充、审批、停止及结束。网关重启或显式结束后清除；SSH 主机的限制沿用网关。
- **宽屏**：工作台宽度达到 840 dp 时，聊天与工具并排；窄屏采用标签切换。

原包名、原签名、Keystore 加密账号／Cookie／草稿、后台提醒、连接隔离和 HTTPS 校验继续保留。在线更新地址始终为 `https://mac.lqilt.top/android/update.json`。随包保留的旧网页兼容类不在正常入口启动。

配套个人 Mac 网关 **2.0.2**。Claude Code 首次接入仍需在电脑确认权限、开发者模式或目录信任；服务端与客户端不支持的操作不能通过原生页面绕过。

## 安装与连接

安装 `dist/android/Codex-Mobile-Bridge-1.2.0.apk`，如系统提示，允许文件管理器安装未知来源应用。桌面网关仍需运行；添加可由手机访问的 HTTPS 根地址，例如 `https://computer.example.com`。拒绝 HTTP、自签名／过期／不匹配证书，以及包含路径、用户名或查询参数的地址。

首页添加多台电脑，保存名称、排序和最近使用时间。点击卡片独立登录；聊天顶部电脑图标切换连接。可粘贴网页扫码登录链接，也可通过安卓系统分享将链接传入 App。账号密码登录可勾选「记住账号和密码」，登录成功后按连接独立保存，使用 Android Keystore 加密；重新登录时自动填入，不自动提交。取消勾选或删除连接会清除该连接保存的账号密码。免密网关显示免密连接按钮。扫码登录链接只在首次打开或新分享时消费，旋转不会重复兑换。

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

详见 [测试结果](TEST_RESULTS.md)。提供源码与签名 APK，不发布应用商店版本。

## App 内更新（1.2.0：原生工作台，个人域名）

打开右上角设置 →「版本与更新」，无需连接电脑即可查看版本、检查、下载和安装更新。默认读取 `https://mac.lqilt.top/android/update.json`，APK 来自同一域名 `/android/` 目录。更新 HTTP 客户端与聊天登录隔离，不发送网关 Cookie、CSRF 或登录凭据。

只接受更新清单同域名、同端口的 HTTPS APK 地址，个人域名更新不跟随跳转。下载后检查 SHA-256、应用包名、版本及当前安装签名，再交给安卓系统安装器；首次需允许 App 安装应用。下载失败可重试，调试包不能安装正式更新。

发布步骤：增加 versionCode／versionName，用同一签名运行 `build-release.py`；运行 `python3 android/scripts/create-update-manifest.py --notes '中文更新说明'`。先上传 APK 到现有域名的 `/android/` 静态目录，校验后最后原子替换 update.json。只部署到自己的服务器，不推送或发布 GitHub；签名密钥始终保存在本机。

仍使用个人域名的旧版可直接检查更新。1.0.8 与 1.1.0 使用过 GitHub 更新源，需要从 `https://mac.lqilt.top/android/Codex-Mobile-Bridge-1.2.0.apk` 下载并覆盖安装一次，之后 App 检查更新继续使用个人域名。静态服务配置见 `hosting/nginx-location.conf`。

## 列表运行提示（1.0.3）

列表加载／手动刷新／返回前台立即请求活动状态，不再先等待 5 秒。首次状态订阅仍未连接时，最多追加三次 500ms 间隔查询，之后按 HTML 的 5 秒节奏更新；在聊天页也更新已加载列表的活动状态。服务端首次附着或网络较慢时仍可能需要短暂等待，不将未知状态误报成空闲。

与 HTML 一致：运行中绿点、完成未查看蓝点、失败／停止未查看红点；进入对应聊天清除未读标识。空闲聊天不显示状态文字；首次未知不显示圆点，曾经运行但暂时断开时显示灰点。状态按电脑连接和主机、聊天隔离并加密保存，活动更新时间同时更新最近排序。列表不再显示 `idle`／`unknown` 协议字段。

## 连接后默认进入列表（1.0.5）

启动 App 后连接已保存电脑、切换电脑、重新登录或导入登录链接，都先进入聊天列表，不再自动打开上次聊天。由用户点选聊天后，仍恢复对应聊天的草稿、附件与阅读位置。此次不增加列表缓存或其他功能。

## App 本地通知（1.0.6）

打开聊天菜单「App 通知」，或首页设置 →「App 通知」，开启开关并允许安卓通知权限（Android 13+）。可以先点击「发送测试通知」。默认关闭，设置在本机加密保存，独立于 PushPlus／ntfy。

启用并连接电脑后，前台使用现有同步；退到后台通过用户开启的前台服务和低打扰常驻通知维持监测，监测当前电脑的已加载聊天及最新 100 个聊天（最多 500 个标识）。后台活动查询每 5 秒一次，列表每 30 秒刷新；活跃任务、最近打开及存在待回复请求的聊天读取少量时间线元数据，以发现审批和问题。只读查询不会在桌面打开聊天，不使用厂商推送、开机广播、后台定时任务或服务自动重启。

提醒任务完成、失败、停止以及等待审批／问题回复。历史任务不会首次批量通知；事件标识和请求标识按连接／主机／聊天隔离并去重。当前正在阅读的聊天不弹重复提醒。点击通知进入对应电脑的聊天列表，仍由用户点选聊天。切换电脑、退出登录或进入电脑选择页会停止旧电脑的后台服务。服务遇到登录过期提示重新登录并停止；离线保留已观察状态并重试。

退后台时有常驻通知，可点击「停止提醒」关闭，也可在 App 中关闭开关；关闭后停止请求。强制停止 App、系统结束进程或设备深度休眠时不保证及时提醒，重新打开 App 并连接后恢复。没有隐藏常驻通知、申请忽略电池优化或定时拉起 App。后台仅使用有效 HTTPS 证书、独立 Cookie／CSRF 客户端和现有网关接口；后台提醒仅使用登录会话。

Android 14+ 服务类型使用 specialUse 并声明监测用途；这是个人侧载版本，不接手机厂商 SDK 或 Google 推送。
