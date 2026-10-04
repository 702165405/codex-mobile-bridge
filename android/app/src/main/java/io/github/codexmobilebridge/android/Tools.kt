@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.codexmobilebridge.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.*

@Composable fun Toggle(label: String,value: Boolean,enabled: Boolean=true,change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth()) { Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(value,change,enabled=enabled) }
}
@Composable fun ToolDialog(vm: BridgeViewModel) {
    val name=vm.tool ?: return
    Dialog(onDismissRequest=vm::closeTool) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max=720.dp).padding(20.dp)) {
                Row {Text(vm.t("工具与设置","Tools & settings"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=vm::closeTool,enabled=!vm.busy){Text(vm.t("关闭","Close"))}}
                if(vm.toolLoading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(vm.error.isNotBlank()) Text(vm.error,color=MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    key(name,vm.selected?.id,vm.target?.key,vm.toolLoading) { if(!vm.toolLoading) ToolContent(vm,name,vm.toolData) }
                }
            }
        }
    }
}
@Composable fun ToolContent(vm: BridgeViewModel,name: String,data: JsonObject) {
    val ready=!vm.busy && !vm.toolLoading
    when(name) {
        "appearance" -> {
            Text(vm.t("主题","Theme"));Choice(vm.theme,listOf("system" to vm.t("跟随系统","System"),"light" to vm.t("浅色","Light"),"dark" to vm.t("深色","Dark"))){vm.preference("theme",it)}
            Text(vm.t("语言","Language"));Choice(vm.language,listOf("system" to vm.t("跟随系统","System"),"zh" to "中文","en" to "English")){vm.preference("language",it)}
            Toggle(vm.t("显示思考过程","Show reasoning"),vm.showReasoning){vm.preference("reasoning",it.toString())}
            Toggle(vm.t("显示执行过程","Show execution"),vm.showProcess){vm.preference("process",it.toString())}
            Toggle(vm.t("紧凑布局","Compact layout"),vm.compact){vm.preference("compact",it.toString())}
            Text(vm.t("字号","Font size"));Slider(vm.fontScale,{vm.preference("fontScale",it.toString())},valueRange=.8f..1.5f)
        }
        "logout" -> {Text(vm.t("退出当前电脑的登录？其他电脑不受影响。","Sign out of this computer? Other computers keep their sessions."));Button(onClick=vm::logout,enabled=ready){Text(vm.t("退出登录","Sign out"))}}
        "details" -> {
            val meta=vm.timeline.meta
            Text(meta.str("cwd"));Text(meta.str("hostLabel",vm.target?.host ?: ""));Text(meta.str("provider"))
            var title by remember {mutableStateOf(meta.str("title"))}
            OutlinedTextField(title,{if(it.length<=120)title=it},label={Text(vm.t("聊天名称","Chat name"))})
            Button(onClick={vm.postChat("rename",payload("title" to title.trim()))},enabled=ready && title.isNotBlank() && title.none{it.isISOControl()}){Text(vm.t("重命名","Rename"))}
            OutlinedButton(onClick={vm.postChat("history")},enabled=ready && !meta.bool("historyComplete")){Text(vm.t("读取完整桌面历史","Load desktop history"))}
            OutlinedButton(onClick={vm.postChat("reconnect",payload("activate" to true))},enabled=ready){Text(vm.t("重连桌面","Reconnect desktop"))}
        }
        "new" -> {
            val projects=data.rows("projects");var project by remember {mutableStateOf(projects.firstOrNull()?.str("key") ?: "")};var title by remember {mutableStateOf("")}
            Text(vm.t("选择项目","Choose project"));Choice(project,projects.map{it.str("key") to (it.str("name")+" · "+it.str("host","local"))}){project=it}
            OutlinedTextField(title,{if(it.length<=120)title=it},label={Text(vm.t("名称（可选）","Name (optional)"))})
            Button(onClick={vm.perform("/api/sessions",payload("project" to project,"title" to title),durableKey="new:$project:$title")},enabled=ready && project.isNotEmpty()){Text(vm.t("创建聊天","Create chat"))}
        }
        "model" -> {
            val models=data.rows("models");var model by remember {mutableStateOf(vm.timeline.meta.str("model",data.str("currentModel")))}
            var custom by remember {mutableStateOf(model)};var effort by remember(model){mutableStateOf(vm.timeline.meta.str("effort",models.find{it.str("id")==model}?.str("defaultEffort","medium") ?: "medium"))}
            var fast by remember {mutableStateOf(vm.timeline.meta.str("serviceTier")=="fast")};var dirty by remember {mutableStateOf(false)}
            Choice(if(models.any{it.str("id")==model})model else "custom",models.map{it.str("id") to it.str("name",it.str("id"))}+listOf("custom" to vm.t("自定义模型","Custom model"))){model=it;dirty=false}
            if(model=="custom" || models.none{it.str("id")==model}) OutlinedTextField(custom,{custom=it},label={Text(vm.t("模型 ID","Model ID"))})
            val current=models.find{it.str("id")==model};Text(current?.str("description") ?: "")
            val efforts=current?.strings("efforts")?.ifEmpty{null} ?: listOf("none","minimal","low","medium","high","xhigh","max","ultra")
            LaunchedEffect(model){if(effort !in efforts)effort=current?.str("defaultEffort")?.takeIf{it in efforts} ?: efforts.first()}
            Choice(effort,efforts.map{it to it}){effort=it}
            val allowed=data.obj("fastMode").bool("allowed") && current?.bool("fastTier")==true && vm.timeline.meta.str("provider","openai")=="openai"
            Toggle("Fast",fast,ready && allowed){fast=it;dirty=true}
            if(!allowed) Text(vm.t("当前模型或网关不支持 Fast","Fast is unavailable for this model or gateway"))
            Button(onClick={val fields=payload("model" to if(current!=null)model else custom.trim(),"effort" to effort);vm.postChat("settings",if(dirty && allowed)JsonObject(fields+payload("fastMode" to fast))else fields)},enabled=ready && vm.timeline.meta.bool("connected") && (current!=null || custom.isNotBlank())){Text(vm.t("应用设置","Apply settings"))}
        }
        "skills" -> {
            var q by remember {mutableStateOf("")};OutlinedTextField(q,{q=it},label={Text(vm.t("搜索 Skill","Search skills"))})
            for(skill in data.rows("skills").filter{it.toString().contains(q,true)}) {
                val id=skill.str("id");Toggle(skill.str("displayName",skill.str("name")),id in vm.draft.skills,ready && (id in vm.draft.skills || vm.draft.skills.size<8)){on ->vm.editDraft(vm.draft.copy(skills=if(on)vm.draft.skills+id else vm.draft.skills-id))};Text(skill.str("description"),style=MaterialTheme.typography.bodySmall)
            }
        }
        "notifications","defaults" -> {
            if(name=="notifications") {
                Text(vm.t("聊天提醒可继承电脑的默认设置","Chat reminders can inherit the computer defaults"))
                val options=listOf("inherit" to vm.t("继承默认","Default"),"on" to vm.t("开启","On"),"off" to vm.t("关闭","Off"))
                var requests by remember {mutableStateOf(data.str("requests","inherit"))};var completion by remember{mutableStateOf(data.str("completion","inherit"))}
                Text(vm.t("审批与问题","Approvals & questions"));Choice(requests,options){requests=it}
                Text(vm.t("完成提醒","Completion"));Choice(completion,options){completion=it}
                Button(onClick={vm.postChat("notifications",payload("requests" to requests,"completion" to completion))},enabled=ready){Text(vm.t("保存","Save"))}
            } else {
                var requests by remember{mutableStateOf(data.bool("requests"))};var completion by remember{mutableStateOf(data.bool("completion"))}
                Toggle(vm.t("审批与问题提醒","Approvals & questions"),requests,ready){requests=it};Toggle(vm.t("完成提醒","Completion"),completion,ready){completion=it}
                Button(onClick={vm.perform("/api/notifications/defaults",payload("requests" to requests,"completion" to completion))},enabled=ready){Text(vm.t("保存","Save"))}
            }
            if(!data.bool("available",true)) Text(vm.t("请先配置 PushPlus，或在电脑配置 ntfy／Bark","Configure PushPlus, or ntfy/Bark on the computer first"))
        }
        "pushplus" -> {
            var enabled by remember{mutableStateOf(data.bool("pushplusEnabled"))};var token by remember{mutableStateOf("")};var clear by remember{mutableStateOf(false)}
            Toggle(vm.t("启用 PushPlus","Enable PushPlus"),enabled,ready){enabled=it}
            Text(vm.t(if(data.bool("hasPushplusToken"))"已保存 Token，留空可保留" else "尚未保存 Token",if(data.bool("hasPushplusToken"))"Token saved; leave empty to keep it" else "No saved token"))
            OutlinedTextField(token,{token=it},label={Text("Token")},visualTransformation=PasswordVisualTransformation(),enabled=ready)
            Toggle(vm.t("清除已保存 Token","Clear saved token"),clear,ready){clear=it}
            Button(onClick={vm.perform("/api/notifications/pushplus",payload("pushplusEnabled" to enabled,"pushplusToken" to token,"clearPushplusToken" to clear))},enabled=ready){Text(vm.t("保存","Save"))}
            OutlinedButton(onClick={vm.perform("/api/notifications/pushplus/test",payload())},enabled=ready && data.bool("pushplusEnabled") && data.bool("hasPushplusToken")){Text(vm.t("发送测试通知（使用已保存设置）","Send test using saved settings"))}
        }
        "goal" -> {
            val goal=vm.timeline.meta.obj("goal");val status=goal.str("status");var objective by remember{mutableStateOf(goal.str("objective"))}
            val labels=mapOf("active" to vm.t("目标进行中","Goal active"),"paused" to vm.t("目标已暂停","Goal paused"),"blocked" to vm.t("目标等待处理","Goal blocked"),"usageLimited" to vm.t("已达到用量限制","Usage limit reached"),"budgetLimited" to vm.t("已达到预算限制","Budget limit reached"),"complete" to vm.t("目标已完成","Goal complete"))
            Text(labels[status] ?: vm.t("尚未创建目标","No goal yet"))
            if(goal["tokensUsed"]!=null)Text(vm.t("已用 Token：","Tokens used: ")+goal.str("tokensUsed")+if(goal["tokenBudget"]!=null)" / "+goal.str("tokenBudget") else "")
            val pending=vm.timeline.meta.obj("goalSubmission").str("status")
            if(pending.isNotEmpty())Text(vm.t(if(pending=="pending")"正在开启目标…" else "目标请求结果待确认，请检查聊天",if(pending=="pending")"Starting goal…" else "Goal request unconfirmed; inspect the chat"))
            if(vm.target?.host!="local" || !vm.timeline.meta.bool("goalRuntimeAvailable",true))Text(vm.t("此主机暂不支持目标模式","Goal mode is unavailable on this host"))
            val canPause=status in listOf("active","blocked","usageLimited","budgetLimited")
            OutlinedTextField(objective,{objective=it},label={Text(vm.t("目标","Objective"))},maxLines=8)
            Button(onClick={vm.goalAction("edit",payload("objective" to objective))},enabled=ready && objective.isNotBlank() && status=="paused"){Text(vm.t("修改目标","Edit goal"))}
            Button(onClick={vm.goalAction("status",payload("status" to if(canPause)"paused" else "active"))},enabled=ready && (canPause || status=="paused")){Text(vm.t(if(canPause)"暂停目标" else "恢复目标",if(canPause)"Pause goal" else "Resume goal"))}
            var cancel by remember{mutableStateOf(false)};TextButton(onClick={cancel=true},enabled=ready && goal.isNotEmpty()){Text(vm.t("取消目标","Cancel goal"))}
            if(cancel) Confirm(vm.t("取消目标？","Cancel goal?"),objective,{cancel=false}){cancel=false;vm.goalAction("cancel")}
        }
        "message" -> {
            var text by remember{mutableStateOf(data.str("editText",data.str("text")))};var loaded by remember{mutableStateOf(!data.bool("truncated"))};var failure by remember{mutableStateOf("")}
            LaunchedEffect(data){try{val full=vm.fullText(data);text=data.str("editText",full);loaded=true}catch(e:Exception){failure=e.message ?: "Failed"}}
            Text(failure);val kind=data.str("action");Text(vm.t(if(kind=="fork")"创建独立分支？" else if(kind=="edit-fork")"修改历史消息并创建分支" else "编辑最后一条消息并重新发送",if(kind=="fork")"Create a separate branch?" else if(kind=="edit-fork")"Edit history and fork" else "Edit last message and resend"))
            if(kind!="fork")OutlinedTextField(text,{text=it;vm.target?.let{chat->vm.store.put("editDraft:${chat.key}:${data.str("key")}",it)}},maxLines=12,enabled=ready && loaded)
            Button(onClick={vm.messageAction(kind,data,text)},enabled=ready && loaded && (kind=="fork" || text.isNotBlank())){Text(vm.t("确认","Confirm"))}
        }
        "accounts" -> AccountsTool(vm,data)
        "usage" -> UsageTool(vm,data)
    }
}
@Composable fun AccountsTool(vm: BridgeViewModel,data: JsonObject) {
    var switch by remember{mutableStateOf<JsonObject?>(null)}
    val phase=data.obj("switch").str("phase","idle")
    val switching=phase !in listOf("idle","complete","restored","failed")
    Text(when(phase){"idle"->vm.t("选择已保存的账户","Choose a saved account");"complete"->vm.t("切换完成","Switch complete");"failed"->vm.t("切换失败，请检查桌面","Switch failed; check desktop");"restored"->vm.t("已恢复原账户","Original account restored");"interrupted"->vm.t("请在桌面恢复账户","Recover the account on desktop");else->vm.t("正在切换账户…","Switching account…")})
    Text(data.obj("switch").str("error",data.str("error")))
    if(data.rows("accounts").isEmpty())Text(vm.t("请先在电脑端添加官方账户或 API 接入","Add an official account or API connection on the computer first"))
    for(account in data.rows("accounts")) {
        HorizontalDivider();Text(account.str("name",account.str("label",account.str("id"))),style=MaterialTheme.typography.titleMedium)
        Text(account.str("email"));if(account.str("kind")=="api")Text(account.str("baseUrl")+" · "+account.str("model"));if(account.str("id")==data.str("activeId"))Text(vm.t("当前账户","Active account"))
        Row {for(section in listOf("usage","models"))TextButton(onClick={vm.perform("/api/accounts/details",payload("id" to account.str("id"),"section" to section,"refresh" to true),true)},enabled=!vm.busy){Text(vm.t(if(section=="usage")"刷新额度" else "读取模型",if(section=="usage")"Refresh usage" else "Read models"))}}
        val usage=account.obj("details").obj("usage");val models=account.obj("details").obj("models")
        if(usage.str("status")=="loading" || models.str("status")=="loading")Text(vm.t("正在读取详情…","Loading details…"))
        if(usage.str("error").isNotEmpty())Text(usage.str("error"))
        QuotaRows(vm,usage)
        if(models.str("error").isNotEmpty())Text(models.str("error"))
        for(model in models.rows("models"))Text(model.str("name",model.str("id"))+if(model.str("name")!=model.str("id"))" · "+model.str("id") else "")
        Button(onClick={switch=account},enabled=!vm.busy && !switching && data.bool("canSwitch") && account.str("id")!=data.str("activeId")){Text(vm.t("切换至此账户","Switch to this account"))}
    }
    if(!data.bool("canSwitch"))Text(vm.t("当前网关暂不允许切换；请检查桌面任务和权限","Switching is unavailable; check desktop tasks and permissions"))
    for(blocker in data.rows("blockers"))Text(vm.t("待处理任务：","Pending task: ")+blocker.str("title"))
    switch?.let {account ->Confirm(vm.t("确认切换账户？","Switch account?"),account.str("name",account.str("id"))+"\n"+vm.t("将结束此电脑的所有任务并重启 Codex 桌面。","All tasks on this computer will end and Codex desktop will restart."),{switch=null}){switch=null;vm.perform("/api/accounts/switch",payload("id" to account.str("id"),"confirmed" to true,"tasksConfirmed" to true),true,"switch:${account.str("id")}","requestId")}}
}
@Composable fun JsonReadout(value: JsonElement) { if(value!=JsonNull && value.toString()!="{}") MarkdownText("```json\n"+Json{prettyPrint=true}.encodeToString(JsonElement.serializer(),value)+"\n```",.85f) }
fun displayTime(vm: BridgeViewModel,value: JsonObject,key: String): String {
    val seconds=(value[key] as? JsonPrimitive)?.doubleOrNull ?: return "—"
    if(!seconds.isFinite())return "—"
    val locale=if(vm.english)Locale.US else Locale.SIMPLIFIED_CHINESE
    return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,locale).format(Date((seconds*1000).toLong()))
}
@Composable fun QuotaRows(vm: BridgeViewModel,data: JsonObject) {
    for(bucket in data.rows("limits")) {
        Text(bucket.str("name"),style=MaterialTheme.typography.titleSmall)
        for(window in bucket.rows("windows")) {
            val remaining=(window["remainingPercent"] as? JsonPrimitive)?.floatOrNull
            Text(vm.t("剩余 ","Remaining ")+(remaining?.toString() ?: "—")+"% · "+window.str("windowDurationMins")+vm.t(" 分钟"," min"))
            if(remaining!=null)LinearProgressIndicator(progress={remaining.coerceIn(0f,100f)/100f},modifier=Modifier.fillMaxWidth())
            Text(vm.t("恢复时间：","Resets: ")+displayTime(vm,window,"resetsAt"),style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun UsageTool(vm: BridgeViewModel,data: JsonObject) {
    Text(data.str("email",data.str("loginType")));Text(data.str("planType"));Text(data.str("error"))
    QuotaRows(vm,data)
    OutlinedButton(onClick={vm.openTool("usage")},enabled=!vm.busy){Text(vm.t("刷新额度","Refresh usage"))}
    val credits=data.obj("resetCredits");Text(vm.t("可用重置卡：","Available reset cards: ")+credits.str("availableCount","?"))
    var reset by remember{mutableStateOf<JsonObject?>(null)};val pending=data.obj("pendingReset")
    if(pending.isNotEmpty())Button(onClick={reset=pending},enabled=!vm.busy && data.bool("canReset")){Text(vm.t("重试原重置请求","Retry original reset"))}
    else if(data.bool("canReset")) {
        if(credits["credits"]==JsonNull && credits.num("availableCount")>0)Button(onClick={reset=payload("accountKey" to data.str("accountKey"),"creditId" to null)},enabled=!vm.busy){Text(vm.t("使用一张重置卡","Use a reset card"))}
        for(card in credits.rows("credits")) {
            Text(card.str("title"));Text(card.str("description"));Text(vm.t("到期时间：","Expires: ")+displayTime(vm,card,"expiresAt"))
            Button(onClick={reset=payload("accountKey" to data.str("accountKey"),"creditId" to card.str("id"))},enabled=!vm.busy && data.str("error").isEmpty() && card.str("status")=="available" && card.str("resetType")=="codexRateLimits" && (card["expiresAt"]==null || card["expiresAt"]==JsonNull || card.num("expiresAt")*1000>System.currentTimeMillis())){Text(vm.t("使用重置卡","Use reset card"))}
        }
    }
    reset?.let {row ->Confirm(vm.t("确认消耗一张重置卡？","Consume one reset card?"),data.str("email")+"\n"+vm.t("成功后消耗一张卡，并重置符合条件的额度。","Success consumes a card and resets eligible limits."),{reset=null}){reset=null;vm.resetAccount(row)}}
}

@Composable fun ApprovalCard(vm: BridgeViewModel,request: JsonObject) {
    val params=request.obj("params");val method=request.str("method");val enabled=!vm.busy && request.str("id") !in vm.answered
    var answers by remember(request.str("id")){mutableStateOf(emptyMap<String,String>())}
    val input=method.contains("requestUserInput");val mcp=method=="mcpServer/elicitation/request";val plan=method=="item/plan/requestImplementation";val computer=params.obj("computerUse")
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(vm.t("Codex 需要确认","Codex needs your response"),style=MaterialTheme.typography.titleMedium)
        for(field in listOf("message","reason","cwd","command"))if(params[field]!=null)Text(params.str(field,params[field].toString()))
        for(field in listOf("changes","permissions","grantRoot","networkApprovalContext"))params[field]?.let{JsonReadout(it)}
        if(!request.bool("supported"))Text(vm.t("请在桌面 App 处理此请求","Handle this request in the desktop app"))
        else if(plan) {
            MarkdownText(params.str("planContent"));val feedback=answers["feedback"] ?: ""
            OutlinedTextField(feedback,{answers=answers+("feedback" to it)},label={Text(vm.t("修改意见（可选）","Feedback (optional)"))},enabled=enabled)
            Button(onClick={vm.respond(request,payload("action" to "implement"))},enabled=enabled && feedback.isBlank()){Text(vm.t("按计划执行","Implement plan"))}
            Button(onClick={vm.respond(request,payload("action" to "revise","text" to feedback))},enabled=enabled && feedback.isNotBlank()){Text(vm.t("按意见继续规划","Revise plan"))}
        } else if(computer.isNotEmpty()) {
            Text(computer.str("app"));for(mode in computer.strings("persistModes"))Button(onClick={vm.respond(request,payload("action" to "accept","persist" to mode))},enabled=enabled){Text(vm.t(if(mode=="always")"始终允许" else "允许此对话",if(mode=="always")"Always allow" else "Allow this chat"))}
            if("session" !in computer.strings("persistModes"))Button(onClick={vm.respond(request,payload("action" to "accept"))},enabled=enabled){Text(vm.t("本次允许","Allow once"))}
            TextButton(onClick={vm.respond(request,payload("action" to "decline"))},enabled=enabled){Text(vm.t("拒绝","Decline"))}
        } else {
            val schema=params.obj("requestedSchema");val properties=schema.obj("properties");val required=schema.strings("required")
            if(input) for(question in params.rows("questions")) {
                val id=question.str("id");Text(question.str("question",question.str("header")));val value=answers[id] ?: ""
                if(question.rows("options").isNotEmpty())Choice(value,listOf("" to vm.t("请选择","Choose"))+question.rows("options").map{it.str("label") to (it.str("label")+" · "+it.str("description"))},enabled){answers=answers+(id to it)}
                OutlinedTextField(value,{answers=answers+(id to it)},label={Text(vm.t("回复／自行填写","Answer / custom response"))},enabled=enabled)
            }
            if(mcp)for((id,element) in properties) {
                val prop=element as? JsonObject ?: continue;val value=answers[id] ?: "";Text(prop.str("title",id)+if(id in required)" *" else "");Text(prop.str("description"))
                val options=prop.strings("enum");val labels=prop.strings("enumNames");if(options.isNotEmpty() || prop.str("type")=="boolean")Choice(value,listOf("" to vm.t("请选择","Choose"))+(if(options.isNotEmpty())options else listOf("true","false")).mapIndexed{index,item->item to (labels.getOrNull(index) ?: item)},enabled){answers=answers+(id to it)}
                else OutlinedTextField(value,{answers=answers+(id to it)},enabled=enabled)
            }
            val valid=if(input)params.rows("questions").all{!answers[it.str("id")].isNullOrBlank()} else if(mcp)required.all{!answers[it].isNullOrBlank()} && properties.all{(id,p)->val v=answers[id];v.isNullOrBlank() || when((p as? JsonObject)?.str("type")){"integer"->v.toLongOrNull()!=null;"number"->v.toDoubleOrNull()?.isFinite()==true;else->true}} else true
            val decisions=params.strings("availableDecisions")
            if(input || mcp || decisions.isEmpty() || "accept" in decisions)Button(onClick={
                val response=when {
                    input ->payload("answers" to JsonObject(answers.mapValues{JsonArray(listOf(JsonPrimitive(it.value)))}))
                    mcp ->payload("action" to "accept","content" to JsonObject(answers.filterValues{it.isNotBlank()}.mapValues{(id,v)->when(properties.obj(id).str("type")){"boolean"->JsonPrimitive(v.toBoolean());"integer"->JsonPrimitive(v.toLong());"number"->JsonPrimitive(v.toDouble());else->JsonPrimitive(v)}}))
                    else ->payload("decision" to "accept")
                };vm.respond(request,response)
            },enabled=enabled && valid){Text(vm.t(if(input)"发送回复" else "允许",if(input)"Send response" else "Accept"))}
            if(!input)for(decision in listOf("decline","cancel").filter{mcp || decisions.isEmpty() || it in decisions})TextButton(onClick={vm.respond(request,payload((if(mcp)"action" else "decision") to decision))},enabled=enabled){Text(vm.t(if(decision=="decline")"拒绝" else "取消",if(decision=="decline")"Decline" else "Cancel"))}
        }
        if(request.str("id") in vm.answered)Text(vm.t("已发送，等待桌面确认","Sent; waiting for desktop confirmation"))
    }}
}
