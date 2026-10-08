@file:OptIn(kotlinx.coroutines.FlowPreview::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.codexmobilebridge.android

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.text.DateFormat
import java.util.Date

val EmptyObject = JsonObject(emptyMap())
@Composable fun BridgeApp(vm: BridgeViewModel) {
    val dark = when(vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if(dark) darkColorScheme(primary=Color(0xFF9DCEAC),surface=Color(0xFF141B17)) else lightColorScheme(primary=Color(0xFF285F41),surface=Color(0xFFF8FAF5))
    val view=LocalView.current
    SideEffect { (view.context as? android.app.Activity)?.let{activity->
        val controller=WindowCompat.getInsetsController(activity.window,view)
        controller.isAppearanceLightStatusBars=!dark;controller.isAppearanceLightNavigationBars=!dark
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _,event -> when(event) { Lifecycle.Event.ON_START -> vm.resume(); Lifecycle.Event.ON_STOP -> vm.pause(); else -> {} } }
        lifecycle.addObserver(observer); if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.resume()
        onDispose { lifecycle.removeObserver(observer); vm.pause() }
    }
    MaterialTheme(colorScheme=colors) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title={ Column {
                    Text(if(vm.screen == "chat") vm.timeline.meta.str("title",vm.t("聊天","Chat")) else "Codex Mobile Bridge",maxLines=1)
                    if(vm.selected != null && vm.screen != "connections") Text(vm.selected!!.name,style=MaterialTheme.typography.labelMedium)
                } }, navigationIcon={
                    if(vm.screen != "connections") IconButton(onClick={ if(vm.screen=="chat") vm.showSessions() else vm.showConnections() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,vm.t("返回","Back")) }
                }, actions={
                    if(vm.screen != "connections") IconButton(onClick=vm::showConnections) { Icon(Icons.Default.Computer,vm.t("切换电脑","Switch computer")) }
                    if(vm.authenticated && vm.screen != "connections") {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick={ menu=true }) { Icon(Icons.Default.MoreVert,vm.t("更多操作","More actions")) }
                        DropdownMenu(menu,{ menu=false }) {
                            val options = if(vm.screen=="chat") listOf("details" to vm.t("聊天详情与重命名","Chat details & rename"),"model" to vm.t("模型设置","Model settings"),"skills" to "Skills","notifications" to vm.t("聊天提醒","Chat reminders"),"goal" to vm.t("目标进度","Goal progress")) else emptyList()
                            for((key,label) in options + listOf("new" to vm.t("新建聊天","New chat"),"accounts" to vm.t("账号与接入","Accounts"),"usage" to vm.t("账户与额度","Account usage")))
                                DropdownMenuItem(text={ Text(label) },onClick={ menu=false;vm.openTool(key) })
                            if(vm.notificationsAvailable) {
                                DropdownMenuItem(text={ Text(vm.t("PushPlus 通知","PushPlus notifications")) },onClick={ menu=false;vm.openTool("pushplus") })
                                DropdownMenuItem(text={ Text(vm.t("默认提醒设置","Default reminders")) },onClick={ menu=false;vm.openTool("defaults") })
                            }
                            DropdownMenuItem(text={ Text(vm.t("App 通知","App notifications")) },onClick={menu=false;vm.openTool("app-notifications")})
                            DropdownMenuItem(text={ Text(vm.t("显示设置","Appearance")) },onClick={ menu=false;vm.openTool("appearance") })
                            DropdownMenuItem(text={ Text(vm.t("退出此电脑登录","Sign out of this computer")) },onClick={ menu=false;vm.openTool("logout") })
                        }
                    } else IconButton(onClick={ vm.openTool("appearance") }) { Icon(Icons.Default.Settings,vm.t("显示设置","Appearance")) }
                })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if(vm.error.isNotBlank() && vm.tool == null) {
                        StatusStrip(vm.error,true,vm::clearError)
                        if(vm.screen=="chat")TextButton(onClick={vm.clearError();vm.resume()},enabled=!vm.busy){Text(vm.t("重试连接","Retry connection"))}
                    }
                    if(vm.notice.isNotBlank() && vm.tool == null) StatusStrip(vm.notice,false,vm::clearError)
                    when(vm.screen) {
                        "connections" -> ConnectionsScreen(vm)
                        "login" -> LoginScreen(vm)
                        "sessions" -> SessionsScreen(vm)
                        "chat" -> vm.target?.let { key(it.key,vm.chatGeneration) { ChatScreen(vm) } }
                    }
                }
                ToolDialog(vm)
            }
        }
    }
    BackHandler(vm.tool != null || vm.screen != "connections") {
        if(vm.tool != null) vm.closeTool() else if(vm.screen == "chat") vm.showSessions() else vm.showConnections()
    }
}
@Composable fun StatusStrip(text: String,error: Boolean,dismiss: ()->Unit) {
    Row(Modifier.fillMaxWidth().background(if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
        IconButton(onClick=dismiss) { Icon(Icons.Default.Close,"Dismiss") }
    }
}
@Composable fun ConnectionsScreen(vm: BridgeViewModel) {
    var editing by remember { mutableStateOf<Connection?>(null) }; var add by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Connection?>(null) }; var paste by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={ editing=null;add=true },Modifier.weight(1f)) { Icon(Icons.Default.Add,null);Text(vm.t("添加电脑","Add computer")) }
            OutlinedButton(onClick={paste=true}) { Text(vm.t("粘贴登录链接","Paste login link")) }
        }
        if(vm.connections.isEmpty()) Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Default.Devices,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
            Text(vm.t("把电脑上的工作带到手机","Continue computer work on your phone"),style=MaterialTheme.typography.titleLarge)
            Text(vm.t("添加网关 HTTPS 地址；每台电脑独立登录。","Add an HTTPS gateway. Each computer keeps its own login."),Modifier.padding(top=12.dp))
        }
        LazyColumn(contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            itemsIndexed(vm.connections,key={ _,row -> row.id }) { index,row ->
                Card(onClick={vm.select(row)},Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer,null,Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(row.name,style=MaterialTheme.typography.titleMedium);Text(row.origin,style=MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Default.ChevronRight,vm.t("连接","Connect"))
                        }
                        if(row.lastUsed>0) Text(vm.t("最近使用：","Last used: ")+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.lastUsed)),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=12.dp))
                        Row {
                            TextButton(onClick={editing=row;add=true}) { Text(vm.t("编辑","Edit")) }
                            TextButton(onClick={delete=row}) { Text(vm.t("删除","Delete")) }
                            IconButton(onClick={vm.moveConnection(row.id,-1)},enabled=index>0) { Icon(Icons.Default.ArrowUpward,vm.t("上移","Move up")) }
                            IconButton(onClick={vm.moveConnection(row.id,1)},enabled=index<vm.connections.lastIndex) { Icon(Icons.Default.ArrowDownward,vm.t("下移","Move down")) }
                        }
                    }
                }
            }
        }
    }
    if(add) {
        var name by remember(editing) { mutableStateOf(editing?.name ?: "") }; var url by remember(editing) { mutableStateOf(editing?.origin ?: "https://") }
        AlertDialog(onDismissRequest={add=false},title={Text(vm.t("电脑连接","Computer connection"))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{name=it},label={Text(vm.t("名称","Name"))},singleLine=true)
            OutlinedTextField(url,{url=it},label={Text("HTTPS URL")},singleLine=true)
            if(vm.error.isNotBlank()) Text(vm.error,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick={vm.clearError();vm.saveConnection(name,url,editing?.id);if(vm.error.isBlank()) add=false}) {Text(vm.t("保存","Save"))}},dismissButton={TextButton(onClick={add=false;vm.clearError()}){Text(vm.t("取消","Cancel"))}})
    }
    delete?.let { row -> Confirm(vm.t("删除连接？","Remove connection?"),row.name+"\n"+vm.t("将清除这台电脑的本地登录和草稿。","Its saved login and drafts will be removed."),{delete=null}) {vm.removeConnection(row);delete=null} }
    if(paste) TextPrompt(vm,vm.t("粘贴登录链接","Paste login link"),"",{paste=false}) {vm.importLink(it);paste=false}
}
@Composable fun LoginScreen(vm: BridgeViewModel) {
    val saved = remember(vm.selected?.id) { vm.selected?.let { vm.store.savedLogin(it.id) } }
    var username by rememberSaveable(vm.selected?.id) { mutableStateOf(saved?.username ?: "admin") }
    var password by remember(vm.selected?.id) { mutableStateOf(saved?.password ?: "") }
    var rememberPassword by remember(vm.selected?.id) { mutableStateOf(saved != null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.Lock,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
        Text(vm.selected?.name ?: "",style=MaterialTheme.typography.headlineSmall)
        Text(vm.selected?.origin ?: "",style=MaterialTheme.typography.bodyMedium)
        if(vm.passwordless) Text(vm.t("此网关允许免密访问。","This gateway allows passwordless access."))
        else {
            OutlinedTextField(username,{username=it},label={Text(vm.t("账号","Username"))},singleLine=true,enabled=!vm.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text(vm.t("密码","Password"))},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!vm.busy,modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(rememberPassword,{checked -> rememberPassword=checked;if(!checked)vm.selected?.let {vm.store.saveLogin(it.id,null)}},enabled=!vm.busy)
                Text(vm.t("记住账号和密码","Remember username and password"))
            }
        }
        Button(onClick={vm.login(if(vm.passwordless) "" else username,if(vm.passwordless) "" else password,rememberPassword)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()) { Text(vm.t("连接电脑","Connect")) }
        TextButton(onClick={vm.selected?.let {vm.select(it)}} ,enabled=!vm.busy) {Text(vm.t("重新检查连接","Retry connection"))}
        if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable fun SessionsScreen(vm: BridgeViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(vm.search,{vm.search=it},label={Text(vm.t("搜索聊天或项目","Search chats or projects"))},singleLine=true,modifier=Modifier.weight(1f))
            IconButton(onClick={vm.refreshList()},enabled=!vm.busy) {Icon(Icons.Default.Search,vm.t("搜索","Search"))}
            IconButton(onClick={vm.openTool("new")}) {Icon(Icons.Default.Add,vm.t("新建聊天","New chat"))}
        }
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            FilterChip(vm.grouped,{vm.grouped=!vm.grouped},label={Text(vm.t("按项目","By project"))})
            Spacer(Modifier.width(8.dp));FilterChip(vm.archived,{vm.archived=!vm.archived;vm.refreshList()},label={Text(vm.t("已归档","Archived"))})
            Spacer(Modifier.weight(1f));IconButton(onClick={vm.refreshList()},enabled=!vm.busy) {Icon(Icons.Default.Refresh,vm.t("刷新","Refresh"))}
        }
        if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        for(host in vm.hostErrors) Text(host.str("label")+" · "+host.str("error"),color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))
        if(vm.sessions.isEmpty() && !vm.busy) Text(vm.t("没有匹配的聊天","No matching chats"),Modifier.padding(24.dp))
        LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val groups = if(vm.grouped) vm.sessions.groupBy { it.str("projectKey",it.str("projectName",vm.t("未归类","Other"))) } else mapOf("" to vm.sessions)
            for((name,rows) in groups) {
                if(name.isNotBlank()) item(key="group:$name") {Text(rows.firstOrNull()?.str("projectName",name) ?: name,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(vertical=8.dp))}
                items(rows,key={it.str("host")+"|"+it.str("id")}) { row ->
                    Card(onClick={vm.openChat(row.str("id"),row.str("host","local"))},Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                vm.activities[activityKey(row)]?.indicator?.let { indicator ->
                                    val label=when(indicator) {
                                        "running" -> vm.t("运行中","Running")
                                        "completed" -> vm.t("已完成，未查看","Completed, unread")
                                        "failed" -> vm.t("运行失败，未查看","Failed, unread")
                                        "ended" -> vm.t("已停止，未查看","Stopped, unread")
                                        else -> vm.t("运行状态暂不可用","Running status unavailable")
                                    }
                                    val color=when(indicator) {"running" -> Color(0xFF22C55E);"completed" -> Color(0xFF3B82F6);"failed","ended" -> MaterialTheme.colorScheme.error;else -> MaterialTheme.colorScheme.outline}
                                    Box(Modifier.size(8.dp).background(color,CircleShape).semantics { contentDescription=label })
                                }
                                Text(row.str("title",vm.t("未命名聊天","Untitled chat")),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2)
                            }
                            Text(listOf(row.str("projectName"),row.str("hostLabel",row.str("host"))).filter {it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if(vm.listHasMore) item {TextButton(onClick={vm.refreshList(true)},enabled=!vm.busy){Text(vm.t("加载更多","Load more"))}}
        }
    }
}
@Composable fun ChatScreen(vm: BridgeViewModel) {
    val chat = vm.target!!; val chatGeneration=vm.chatGeneration; val meta = vm.timeline.meta
    LaunchedEffect(meta.str("status"),meta.bool("goalRuntimeAvailable"),meta.obj("goal").str("status")) {
        if(meta.isNotEmpty() && vm.draft.sendMode=="steer" && meta.str("status")!="active")vm.editDraft(vm.draft.copy(sendMode="send"))
        if(meta.isNotEmpty() && vm.draft.workMode=="goal" && (chat.host!="local" || !meta.bool("goalRuntimeAvailable",true) || meta.obj("goal").str("status") !in listOf("","complete")))vm.editDraft(vm.draft.copy(workMode="default"))
    }
    val visibleMessages=vm.timeline.messages.filter { row -> (vm.showReasoning || row.str("kind") != "reasoning") && (vm.showProcess || row.str("role") != "activity" && row.str("phase") != "commentary") }
    val list = rememberLazyListState(vm.draft.position,vm.draft.offset)
    val scope = rememberCoroutineScope(); val clipboard = LocalClipboardManager.current
    var follow by remember { mutableStateOf(vm.draft.following) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {vm.addAttachments(it)}
    var download by remember { mutableStateOf<JsonObject?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri -> if(uri != null) download?.let {vm.download(it,uri)} }
    var restored by remember { mutableStateOf(false) }
    val savedAnchor = remember {vm.draft.anchor}
    val savedOffset = remember {vm.draft.offset}
    LaunchedEffect(vm.timeline.sequence,vm.historyBusy,vm.timeline.messages.size) {
        if(!restored && vm.timeline.sequence >= 0) {
            if(!follow && savedAnchor.isNotEmpty()) {
                val index=list.layoutInfo.visibleItemsInfo.find{it.key==savedAnchor}?.index
                    ?: visibleMessages.indexOfFirst{it.str("key")==savedAnchor}.takeIf{it>=0}?.plus(if(vm.timeline.hasMore)1 else 0)
                if(index==null && vm.historyBusy)return@LaunchedEffect
                if(index!=null)list.scrollToItem(index,savedOffset)
            }
            restored=true
        }
    }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { interaction ->
        if(interaction is DragInteraction.Start)follow=false
    } }
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
        if(!scrolling && restored && !list.canScrollForward)follow=true
    } }
    fun readingAnchor(): String {
        val first=list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString() ?: ""
        return first.takeIf{key->vm.timeline.messages.any{it.str("key")==key}} ?: ""
    }
    LaunchedEffect(list) { snapshotFlow { Triple(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,readingAnchor()) }.debounce(500).collect { (index,offset,anchor) -> if(restored)vm.scrollPosition(index,offset,anchor,follow,chat,chatGeneration) } }
    DisposableEffect(Unit) { onDispose { if(restored)vm.scrollPosition(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,readingAnchor(),follow,chat,chatGeneration) } }
    LaunchedEffect(vm.timeline.sequence) {
        if(follow && vm.timeline.messages.isNotEmpty()) list.scrollToItem(maxOf(0,list.layoutInfo.totalItemsCount-1))
    }
    Column(Modifier.fillMaxSize()) {
        if(!meta.bool("connected")) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(meta.str("connectionError",vm.t("正在连接桌面，可先阅读历史","Connecting to desktop; saved history remains readable")),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={vm.postChat("reconnect",payload("activate" to true))},enabled=!vm.busy) {Text(vm.t("重连","Reconnect"))}
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(if(vm.compact) 6.dp else 12.dp)) {
                if(vm.timeline.hasMore) item(key="older") {TextButton(onClick={follow=false;vm.older()},enabled=!vm.historyBusy){Text(vm.t("查看更早内容","Load older messages"))}}
                items(visibleMessages,key={it.str("key")}) { row ->
                    MessageCard(vm,row,onCopy={scope.launch {try {clipboard.setText(AnnotatedString(vm.fullText(row)))}catch(_: Exception){}}})
                }
                for(request in meta.rows("requests")) item(key="approval:${request.str("id")}") { ApprovalCard(vm,request) }
                for(queued in meta.rows("submissions")) item(key="queued:${queued.str("id")}") {
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(vm.t("待发送／结果待确认","Queued / result unconfirmed"));Text(queued.str("text"),maxLines=3)
                        if(queued.str("status") == "queued") TextButton(onClick={vm.postChat("queue",payload("id" to queued.str("id")))},enabled=!vm.busy){Text(vm.t("撤回","Cancel queued message"))}
                    } }
                }
                if(meta.obj("goal").isNotEmpty() || meta.obj("goalSubmission").isNotEmpty()) item(key="goal") {
                    OutlinedButton(onClick={vm.openTool("goal")}) { Text(vm.t("查看目标进度","Goal progress")+" · "+meta.obj("goal").str("status")) }
                }
                for(file in meta.rows("files")) item(key="file:${file.str("id")}") {
                    Column {
                        if(file.bool("image")) GatewayImage(vm,file)
                        TextButton(onClick={download=file;save.launch(file.str("name","download"))}) {Icon(Icons.Default.Download,null);Text(file.str("name",vm.t("保存文件","Save file")))}
                    }
                }
                if(meta.str("status")=="active") item(key="working") {Row {CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp));Text(vm.t("Codex 正在工作…","Codex is working…"))}}
                item(key="bottom") {Spacer(Modifier.height(8.dp))}
            }
            SmallFloatingActionButton(onClick={follow=true;scope.launch{list.animateScrollToItem(maxOf(0,list.layoutInfo.totalItemsCount-1))}},modifier=Modifier.align(Alignment.BottomEnd).padding(8.dp)) {Icon(Icons.Default.ArrowDownward,vm.t("最新消息","Latest messages"))}
        }
        vm.store.get("editFallback:${chat.key}")?.let {
            Text(vm.t("分支中的修改未发送，请编辑最后一条用户消息后重发。","Fork edit was not sent. Edit the last user message to resend."),Modifier.padding(12.dp))

        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal=12.dp,vertical=8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={vm.openTool("model")}) {Text(meta.str("model",vm.t("模型","Model")),maxLines=1)}
                TextButton(onClick={vm.openTool("skills")}) {Text("Skills${if(vm.draft.skills.isNotEmpty()) " (${vm.draft.skills.size})" else ""}")}
                TextButton(onClick={vm.openTool("notifications")}) {Text(vm.t("提醒","Reminders"))}
                if(meta.str("status")=="active") TextButton(onClick={vm.postChat("stop")},enabled=!vm.busy) {Text(vm.t("停止","Stop"),color=MaterialTheme.colorScheme.error)}
            }
            for(file in vm.attachments) Row(verticalAlignment=Alignment.CenterVertically) {
                Text(file.name+" · "+file.status+if(file.error.isNotEmpty()) " · "+file.error else "",Modifier.weight(1f),maxLines=2,style=MaterialTheme.typography.bodySmall)
                if(file.status=="failed") IconButton(onClick={vm.retryAttachment(file.id)},enabled=!vm.busy){Icon(Icons.Default.Refresh,vm.t("重试上传","Retry upload"))}
                IconButton(onClick={vm.removeAttachment(file.id)},enabled=!vm.busy){Icon(Icons.Default.Close,vm.t("移除附件","Remove attachment"))}
            }
            OutlinedTextField(vm.draft.text,{if(it.length<=100000) vm.editDraft(vm.draft.copy(text=it))},modifier=Modifier.fillMaxWidth(),placeholder={Text(vm.t("继续这条聊天…","Continue this chat…"))},maxLines=6,enabled=!vm.busy)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                IconButton(onClick={pick.launch(arrayOf("*/*"))},enabled=!vm.busy){Icon(Icons.Default.AttachFile,vm.t("添加附件","Add files"))}
                Choice(vm.draft.sendMode,listOf("send" to vm.t("发送","Send"),"queue" to vm.t("排队","Queue")) + if(meta.str("status")=="active")listOf("steer" to vm.t("补充","Steer")) else emptyList(),enabled=!vm.busy,onSelect={vm.editDraft(vm.draft.copy(sendMode=it))})
                val goalEnabled=meta.bool("goalRuntimeAvailable",true) && chat.host=="local" && meta.str("status")!="active" && meta.obj("goalSubmission").str("status") !in listOf("pending","unknown") && meta.obj("goal").str("status") in listOf("","complete")
                val modes=listOf("default" to vm.t("普通","Normal"),"plan" to vm.t("计划","Plan")) + if(goalEnabled) listOf("goal" to vm.t("目标","Goal")) else emptyList()
                Choice(vm.draft.workMode,modes,enabled=!vm.busy && vm.draft.sendMode!="steer",onSelect={vm.editDraft(vm.draft.copy(workMode=it))})
                Spacer(Modifier.weight(1f))
                IconButton(onClick={follow=true;vm.send()},enabled=!vm.busy && !meta.bool("loadingHistory") && !meta.bool("activating") && (vm.draft.text.isNotBlank() || vm.attachments.isNotEmpty()) && vm.attachments.all{it.status=="ready"}) {Icon(Icons.AutoMirrored.Filled.Send,vm.t("发送消息","Send message"),tint=MaterialTheme.colorScheme.primary)}
            }
            if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
@Composable fun MessageCard(vm: BridgeViewModel,row: JsonObject,onCopy: ()->Unit) {
    val activity = row.str("role")=="activity" || row.str("kind")=="reasoning"
    var expanded by remember(row.str("key")) { mutableStateOf(!activity) }
    Card(colors=CardDefaults.cardColors(containerColor=if(row.str("role")=="user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if(vm.compact) 10.dp else 16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(activity) row.str("title",row.str("kind")) else when(row.str("role")){"user" -> vm.t("你","You");"error" -> vm.t("错误","Error");else -> "CODEX"},Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                if(activity) IconButton(onClick={expanded=!expanded;if(expanded && row.bool("truncated")) vm.expand(row)}) {Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,vm.t("展开内容","Expand"))}
            }
            val text = vm.details[row.str("key")+":"+row.str("version")] ?: row.str("text")
            if(expanded) {
                MarkdownText(text,vm.fontScale)
                for(attachment in row.rows("attachments")) {
                    if(attachment.str("type") in listOf("localImage","image")) {
                        val imagePath=if(attachment.str("uploadId").isNotEmpty()) "uploads/${attachment.str("uploadId")}/preview" else if(attachment.str("desktopId").isNotEmpty()) "desktop-images/${attachment.str("desktopId")}" else ""
                        if(imagePath.isNotEmpty()) GatewayImage(vm,attachment,imagePath)
                    }
                    Text(attachment.str("name",attachment.str("type")),style=MaterialTheme.typography.bodySmall)
                }
                if(row.bool("truncated") && !vm.details.containsKey(row.str("key")+":"+row.str("version"))) TextButton(onClick={vm.expand(row)},enabled=!vm.busy){Text(vm.t("展开完整内容","Load complete text"))}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=onCopy) {Text(vm.t("复制","Copy"))}
                    if(text.contains("```")) {
                        val clipboard = LocalClipboardManager.current
                        TextButton(onClick={clipboard.setText(AnnotatedString(Regex("```[^\\n]*\\n([\\s\\S]*?)```").findAll(text).joinToString("\n\n") {it.groupValues[1]}))}) {Text(vm.t("复制代码","Copy code"))}
                    }
                    if(row.bool("editable")) TextButton(onClick={vm.openTool("message",JsonObject(row+payload("action" to if(vm.timeline.meta.str("latestUserTurnId")==row.str("turnId")) "edit" else "edit-fork")))},enabled=!vm.busy && row.str("turnStatus")!="inProgress" && !(vm.timeline.meta.str("latestUserTurnId")==row.str("turnId") && vm.timeline.meta.str("status")=="active")) {Text(vm.t("编辑","Edit"))}
                    if(row.bool("forkable")) TextButton(onClick={vm.openTool("message",JsonObject(row+payload("action" to "fork")))},enabled=!vm.busy){Text(vm.t("分支","Fork"))}
                }
            }
        }
    }
}
@Composable fun MarkdownText(text: String,scale: Float=1f) {
    val context=LocalContext.current; val color=MaterialTheme.colorScheme.onSurface
    val textColor=android.graphics.Color.argb(255,(color.red*255).toInt(),(color.green*255).toInt(),(color.blue*255).toInt())
    val markwon=remember(context,scale,textColor) {Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(StrikethroughPlugin.create()).usePlugin(MarkwonInlineParserPlugin.create()).usePlugin(JLatexMathPlugin.create(16f*context.resources.displayMetrics.scaledDensity*scale){it.inlinesEnabled(true);it.theme().textColor(textColor)}).build()}
    AndroidView(factory={TextView(it).apply {setTextIsSelectable(true);movementMethod=LinkMovementMethod.getInstance()}},update={view ->view.textSize=16f*scale;view.setTextColor(textColor);markwon.setMarkdown(view,readableMarkdown(text))},modifier=Modifier.fillMaxWidth().semantics { this.text=AnnotatedString(text) })
}
@Composable fun GatewayImage(vm: BridgeViewModel,file: JsonObject,path: String = "files/${file.str("id")}") {
    var bitmap by remember(path,vm.target?.key) {mutableStateOf<android.graphics.Bitmap?>(null)}
    val target=vm.target; val gateway=vm.api
    LaunchedEffect(target?.key,path) {
        if(target!=null && gateway!=null) try { val bytes=gateway.bytes(target.route(path));bitmap=withContext(Dispatchers.Default){
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            val options=BitmapFactory.Options().apply{inSampleSize=1};while(bounds.outWidth/options.inSampleSize>2048 || bounds.outHeight/options.inSampleSize>2048)options.inSampleSize*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        } } catch(_: Exception){}
    }
    bitmap?.let {Image(it.asImageBitmap(),file.str("name"),Modifier.fillMaxWidth().heightIn(max=280.dp))}
}
@Composable fun Choice(value: String,options: List<Pair<String,String>>,enabled: Boolean=true,onSelect: (String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {TextButton(onClick={open=true},enabled=enabled) {Text(options.find {it.first==value}?.second ?: value,maxLines=1);Icon(Icons.Default.ArrowDropDown,null)}
        DropdownMenu(open,{open=false}) {for((key,label) in options) DropdownMenuItem(text={Text(label)},onClick={open=false;onSelect(key)})}}
}
@Composable fun Confirm(title: String,text: String,dismiss: ()->Unit,yes: ()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=yes){Text("OK")}},dismissButton={TextButton(onClick=dismiss){Icon(Icons.Default.Close,"Cancel")}})
}
@Composable fun TextPrompt(vm: BridgeViewModel,title: String,initial: String,dismiss: ()->Unit,save: (String)->Unit) {
    var value by remember {mutableStateOf(initial)}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={save(value)}){Text(vm.t("保存","Save"))}},dismissButton={TextButton(onClick=dismiss){Text(vm.t("取消","Cancel"))}})
}
