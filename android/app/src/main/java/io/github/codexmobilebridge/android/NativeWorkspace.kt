@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.codexmobilebridge.android

import android.graphics.BitmapFactory
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun NativeWorkspaceScreen(vm:BridgeViewModel) {
    val chat=vm.target ?: return;val gateway=vm.api ?: return
    val work=remember(chat.key){NativeWorkspaceSession(gateway,chat,vm.store,vm.getApplication<Application>(),vm.workspaceTab)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(work,lifecycle){
        val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->work.refresh();Lifecycle.Event.ON_STOP->work.pause();else->Unit}}
        lifecycle.addObserver(observer);work.refresh()
        onDispose{lifecycle.removeObserver(observer);work.dispose()}
    }
    val tools:@Composable ()->Unit = {NativeToolsPane(vm,work)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp
        LaunchedEffect(wide){vm.workspaceChat(wide)}
        LaunchedEffect(vm.workspaceTab){if(work.tab!=vm.workspaceTab)work.choose(vm.workspaceTab)}
        Row(Modifier.fillMaxSize()) {
            if(wide){Box(Modifier.weight(1f).fillMaxHeight()){ChatScreen(vm)};VerticalDivider(Modifier.fillMaxHeight())}
            Box(Modifier.weight(1f).fillMaxHeight()){tools()}
        }
    }
}
@Composable private fun NativeToolsPane(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick=vm::closeWorkspace,label={Text(vm.t("聊天","Chat"))},leadingIcon={Icon(Icons.Default.ChatBubbleOutline,null,Modifier.size(16.dp))})
            for((id,label) in nativeWorkspaceTabs(work.chat.provider))FilterChip(work.tab==id,{work.choose(id)},enabled=!work.busy,label={Text(vm.t(label.first,label.second))})
        }
        if(work.loading || work.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(work.error.isNotEmpty())StatusStrip(work.error,true,work::clearError)
        if(work.notice.isNotEmpty())StatusStrip(work.notice,false,work::clearError)
        Box(Modifier.weight(1f)) {
            when(work.tab){"files"->NativeFiles(vm,work);"git"->NativeGit(vm,work);"terminal"->NativeTerminal(vm,work);"side"->NativeSideChat(vm,work);"agents"->NativeAgents(vm,work)}
        }
    }
}
@Composable private fun NativeFiles(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)work.download(uri)}
    val upload=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)work.upload(uri)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={work.files(work.directory.substringBeforeLast('/',""))},enabled=work.directory.isNotEmpty() && !work.busy){Icon(Icons.AutoMirrored.Filled.ArrowBack,vm.t("上级目录","Parent folder"))}
            Text(work.directory.ifEmpty{work.listing.str("project",vm.t("项目文件","Project files"))},Modifier.weight(1f),maxLines=2,style=MaterialTheme.typography.titleSmall)
            IconButton(onClick={work.files()},enabled=!work.busy){Icon(Icons.Default.Refresh,vm.t("刷新文件","Refresh files"))}
            IconButton(onClick={upload.launch(arrayOf("*/*"))},enabled=!work.busy){Icon(Icons.Default.UploadFile,vm.t("上传文件","Upload file"))}
        }
        Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(work.search,{work.search=it},placeholder={Text(vm.t("筛选当前目录","Filter this folder"))},singleLine=true,modifier=Modifier.weight(1f))
            IconButton(onClick={work.files()},enabled=!work.busy){Icon(Icons.Default.Search,vm.t("查找文件","Find files"))}
        }
        Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            FilterChip(work.hidden,{work.hidden=!work.hidden;work.files()},label={Text(vm.t("隐藏文件","Hidden files"))})
            Spacer(Modifier.weight(1f));Text(work.listing.num("total").toString()+vm.t(" 项"," items"),style=MaterialTheme.typography.bodySmall)
        }
        if(work.downloadProgress.isNotEmpty())Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(work.downloadPath+" · "+work.downloadProgress,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=work::cancelDownload){Text(vm.t("暂停下载","Pause download"))}}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(work.listing.rows("entries"),key={it.str("path")}){row->
                val directory=row.str("kind")=="directory";val allowed=row.str("kind")!="blocked"
                Card(onClick={if(directory)work.files(row.str("path")) else work.preview(row)},enabled=allowed && !work.busy,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(directory)Icons.Default.Folder else Icons.Default.Description,null,tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(row.str("name"),maxLines=2);if(!directory)Text(fileSize(row.num("size")),style=MaterialTheme.typography.labelSmall)}
                        if(allowed)IconButton(onClick={work.prepareDownload(row);save.launch(row.str("name")+if(directory)".zip" else "")},enabled=work.downloadProgress.isEmpty()){Icon(Icons.Default.Download,if(directory)vm.t("下载目录 ZIP","Download folder ZIP") else vm.t("保存文件","Save file"))}
                    }
                }
            }
            if(work.listing["nextOffset"]!=null && work.listing["nextOffset"]!=JsonNull)item{TextButton(onClick={work.files(more=true)},enabled=!work.loading){Text(vm.t("加载更多文件","Load more files"))}}
            if(!work.loading && work.listing.rows("entries").isEmpty())item{Text(vm.t("此目录没有匹配文件","No matching files in this folder"),Modifier.padding(16.dp))}
        }
    }
    work.preview?.let{value->NativePreview(vm,value,work::closePreview){work.prepareDownload(value);save.launch(value.str("name","download"))}}
}
fun fileSize(bytes:Long)=when{bytes>=1024*1024*1024->"%.1f GiB".format(bytes.toDouble()/(1024*1024*1024));bytes>=1024*1024->"%.1f MiB".format(bytes.toDouble()/(1024*1024));bytes>=1024->"%.1f KiB".format(bytes.toDouble()/1024);else->"$bytes B"}
@Composable private fun NativePreview(vm:BridgeViewModel,value:JsonObject,dismiss:()->Unit,download:(()->Unit)?=null) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(value.str("name",value.str("path",vm.t("预览","Preview"))),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2);if(download!=null)IconButton(onClick=download){Icon(Icons.Default.Download,vm.t("保存文件","Save file"))};IconButton(onClick=dismiss){Icon(Icons.Default.Close,vm.t("关闭预览","Close preview"))}}
                if(value.bool("truncated"))Text(vm.t("预览已截断，下载可查看完整文件","Preview truncated; download the complete file"),style=MaterialTheme.typography.bodySmall)
                if(value.str("kind")=="image")NativePreviewImage(value) else {
                    val text=value.str("text").ifEmpty{vm.t("此文件不支持文本预览，请保存后打开。","This file has no text preview. Save it to open.")}
                    val styled=remember(text){buildAnnotatedString{for(line in text.split('\n')){withStyle(SpanStyle(color=when{line.startsWith("+")&&!line.startsWith("+++")->Color(0xFF33825C);line.startsWith("-")&&!line.startsWith("---")->Color(0xFFD76161);line.startsWith("@@")->Color(0xFF728BF1);else->Color.Unspecified})){append(line);append('\n')}}}}
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())){Text(styled,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
}
@Composable private fun NativePreviewImage(value:JsonObject) {
    var bitmap by remember(value.str("path")){mutableStateOf<android.graphics.Bitmap?>(null)}
    var scale by remember{mutableFloatStateOf(1f)};var x by remember{mutableFloatStateOf(0f)};var y by remember{mutableFloatStateOf(0f)}
    LaunchedEffect(value){bitmap=withContext(Dispatchers.Default){runCatching{val bytes=android.util.Base64.decode(value.str("data"),android.util.Base64.DEFAULT);val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);val options=BitmapFactory.Options().apply{inSampleSize=1};while(bounds.outWidth/options.inSampleSize>2048 || bounds.outHeight/options.inSampleSize>2048)options.inSampleSize*=2;BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)}.getOrNull()}}
    Box(Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,8f);x=(x+pan.x).coerceIn(-2000f,2000f);y=(y+pan.y).coerceIn(-2000f,2000f)}},contentAlignment=Alignment.Center){bitmap?.let{Image(it.asImageBitmap(),value.str("name"),Modifier.fillMaxWidth().graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y})}}
}
@Composable private fun NativeGit(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    var confirm by remember{mutableStateOf<JsonObject?>(null)};var message by remember{mutableStateOf("")};var branch by remember{mutableStateOf("")}
    fun fields(action:String,vararg params:Pair<String,Any?>)=JsonObject(payload("action" to action,"version" to work.git.str("version"))+payload(*params))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AccountTree,null,Modifier.size(18.dp));Text("  "+work.git.str("branch",vm.t("Git 工作区","Git workspace")),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);IconButton(onClick={work.loadGit()},enabled=!work.busy){Icon(Icons.Default.Refresh,vm.t("刷新 Git","Refresh Git"))}}
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){for((id,label) in listOf("changes" to vm.t("改动","Changes"),"history" to vm.t("历史","History"),"branches" to vm.t("分支","Branches")))FilterChip(work.gitView==id,{work.loadGit(id)},enabled=!work.busy,label={Text(label)})}
        if(!work.git.bool("available")){Text(work.git.str("message",vm.t("正在读取 Git…","Reading Git…")),Modifier.padding(16.dp));return}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(work.gitView=="changes") {
                val entries=work.git.rows("entries")
                item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={work.gitAction(fields("stage-all"))},enabled=!work.busy && entries.isNotEmpty()){Text(vm.t("全部暂存","Stage all"))};OutlinedButton(onClick={work.gitAction(fields("unstage-all"))},enabled=!work.busy && entries.any{!it.bool("untracked") && it.str("index")!="."}){Text(vm.t("取消暂存","Unstage all"))}}}
                for((section,label,rows) in listOf(Triple("conflict",vm.t("冲突","Conflicts"),entries.filter{it.bool("conflict")}),Triple("staged",vm.t("已暂存","Staged"),entries.filter{!it.bool("conflict") && !it.bool("untracked") && it.str("index")!="."}),Triple("unstaged",vm.t("工作区","Working tree"),entries.filter{!it.bool("conflict") && (it.bool("untracked") || it.str("worktree")!=".")}))) {
                    if(rows.isNotEmpty())item{Text("$label · ${rows.size}",style=MaterialTheme.typography.labelLarge)}
                    items(rows,key={section+it.str("path")}){row->Card(onClick={work.gitDiff(row,section)},modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){Text(row.str("path"),Modifier.weight(1f).padding(horizontal=8.dp),maxLines=3);TextButton(onClick={work.gitAction(fields(if(section=="staged")"unstage" else "stage","path" to row.str("path")))},enabled=!work.busy && !row.bool("submodule")){Text(if(section=="staged")vm.t("撤回暂存","Unstage") else vm.t("暂存","Stage"))}}}}
                }
                if(entries.isEmpty())item{Text(vm.t("工作区没有文件改动","Working tree is clean"))}
                item{OutlinedTextField(message,{message=it},label={Text(vm.t("提交说明","Commit message"))},modifier=Modifier.fillMaxWidth(),maxLines=5);Button(onClick={confirm=fields("commit","message" to message)},enabled=!work.busy && message.isNotBlank() && (entries.any{!it.bool("untracked") && it.str("index")!="."} || work.git.str("operation")=="merge")){Text(vm.t("提交已暂存内容","Commit staged changes"))}}
                if(work.git.str("operation")=="merge")item{TextButton(onClick={confirm=fields("abort-merge")},enabled=!work.busy){Text(vm.t("中止合并","Abort merge"))}}
            } else if(work.gitView=="history") {
                items(work.gitList.rows("commits"),key={it.str("id")}){row->Card(onClick={work.commitDetail(row)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(row.str("subject"));Text(row.str("id").take(8)+" · "+row.str("author")+" · "+row.str("date"),style=MaterialTheme.typography.bodySmall);if(row.str("refs").isNotEmpty())Text(row.str("refs"),style=MaterialTheme.typography.labelSmall)}}}
                if(work.gitList.bool("hasMore"))item{TextButton(onClick=work::olderCommits,enabled=work.historyLimit<1000){Text(vm.t("加载更早提交","Load older commits"))}}
            } else {
                item{OutlinedTextField(branch,{branch=it},label={Text(vm.t("新分支名称","New branch name"))},modifier=Modifier.fillMaxWidth());Button(onClick={confirm=fields("create-branch","branch" to branch.trim())},enabled=!work.busy && branch.isNotBlank() && work.git.str("scope")=="." && work.git.str("operation").isBlank()){Text(vm.t("创建并切换","Create and switch"))}}
                items(work.gitList.rows("branches"),key={it.str("name")}){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(row.str("name"));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={confirm=fields("switch-branch","branch" to row.str("name"))},enabled=!work.busy && !row.bool("current") && work.git.str("scope")=="." && work.git.str("operation").isBlank()){Text(vm.t("切换","Switch"))};OutlinedButton(onClick={confirm=fields("merge","branch" to row.str("name"))},enabled=!work.busy && !row.bool("current") && work.git.str("scope")=="." && work.git.str("operation").isBlank()){Text(vm.t("合并到当前分支","Merge into current"))}}}}}
            }
        }
    }
    confirm?.let{body->val operation=body.str("action");Confirm(vm.t("确认 Git 操作","Confirm Git operation"),when(operation){"commit"->vm.t("提交已暂存内容，使用电脑上的 Git 身份和 hooks。","Commit staged changes using the computer's Git identity and hooks.")+"\n"+body.str("message");"abort-merge"->vm.t("将撤销本次合并中的冲突处理并恢复合并前状态。","Discard conflict resolution from this merge and restore the previous state.");else->vm.t("当前分支：","Current branch: ")+work.git.str("branch")+"\n"+body.str("branch")+"\n"+vm.t("操作会更新工作区；合并可能产生冲突。","This updates working files. A merge may produce conflicts.")},{confirm=null}){confirm=null;work.gitAction(body)}}
    work.diff?.let{NativePreview(vm,it,work::closePreview)}
    work.gitDetail?.let{commit->Dialog(onDismissRequest=work::closePreview){Surface(shape=RoundedCornerShape(16.dp)){LazyColumn(Modifier.heightIn(max=600.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text(commit.str("message"));Text(commit.str("author")+" · "+commit.str("date"),style=MaterialTheme.typography.bodySmall)};items(commit.rows("entries"),key={it.str("path")}){row->TextButton(onClick={work.historicalDiff(row.str("path"),commit.str("id"))}){Text(row.str("path"))}};item{TextButton(onClick=work::closePreview){Text(vm.t("关闭","Close"))}}}}}}
}
@Composable private fun NativeTerminal(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    var command by remember(work.chat.key){mutableStateOf("")}
    var discard by remember(work.chat.key){mutableStateOf(false)}
    val scroll=rememberScrollState()
    LaunchedEffect(work.terminalText){scroll.scrollTo(scroll.maxValue)}
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text(work.terminal.str("shell",vm.t("连续终端","Persistent terminal")),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium);TextButton(onClick=work::clearTerminal){Text(vm.t("清屏","Clear"))};TextButton(onClick={work.terminalAction(if(work.terminal.bool("running"))"close" else "open")},enabled=!work.busy){Text(if(work.terminal.bool("running"))vm.t("结束终端","Close terminal") else vm.t("打开终端","Open terminal"))}}
        Text(work.terminal.str("cwd"),style=MaterialTheme.typography.bodySmall)
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF11151B),RoundedCornerShape(12.dp)).verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(12.dp)){Text(work.terminalText, color=Color(0xFFE2E8F0),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){for((label,data) in listOf("Ctrl+C" to "\u0003","Tab" to "\t","Esc" to "\u001b","↑" to "\u001b[A","↓" to "\u001b[B","←" to "\u001b[D","→" to "\u001b[C"))TextButton(onClick={work.terminalAction("input",data)},enabled=!work.busy && work.terminal.bool("running") && !work.inputUnconfirmed){Text(label)}}
        OutlinedTextField(command,{command=it},modifier=Modifier.fillMaxWidth(),placeholder={Text(vm.t("输入命令或回复","Enter a command or reply"))},maxLines=4)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){if(work.inputUnconfirmed){TextButton(onClick=work::retryTerminalInput,enabled=!work.busy){Text(vm.t("核验原输入","Retry original input"))};TextButton(onClick={discard=true},enabled=!work.busy){Text(vm.t("放弃待核验输入","Discard pending input"))}};Spacer(Modifier.weight(1f));Button(onClick={work.terminalAction("input",command+"\r");command=""},enabled=!work.busy && work.terminal.bool("running") && !work.inputUnconfirmed){Text(vm.t("发送到终端","Send to terminal"))}}
    }
    if(discard)Confirm(vm.t("放弃待核验输入？","Discard pending input?"),vm.t("电脑可能已经执行了这次输入，请先检查终端记录。放弃后不会再次发送，也不会撤销电脑上的执行。","The computer may have executed this input. Check the terminal first. Discarding will neither resend it nor undo execution."),{discard=false}){discard=false;work.discardTerminalInput()}
}
@Composable private fun NativeSideChat(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    var text by remember(work.chat.key){mutableStateOf(work.store.get("side-draft:${work.chat.key}") ?: "")}
    var end by remember{mutableStateOf(false)};var model by remember{mutableStateOf("")};var effort by remember{mutableStateOf("medium")}
    var mode by remember{mutableStateOf("send")};var workMode by remember{mutableStateOf("default")}
    var skills by remember{mutableStateOf(emptyList<String>())};var skillsOpen by remember{mutableStateOf(false)};var permissionOpen by remember{mutableStateOf(false)};var fullAccess by remember{mutableStateOf(false)}
    val attachment=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)work.uploadSide(uri)}
    val connected=work.side.str("id").isNotBlank()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(vm.t("临时侧边聊天","Temporary side chat"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);if(connected){TextButton(onClick={work.sideAction("stop")},enabled=!work.busy){Text(vm.t("停止","Stop"))};TextButton(onClick={end=true},enabled=!work.busy){Text(vm.t("结束聊天","End chat"))}}}
        if(!connected){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(vm.t("在当前项目中创建临时 Codex 聊天，共享工作目录；聊天结束或网关重启后清除。","Create a temporary Codex chat in this project. It shares the working directory and is cleared when ended or the gateway restarts."));Button(onClick={work.sideAction("create")},enabled=!work.busy && work.chat.host=="local"){Text(vm.t("创建侧边聊天","Create side chat"))}};return}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            for(turn in work.side.rows("turns"))items(turn.rows("messages"),key={it.str("id")}){row->Column{Text(if(row.str("role")=="user")vm.t("你","You") else "CODEX",style=MaterialTheme.typography.labelSmall);MarkdownText(row.str("text"),vm.fontScale)}}
            items(work.side.rows("requests"),key={it.str("id")}){request->ApprovalCard(vm,request,enabledOverride=!work.busy,onRespond={response->work.sideAction("respond",payload("requestId" to request.str("id"),"response" to response))})}
            items(work.side.rows("submissions"),key={it.str("id")}){row->Card{Column(Modifier.padding(12.dp)){Text(row.str("text"),maxLines=3);Text(row.str("status"),style=MaterialTheme.typography.bodySmall);if(row.str("status")=="queued")TextButton(onClick={work.sideAction("cancel-queued",payload("submissionId" to row.str("id")))},enabled=!work.busy){Text(vm.t("撤回","Cancel queued message"))}}}}
            if(work.side.str("error").isNotBlank())item{Text(work.side.str("error"),color=MaterialTheme.colorScheme.error)}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)){TextButton(onClick={work.sideAction("catalog")},enabled=!work.busy){Text(work.side.str("model",vm.t("模型","Model")))};TextButton(onClick={skillsOpen=true;work.sideAction("skills",payload("query" to "","offset" to 0,"selected" to skills))},enabled=!work.busy){Text("Skills (${skills.size})")};TextButton(onClick={permissionOpen=true},enabled=!work.busy){Text(vm.t("权限","Permissions"))};Choice(mode,listOf("send" to vm.t("发送","Send"),"queue" to vm.t("排队","Queue"),"steer" to vm.t("补充","Steer"))){mode=it};Choice(workMode,listOf("default" to vm.t("普通","Normal"),"plan" to vm.t("计划","Plan"))){workMode=it}}
        if(work.sideCatalog.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)){val models=work.sideCatalog.rows("models");Choice(model,models.map{it.str("id") to it.str("name",it.str("id"))}){model=it};val efforts=models.find{it.str("id")==model}?.strings("efforts").orEmpty().ifEmpty{listOf("low","medium","high")};Choice(effort,efforts.map{it to it}){effort=it};TextButton(onClick={work.sideAction("settings",payload("model" to model,"effort" to effort))},enabled=!work.busy && model.isNotEmpty()){Text(vm.t("应用","Apply"))}}
        for(file in work.sideFiles)Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(file.name+" · "+file.status,Modifier.weight(1f),maxLines=1,style=MaterialTheme.typography.bodySmall);if(file.status=="failed")IconButton(onClick={work.retrySideFile(file.id)},enabled=!work.busy){Icon(Icons.Default.Refresh,vm.t("重试上传","Retry upload"))};IconButton(onClick={work.removeSideFile(file.id)},enabled=!work.busy){Icon(Icons.Default.Close,vm.t("移除附件","Remove attachment"))}}
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={attachment.launch(arrayOf("*/*"))},enabled=!work.busy){Icon(Icons.Default.AttachFile,vm.t("侧边聊天附件","Side chat attachment"))};OutlinedTextField(text,{if(it.length<=100000){text=it;work.store.put("side-draft:${work.chat.key}",it)}},placeholder={Text(vm.t("继续侧边聊天…","Continue the side chat…"))},maxLines=5,modifier=Modifier.weight(1f));IconButton(onClick={work.sideAction("send",payload("text" to text,"mode" to mode,"skills" to skills,"workMode" to if(mode=="steer")null else workMode))},enabled=!work.busy && (text.isNotBlank() || work.sideFiles.isNotEmpty()) && work.sideFiles.all{it.status=="ready"}){Icon(Icons.AutoMirrored.Filled.Send,vm.t("发送侧边消息","Send side message"))}}
        LaunchedEffect(work.busy){if(!work.busy && work.store.get("side-draft:${work.chat.key}")==null)text=""}
    }
    if(skillsOpen)Dialog(onDismissRequest={skillsOpen=false}){Surface(shape=RoundedCornerShape(16.dp)){LazyColumn(Modifier.heightIn(max=600.dp).padding(16.dp)){item{Text("Skills",style=MaterialTheme.typography.titleMedium)};items(work.sideSkills.rows("skills"),key={it.str("id")}){row->val id=row.str("id");Toggle(row.str("displayName",row.str("name")),id in skills,enabled=!work.busy && row.bool("selectable",true) && (id in skills || skills.size<8)){on->skills=if(on)skills+id else skills-id};Text(row.str("description"),style=MaterialTheme.typography.bodySmall)};item{TextButton(onClick={skillsOpen=false}){Text(vm.t("完成","Done"))}}}}}
    if(permissionOpen)AlertDialog(onDismissRequest={permissionOpen=false},title={Text(vm.t("侧边聊天权限","Side chat permissions"))},text={Column{for((preset,label) in listOf("ask" to vm.t("请求批准","Ask for approval"),"auto-review" to vm.t("帮我批准","Automatic review"),"full-access" to vm.t("完全访问权限","Full access")))TextButton(onClick={if(preset=="full-access")fullAccess=true else{work.sideAction("permissions",payload("preset" to preset,"confirmed" to false));permissionOpen=false}},enabled=!work.busy){Text(label)}}},confirmButton={TextButton(onClick={permissionOpen=false}){Text(vm.t("关闭","Close"))}})
    if(fullAccess)Confirm(vm.t("允许完全访问？","Allow full access?"),vm.t("此侧边聊天将能访问电脑文件和网络，无需逐次确认。","This side chat can access computer files and the network without per-operation approval."),{fullAccess=false}){fullAccess=false;permissionOpen=false;work.sideAction("permissions",payload("preset" to "full-access","confirmed" to true))}
    if(end)Confirm(vm.t("结束侧边聊天？","End side chat?"),vm.t("会结束当前任务并清除这条临时聊天。","Ends the active task and clears this temporary chat."),{end=false}){end=false;work.sideAction("close")}
}
@Composable private fun NativeAgents(vm:BridgeViewModel,work:NativeWorkspaceSession) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(vm.t("当前聊天派生的任务","Tasks created by this chat"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);IconButton(onClick=work::loadAgents){Icon(Icons.Default.Refresh,vm.t("刷新子智能体","Refresh agents"))}}}
        items(work.agents.rows("agents"),key={it.str("id")}){row->Card(onClick={work.agent(row)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(row.str("nickname",row.str("title")));Text(row.str("title"));Text(row.str("role")+" · "+row.str("path"),style=MaterialTheme.typography.bodySmall)}}}
        if(work.agents.rows("agents").isEmpty())item{Text(vm.t("此聊天还没有已保存的子智能体任务","No saved agent tasks for this chat"))}
    }
    work.agentDetail?.let{detail->Dialog(onDismissRequest=work::closePreview,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){LazyColumn(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Row{Text(detail.str("title",vm.t("子智能体记录","Agent history")),Modifier.weight(1f));TextButton(onClick=work::closePreview){Text(vm.t("关闭","Close"))}}};for(turn in detail.rows("turns"))items(turn.rows("messages"),key={it.str("id")}){row->MarkdownText(row.str("text"),vm.fontScale)}}}}}
}

@Composable fun NativeImageViewer(bitmap:android.graphics.Bitmap,name:String,dismiss:()->Unit) {
    var scale by remember{mutableFloatStateOf(1f)};var x by remember{mutableFloatStateOf(0f)};var y by remember{mutableFloatStateOf(0f)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Color(0xFF17181C)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.weight(1f),color=Color.White,maxLines=2);IconButton(onClick={scale=1f;x=0f;y=0f}){Icon(Icons.Default.FitScreen,"Reset zoom",tint=Color.White)};IconButton(onClick=dismiss){Icon(Icons.Default.Close,"Close image",tint=Color.White)}}
                Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,8f);x=(x+pan.x).coerceIn(-2000f,2000f);y=(y+pan.y).coerceIn(-2000f,2000f)}},contentAlignment=Alignment.Center){Image(bitmap.asImageBitmap(),name,Modifier.fillMaxWidth().graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y})}
            }
        }
    }
}
