@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.codexmobilebridge.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@Composable fun ContextUsageChip(vm: BridgeViewModel, meta: JsonObject) {
    val usage=meta.obj("contextUsage")
    if(usage.isEmpty())return
    val used=usage.num("usedTokens",usage.num("totalTokens",usage.num("used")))
    val limit=usage.num("contextWindow",usage.num("maxTokens",usage.num("limit")))
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(vm.t("上下文","Context")+" · "+if(limit>0)"$used / $limit" else usage.toString(),style=MaterialTheme.typography.labelSmall)
        if(limit>0)LinearProgressIndicator(progress={(used.toFloat()/limit).coerceIn(0f,1f)},modifier=Modifier.weight(1f).padding(top=6.dp))
    }
}
@Composable fun ClaudeNewChat(vm: BridgeViewModel,data: JsonObject) {
    val projects=data.rows("projects")
    var project by remember(data){mutableStateOf(projects.firstOrNull()?.str("key") ?: "")}
    var title by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    Choice(project,projects.map{it.str("key") to it.str("name",it.str("cwd"))}){project=it}
    OutlinedTextField(title,{if(it.length<=120)title=it},label={Text(vm.t("聊天名称","Chat name"))})
    OutlinedTextField(message,{if(it.length<=100000)message=it},label={Text(vm.t("首条消息","First message"))},maxLines=6)
    Text(data.str("notice"),style=MaterialTheme.typography.bodySmall)
    Button(onClick={vm.perform("/api/desktop-sessions/claude/create",payload("projectKey" to project,"title" to title,"firstMessage" to message),durableKey="claude-create:$project:$title:$message")},enabled=!vm.busy && project.isNotBlank() && title.isNotBlank() && message.isNotBlank()){Text(vm.t("创建聊天","Create chat"))}
}
@Composable fun ClaudeModelSettings(vm: BridgeViewModel,data: JsonObject) {
    val models=data.rows("models");val capabilities=data.obj("capabilities")
    var model by remember(data){mutableStateOf(data.str("currentModel",models.firstOrNull()?.str("id") ?: ""))}
    var effort by remember(model){mutableStateOf(data.str("currentEffort"))}
    val row=models.find{it.str("id")==model};val efforts=row?.strings("efforts").orEmpty()
    Choice(model,models.map{it.str("id") to it.str("name",it.str("id"))}){model=it}
    if(efforts.isNotEmpty() && capabilities.bool("effort"))Choice(effort,listOf("" to vm.t("默认","Default"))+efforts.map{it to it}){effort=it}
    Text(data.str("notice"))
    Button(onClick={vm.postChat("settings",JsonObject(payload("model" to model)+if(effort.isNotBlank() && effort in efforts && capabilities.bool("effort"))payload("effort" to effort) else payload()),true)},enabled=!vm.busy && row!=null && capabilities.bool("models",true) && vm.timeline.meta.bool("connected")){Text(vm.t("应用设置","Apply settings"))}
}
@Composable fun ClaudeApprovalCard(vm: BridgeViewModel,request: JsonObject) {
    var answers by remember(request.str("id")){mutableStateOf(emptyMap<String,String>())}
    val questions=request.obj("input").rows("questions")
    val ready=!vm.busy && vm.timeline.meta.bool("connected") && request.str("id") !in vm.answered
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(vm.t("Claude Code 需要确认","Claude Code needs your response"),style=MaterialTheme.typography.titleMedium)
            Text(request.str("tool"));JsonReadout(request["input"] ?: JsonNull)
            for((index,question) in questions.withIndex()) {
                val id=question.str("id",question.str("question",index.toString()))
                Text(question.str("question"));val options=question.rows("options")
                if(options.isNotEmpty())Choice(answers[id] ?: "",listOf("" to vm.t("请选择","Choose"))+options.map{it.str("label") to it.str("label")}){answers=answers+(id to it)}
                OutlinedTextField(answers[id] ?: "",{answers=answers+(id to it)},label={Text(vm.t("回复","Answer"))})
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={val fields=payload("decision" to "accept");vm.respond(request,if(request.bool("needsInput"))JsonObject(fields+payload("answers" to JsonObject(answers.mapValues{payload("answers" to JsonArray(listOf(JsonPrimitive(it.value))))}))) else fields)},enabled=ready && (!request.bool("needsInput") || questions.isNotEmpty() && questions.withIndex().all{(index,q)->!answers[q.str("id",q.str("question",index.toString()))].isNullOrBlank()})){Text(vm.t("本次允许","Allow once"))}
                TextButton(onClick={vm.respond(request,payload("decision" to "decline"))},enabled=ready){Text(vm.t("拒绝","Decline"))}
            }
        }
    }
}
@Composable fun ClaudeAccountsTool(vm: BridgeViewModel,data: JsonObject) {
    val rows=data.rows("accounts");var confirm by remember{mutableStateOf<JsonObject?>(null)}
    Text(vm.t("Claude Code 账号","Claude Code accounts"),style=MaterialTheme.typography.titleMedium)
    if(rows.isEmpty())JsonReadout(data)
    for(row in rows)Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(row.str("name",row.str("label",row.str("id"))));Text(row.str("email"))
            TextButton(onClick={vm.perform("/api/desktop-sessions/claude/accounts",payload("action" to "details","id" to row.str("id")),true)},enabled=!vm.busy){Text(vm.t("详情","Details"))}
            OutlinedButton(onClick={confirm=row},enabled=!vm.busy && data.bool("canSwitch") && row.str("id") !in data.strings("activeIds") && row.str("id")!=data.str("activeId")){Text(vm.t("切换账号","Switch account"))}
        }
    }
    data["account"]?.let{JsonReadout(it)}
    confirm?.let{row->Confirm(vm.t("切换 Claude 账号？","Switch Claude account?"),vm.t("将按桌面端规则切换接入；请先处理正在运行的任务。","The desktop account will change. Handle active tasks first."),{confirm=null}){confirm=null;vm.perform("/api/desktop-sessions/claude/accounts",payload("action" to "switch","id" to row.str("id")),true)}}
}

@Composable fun CodexPermissionsTool(vm:BridgeViewModel) {
    var full by remember{mutableStateOf(false)}
    Text(vm.t("当前权限：","Current permissions: ")+vm.timeline.meta.str("permissionMode"))
    for((preset,label,help) in listOf(Triple("ask",vm.t("请求批准","Ask for approval"),vm.t("需要额外权限时询问你。","Ask when extra permissions are needed.")),Triple("auto-review",vm.t("帮我批准","Automatic review"),vm.t("由 Codex 审核额外权限操作。","Codex reviews operations requiring extra permissions.")),Triple("full-access",vm.t("完全访问权限","Full access"),vm.t("允许访问工作区外的文件和网络。","Allow access to files and networks beyond the workspace.")))) {
        OutlinedButton(onClick={if(preset=="full-access")full=true else vm.postChat("permissions",payload("preset" to preset,"confirmed" to false))},enabled=!vm.busy && vm.timeline.meta.bool("connected")){Text(label)}
        Text(help,style=MaterialTheme.typography.bodySmall)
    }
    if(full)Confirm(vm.t("允许完全访问？","Allow full access?"),vm.t("此聊天将能访问电脑文件和网络，无需逐次确认。","This chat can access computer files and the network without per-operation approval."),{full=false}){full=false;vm.postChat("permissions",payload("preset" to "full-access","confirmed" to true))}
}
