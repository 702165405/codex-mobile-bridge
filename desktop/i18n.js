'use strict';
(function(root){
  const english={
    'Codex 手机网关':'Codex Mobile Bridge',
    '电脑运行 · 手机接力':'On your desktop. With you on mobile.',
    '连接与状态':'Connection & status','网络与登录':'Network & sign-in','手机通知':'Mobile notifications','运行配置':'Runtime settings','运行日志':'Runtime logs',
    '正在读取…':'Loading...','正在读取状态':'Loading status','检查中':'Checking',
    '关闭窗口后，网关继续运行。':'The gateway keeps running when this window closes.',
    '把正在做的事，带到手机上':'Continue on your phone',
    '继续原有 Codex 聊天，查看结果并回应待确认操作。':'Continue existing Codex chats, view results and respond to pending requests.',
    '启动网关':'Start gateway','停止':'Stop','手机访问地址':'Phone access URLs',
    '局域网地址适用于同一网络；外网访问使用 HTTPS 入口。':'Use a LAN address on the same network, or HTTPS for remote access.',
    '登录方式':'Sign-in method','使用网关自己的账号密码。':'Use the gateway account and password.',
    '查看首次登录凭据':'View initial credentials','未开启':'Off',
    '配置 ntfy 后，手机锁屏也可接收待确认提醒。':'Set up ntfy to receive pending-request alerts on your phone.',
    '配置通知 →':'Notification settings →','连接方式':'Connection',
    '允许局域网访问':'Allow LAN access','监听端口':'Listening port',
    '启用 Cloudflare 临时 HTTPS 入口':'Enable a temporary Cloudflare HTTPS tunnel',
    '临时外网地址可能随重启变化，局域网端口保持所填值。':'The temporary public URL may change after a restart. The LAN port stays the same.',
    '额外允许的 HTTPS 源':'Additional allowed HTTPS origins',
    'https://codex.example.com\n每行一个，不含路径':'https://codex.example.com\nOne per line, without a path',
    '登录验证':'Authentication','账号密码':'Account and password','免密访问':'No password','账号':'Username','设置新密码':'New password',
    '留空保留原密码；至少 12 个字符':'Leave blank to keep the password; at least 12 characters',
    '登录设置保存后，在下次启动网关时生效。':'Saved sign-in settings take effect the next time the gateway starts.',
    '首次接入与测试':'Initial setup & testing','手机安装 ntfy':'Install ntfy on your phone',
    '在 iPhone 或 Android 安装 ntfy，允许系统通知和锁屏显示。':'Install ntfy on iPhone or Android and allow notifications on the lock screen.',
    '订阅同一服务与主题':'Subscribe to the same server and topic','首次测试可用':'For an initial test, use',
    '。在下方生成随机主题，再把完整主题复制到手机 ntfy 中订阅；主题会自动创建。':'. Generate a random topic below and subscribe to that exact topic in ntfy on your phone. Topics are created automatically.',
    '保存，再发送测试通知':'Save, then send a test notification',
    '公共匿名主题的 Token 留空。勾选“开启手机通知”，点击底部“保存配置”，然后“发送测试通知”。以手机实际收到为准。':'Leave the token blank for a public anonymous topic. Enable mobile notifications, save settings, then send a test. Confirm delivery on your phone.',
    '开启聊天提醒':'Enable chat alerts',
    '启动网关，在手机浏览器刷新并打开已连接的聊天，点击“提醒”直到显示“提醒已开”。随后让 Codex 提出一次选择，锁屏检查是否收到待确认通知。':'Start the gateway, refresh the mobile page and open a connected chat. Tap “提醒” until it shows “提醒已开”. Ask Codex for a choice, then check for a pending-request notification on your lock screen.',
    '公共匿名主题没有访问控制，知道主题名的人可读写。请使用随机长名称，首测保留“显示聊天标题”为关闭；正式使用可选择受保护主题。':'Public anonymous topics have no access control: anyone who knows the name can read and publish. Use a long random name and keep chat titles disabled for testing. Use a protected topic for regular use.',
    'ntfy 手机安装说明 ↗':'ntfy mobile setup ↗','聊天提醒的测试提示词':'Chat alert test prompt',
    '请用提问工具让我选择“继续测试”或“结束测试”，等待我的回答，不执行任何命令或文件操作。':'Use the question tool to let me choose “Continue testing” or “End testing”. Wait for my answer without running commands or changing files.',
    '测试通知只检查手机接收；聊天提醒还需要电脑、网关、原 Codex App 及相关 SSH 连接在线。':'Test notifications only check phone delivery. Chat alerts also require the computer, gateway, original Codex App and any relevant SSH connections to remain online.',
    '收不到通知或打不开聊天？':'Missing notifications or unable to open a chat?',
    '先核对手机与电脑的服务地址、主题完全一致；服务拒绝访问时，检查 Token 和主题权限，匿名首测可换一个随机主题。':'Check that the phone and desktop use exactly the same server and topic. For access errors, check the token and topic permissions; try a new random topic for an anonymous test.',
    '服务已接受但手机没提示：检查 ntfy 订阅连接、系统通知权限和专注模式。Android 可开启即时投递并允许后台运行；自建服务的 iPhone 即时通知需要配置 APNs 上游。':'If the server accepts a notification but the phone stays silent, check the ntfy connection, notification permissions and Focus mode. On Android, allow instant delivery and background activity. Self-hosted servers need an APNs upstream for instant iPhone delivery.',
    '通知能收到但聊天打不开：保持网关在线。局域网链接需要手机在同一网络；外网访问需要手机可达的 HTTPS 入口。':'If notifications arrive but chats will not open, keep the gateway online. LAN links require the same network; remote access requires an HTTPS URL reachable from your phone.',
    'ntfy 推送':'ntfy delivery',
    'iPhone 和 Android 安装 ntfy 并订阅同一主题，再在手机聊天中开启“待确认提醒”。':'Install ntfy on iPhone or Android, subscribe to the same topic, then enable pending-request alerts in the mobile chat.',
    '开启手机通知':'Enable mobile notifications','ntfy 服务地址':'ntfy server URL','接收主题':'Topic','点击生成随机主题':'Generate a random topic','生成随机主题':'Generate topic','访问 Token':'Access token',
    '如服务需要认证，在这里填写':'Enter a token if your server requires authentication',
    '清除已保存的 Token':'Clear saved token','通知点击后的网关地址':'Gateway URL for notification links',
    '留空自动选择当前 HTTPS 或局域网入口':'Leave blank to use the current HTTPS or LAN URL',
    '在通知中显示聊天标题':'Include chat titles in notifications',
    '默认只推送待处理数量。临时域名变化后，旧通知的链接可能失效。自建 ntfy 的 iPhone 即时通知需要配置 APNs 上游。':'Only pending counts are sent by default. Older links may stop working when the temporary domain changes. Self-hosted ntfy requires an APNs upstream for instant iPhone delivery.',
    '发送测试通知':'Send test notification','已关注聊天':'Watched chats',
    '关注开关在手机聊天页面。开启后，关闭网页仍继续监听；取消关注即可停止该聊天提醒。':'Manage alerts on the mobile chat page. Watched chats stay monitored after you close the page; unwatch a chat to stop its alerts.',
    'Codex 与外网程序':'Codex & tunnel programs','Codex 数据目录':'Codex data directory','选择':'Browse',
    '桌面 IPC 地址':'Desktop IPC address','留空自动发现；Windows 可填写命名管道':'Leave blank to discover; Windows supports named pipes',
    'Codex 可执行文件':'Codex executable','留空自动发现':'Leave blank for automatic discovery',
    'cloudflared 可执行文件':'cloudflared executable','使用临时外网入口时需要':'Required for temporary public access',
    '网络和运行路径在网关停止后可修改。模型、提供商和 Skill 沿用原 Codex 会话设置。':'Stop the gateway to change network settings or runtime paths. Models, providers and Skills use the original Codex chat settings.',
    '启动与数据目录':'Startup & data directory','打开 App 时自动启动网关':'Start the gateway when the app opens',
    '打开目录':'Open directory','选择已有网关目录':'Choose existing gateway directory',
    '目录内保存网关配置、通知 Token、登录凭据和运行日志。':'This directory stores gateway settings, notification tokens, credentials and logs.',
    '配置已保存':'Settings saved','保存配置':'Save settings','网关运行日志':'Gateway logs','刷新日志':'Refresh logs','暂无日志':'No logs yet',
    '，有未保存的修改':', unsaved changes','有未保存的修改':'Unsaved changes',
    '网关已启动，正在运行。':'The gateway is running.',
    '端口已被其他网关占用，请检查运行配置。':'The port is occupied by another gateway. Check runtime settings.',
    '正在启动网关':'Starting gateway','网关启动超时，请查看运行日志。':'Gateway startup timed out. Check the runtime logs.',
    '网关已停止':'Gateway stopped','运行中':'Running','启动中':'Starting','端口已占用':'Port occupied','未启动':'Stopped','账号：':'Username: ',
    '已开启 · ':'On · ',' 个关注聊天':' watched chats',
    '当前网关版本较旧，重启后启用通知能力。':'Restart the older gateway to enable notification support.',
    '手机关闭网页后，已关注聊天仍会继续提醒。':'Watched chats keep sending alerts after the mobile page closes.',
    '最近一次发送：':'Last sent: ','尚无发送记录':'No deliveries yet',
    '尚未开启手机通知：填写并保存后，先发送测试通知。':'Notifications are off. Complete and save the settings, then send a test.',
    '网关尚未启动：可以先测试 ntfy 接收，聊天提醒需要启动网关。':'The gateway is stopped. You can test ntfy delivery now; chat alerts need a running gateway.',
    '当前网关版本不支持聊天提醒，请在首页停止后重新启动网关，再刷新手机网页。':'This gateway version does not support chat alerts. Stop and restart it on the overview page, then refresh the mobile page.',
    '网关已就绪：在手机打开一个已连接的聊天，点击“提醒”，直到显示“提醒已开”。':'The gateway is ready. Open a connected chat on your phone and tap “提醒” until it shows “提醒已开”.',
    '外网 HTTPS':'Public HTTPS','此电脑':'This computer','局域网':'LAN',' · 启动后可用':' · Available after starting','复制':'Copy','打开':'Open',
    '暂无关注聊天。请在手机打开聊天并开启提醒。':'No watched chats. Open a chat on your phone and enable alerts.',
    '已保存；留空保留，服务地址变化时清除':'Saved; leave blank to keep. Cleared if the server changes.',
    '已保存提交的配置，仍有新修改待保存。':'Submitted settings saved. Newer edits are still unsaved.',
    '配置已保存。通知设置由新版网关自动读取，登录设置在下次启动生效。':'Settings saved. Newer gateways apply notification changes automatically; sign-in changes apply on the next start.',
    '请先保存配置，再启动网关。':'Save settings before starting the gateway.',
    '请先保存 ntfy 配置，再发送测试通知。':'Save ntfy settings before sending a test notification.',
    '已切换网关目录，原来的网关进程继续运行。':'Gateway directory changed. The previous gateway keeps running.',
    '打开控制面板':'Open control panel','网关运行中':'Gateway running','网关未启动':'Gateway stopped',
    '打开手机访问地址':'Open phone URL','复制手机访问地址':'Copy phone URL','停止网关并退出':'Stop gateway and quit','退出控制面板（保留网关）':'Quit control panel (keep gateway running)',
    '网关操作未完成':'Gateway action failed','不允许的界面来源':'Untrusted interface origin','未知路径类型':'Unknown path type','地址不可用':'URL unavailable',
    '端口必须为 1–65535':'Port must be between 1 and 65535','网络开关格式不正确':'Invalid network toggle','路径格式不正确':'Invalid path',
    'Codex 数据目录不存在':'Codex data directory does not exist',
    '请先停止网关再更改网络或运行路径，以免正在使用的地址失效':'Stop the gateway before changing network settings or runtime paths to keep active URLs valid.',
    '请填写有效的登录账号和登录方式':'Enter a valid username and sign-in method','新密码至少需要 12 个字符':'The new password must contain at least 12 characters',
    '额外 HTTPS 源格式不正确':'Invalid additional HTTPS origins','额外源须为 HTTPS 地址，不含路径':'Additional origins must use HTTPS without a path',
    '已连接正在运行的网关':'Connected to the running gateway','请先选择存在的 Codex 数据目录':'Choose an existing Codex data directory first',
    '请先选择 cloudflared 程序，或关闭临时外网入口':'Choose the cloudflared executable or disable the temporary tunnel',
    '端口已被占用，请检查现有网关；不会自动更换端口':'The port is occupied. Check the existing gateway; the port will not be changed automatically.',
    'ntfy 已接受测试通知，请在手机确认是否收到':'ntfy accepted the test notification. Confirm delivery on your phone.',
    '请输入完整的 HTTP/HTTPS 地址，不含账号、查询参数或片段':'Enter a complete HTTP/HTTPS URL without credentials, query parameters or fragments',
    '推送服务请使用 HTTPS；HTTP 仅用于本机测试':'Use HTTPS for notification servers; HTTP is only allowed for local testing',
    '通知开关格式不正确':'Invalid notification toggle','ntfy 主题只允许 1–64 位字母、数字、下划线和短横线':'ntfy topics must contain 1–64 letters, digits, underscores or hyphens',
    '开启通知前请填写 ntfy 主题':'Enter an ntfy topic before enabling notifications','通知跳转地址格式不正确':'Invalid notification link URL',
    'ntfy Token 格式不正确':'Invalid ntfy token','请先配置 ntfy 服务和主题':'Configure the ntfy server and topic first','ntfy 未接受通知':'ntfy did not accept the notification'
  };
  function normalize(value){return typeof value==='string'&&/^en(?:-|$)/i.test(value)?'en':'zh-CN';}
  function translate(text,language){
    const source=String(text).replace(/^Error invoking remote method '[^']+': (?:Error: )?/,'');
    return normalize(language)==='en'?(english[source]??source):source;
  }
  // Capture only original UI nodes, never user values, gateway logs or chat data.
  function bind(document){
    const entries=[],walker=document.createTreeWalker(document.documentElement,4);
    let node;while((node=walker.nextNode())){
      const source=node.nodeValue,trimmed=source.trim();
      if(Object.hasOwn(english,trimmed)){
        const target=node;
        entries.push(language=>{target.nodeValue=source.replace(trimmed,translate(trimmed,language));});
      }
    }
    for(const element of document.querySelectorAll('[placeholder]')){
      const source=element.getAttribute('placeholder');
      entries.push(language=>element.setAttribute('placeholder',translate(source,language)));
    }
    return language=>{document.documentElement.lang=normalize(language);entries.forEach(apply=>apply(language));};
  }
  const api={normalize,translate,bind,english};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.desktopI18n=api;
})(typeof window==='object'?window:globalThis);
