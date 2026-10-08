package io.github.codexmobilebridge.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

class BridgeViewModel(app: Application) : AndroidViewModel(app) {
    private val updates = AppUpdates()
    var updateBusy by mutableStateOf(false); private set
    var availableUpdate by mutableStateOf<AppUpdate?>(null); private set
    var updateMessage by mutableStateOf(""); private set
    var updateFile by mutableStateOf<File?>(null); private set
    fun checkAppUpdate() {
        if(updateBusy)return
        updateBusy=true; updateMessage=""; availableUpdate=null; updateFile=null
        viewModelScope.launch {
            try {
                availableUpdate=updates.check()
                updateMessage=if((availableUpdate?.code ?: 0)>BuildConfig.VERSION_CODE) t("发现新版本","New version available") else if(availableUpdate==null) t("更新源尚未发布安卓版本","No Android release published yet") else t("已是最新版本","You are up to date")
            } catch(e:Exception) {updateMessage=t("检查失败，可重试：","Check failed; retry: ")+(e.message ?: "")}
            finally {updateBusy=false}
        }
    }
    fun downloadAppUpdate() {
        val value=availableUpdate ?: return
        if(updateBusy || value.code<=BuildConfig.VERSION_CODE)return
        updateBusy=true;updateMessage=t("正在下载并校验安装包…","Downloading and verifying…")
        viewModelScope.launch {
            try {updateFile=updates.download(getApplication(),value);updateMessage=t("下载完成，请点击安装更新","Download complete. Tap Install update")}
            catch(e:Exception) {updateFile=null;updateMessage=t("下载失败，可重试：","Download failed; retry: ")+(e.message ?: "")}
            finally {updateBusy=false}
        }
    }
    fun installAppUpdate() {
        val file=updateFile ?: return;val value=availableUpdate ?: return
        try {if(!updates.install(getApplication(),file,value))updateMessage=t("请允许此 App 安装应用，返回后再次点击安装更新","Allow app installs, return, then tap Install update again")}
        catch(e:Exception) {updateFile=null;updateMessage=t("安装失败，请重新下载：","Install failed; download again: ")+(e.message ?: "")}
    }
    val store = AppStore(app)
    var appNotifications by mutableStateOf(store.get("appNotifications")=="true"); private set
    fun enableAppNotifications(enabled:Boolean) {
        if(enabled && !LiveNotifications.permitted(getApplication())) {error=t("请允许 App 通知","Allow app notifications first");return}
        if(enabled && !appNotifications)selected?.let {connection->
            // Seed from already observed activity, so a task finishing just after
            // backgrounding is detected without replaying historical completions.
            val baseline=activities.mapValues {NoticeState(it.value.token)}
            store.put("notice:${connection.id}",json.encodeToString(baseline))
        }
        store.put("appNotifications",enabled.toString());appNotifications=enabled
        if(enabled) {startListPolling();startReminders()} else LiveNotifications.stop(getApplication())
    }
    fun testAppNotification()=LiveNotifications.post(getApplication(),selected,t("测试通知","Test notification"),"test")
    private fun startReminders() {
        if(appNotifications && foreground && authenticated && screen in listOf("sessions","chat"))selected?.let {
            try {LiveNotifications.start(getApplication(),it)} catch(e:Exception) {error=t("后台提醒启动失败：","Could not start reminders: ")+(e.message ?: "")}
        }
    }
    private fun visibleChat() {LiveNotifications.visible=if(foreground && screen=="chat")target?.key else null}
    private fun notifyRows(rows:List<JsonObject>) {
        selected?.let {connection->LiveNotifications.accept(getApplication(),store,connection,rows)}
    }
    var connections by mutableStateOf(store.connections()); private set
    var selected by mutableStateOf<Connection?>(null); private set
    var authenticated by mutableStateOf(false); private set
    var passwordless by mutableStateOf(false); private set
    var screen by mutableStateOf("connections"); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var notice by mutableStateOf(""); private set
    var sessions by mutableStateOf(emptyList<JsonObject>()); private set
    var activities by mutableStateOf(emptyMap<String,SessionActivity>()); private set
    var hostErrors by mutableStateOf(emptyList<JsonObject>()); private set
    var search by mutableStateOf("")
    var archived by mutableStateOf(false)
    var grouped by mutableStateOf(false)
    var target by mutableStateOf<ChatTarget?>(null); private set
    var timeline by mutableStateOf(TimelineState()); private set
    var draft by mutableStateOf(Draft()); private set
    var attachments by mutableStateOf(emptyList<Attachment>()); private set
    var historyBusy by mutableStateOf(false); private set
    var tool by mutableStateOf<String?>(null); private set
    var toolData by mutableStateOf(JsonObject(emptyMap())); private set
    var toolLoading by mutableStateOf(false); private set
    var details by mutableStateOf(emptyMap<String,String>()); private set
    var answered by mutableStateOf(emptySet<String>()); private set
    var theme by mutableStateOf(store.get("theme") ?: "system"); private set
    var language by mutableStateOf(store.get("language") ?: "system"); private set
    var showReasoning by mutableStateOf(store.get("reasoning") != "false"); private set
    var showProcess by mutableStateOf(store.get("process") != "false"); private set
    var fontScale by mutableFloatStateOf(store.get("fontScale")?.toFloatOrNull() ?: 1f); private set
    var compact by mutableStateOf(store.get("compact") == "true"); private set
    internal var gatewayFactory: (Connection) -> Gateway = { Gateway(it,store::get,store::put) }
    var api: Gateway? = null; private set
    private var generation by mutableIntStateOf(0)
    val chatGeneration get() = generation
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var listPolling: Job? = null
    private var foreground = false
    private var listGeneration = 0
    private var listOffset = 0
    var listHasMore by mutableStateOf(false); private set
    var notificationsAvailable by mutableStateOf(false); private set
    val english get() = language == "en" || language == "system" && java.util.Locale.getDefault().language != "zh"
    fun t(zh: String,en: String) = if(english) en else zh

    init {
        store.get("selected")?.let { id -> connections.find { it.id == id }?.let { selected = it } }
    }
    fun clearError() { error = ""; notice = "" }
    fun preference(key: String,value: String) {
        store.put(key,value)
        when(key) {
            "theme" -> theme = value; "language" -> language = value
            "reasoning" -> showReasoning = value.toBoolean(); "process" -> showProcess = value.toBoolean()
            "fontScale" -> fontScale = value.toFloat(); "compact" -> compact = value.toBoolean()
        }
    }
    fun saveConnection(name: String,address: String, id: String? = null) {
        try {
            val origin = normalizeOrigin(address)
            require(name.trim().isNotEmpty() && name.length <= 80) { t("请填写电脑名称","Enter a computer name") }
            require(connections.none { it.origin == origin && it.id != id }) { t("这个地址已经添加","This address already exists") }
            val old = connections.find { it.id == id }
            val row = Connection(if(old?.origin == origin) old.id else newId(),name.trim(),origin,old?.lastUsed ?: 0)
            if(old != null && old.id != row.id) store.removeConnection(old.id)
            connections = if(old == null) connections + row else connections.map { if(it.id == old.id) row else it }
            store.saveConnections(connections)
            if(selected?.id == old?.id && old != null) { disconnect(); selected = row; select(row) }
        } catch(e: Exception) { error = e.message ?: "Invalid address" }
    }
    fun removeConnection(row: Connection) {
        if(selected?.id == row.id) { disconnect(); selected = null; store.put("selected",null) }
        store.removeConnection(row.id); connections = store.connections()
    }
    fun moveConnection(id: String,delta: Int) {
        val rows = connections.toMutableList(); val from = rows.indexOfFirst { it.id == id }; val to = (from + delta).coerceIn(0,rows.lastIndex)
        if(from >= 0 && from != to) { val row = rows.removeAt(from); rows.add(to,row); connections = rows; store.saveConnections(rows) }
    }
    private fun cancelScope() {
        generation++; scope.cancel(); api?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        polling = null; listPolling = null; busy = false; toolLoading = false; historyBusy = false
    }
    private fun disconnect() {
        LiveNotifications.stop(getApplication());LiveNotifications.visible=null
        cancelScope(); api = null; authenticated = false; target = null; tool = null; timeline = TimelineState()
        sessions = emptyList(); activities=emptyMap(); hostErrors = emptyList(); details = emptyMap(); draft = Draft(); attachments = emptyList(); screen = "connections"; error = ""
    }
    fun select(row: Connection, pairingToken: String? = null) {
        disconnect()
        selected = row.copy(lastUsed=System.currentTimeMillis())
        connections = connections.map { if(it.id == row.id) selected!! else it }; store.saveConnections(connections); store.put("selected",row.id)
        activities=store.get("activity:${row.id}")?.let {runCatching {json.decodeFromString<Map<String,SessionActivity>>(it)}.getOrNull()}?.mapValues {it.value.copy(offline=true)} ?: emptyMap()
        api = gatewayFactory(selected!!); screen = "login"; busy = true
        val gateway = api!!; val gen = generation
        scope.launch {
            try {
                val auth = gateway.api("/api/auth")
                passwordless = auth.bool("passwordless"); notificationsAvailable = auth.bool("notifications")
                if(pairingToken != null) gateway.api("/api/pair",payload("token" to pairingToken))
                if(gen == generation && (auth.bool("authenticated") || pairingToken != null)) enter()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if(gen == generation) failure(e) }
            finally { if(gen == generation) busy = false }
        }
    }
    fun resume() {
        foreground = true;LiveNotifications.foreground=true;visibleChat();appNotifications=store.get("appNotifications")=="true"
        if(selected != null && api == null) select(selected!!)
        else if(authenticated) { if(target != null) startPolling(); startListPolling();startReminders() }
    }
    fun pause() {
        foreground = false;LiveNotifications.foreground=false;LiveNotifications.visible=null; polling?.cancel(); listPolling?.cancel(); api?.cancel()
        saveDraft(); busy = false
    }
    fun login(username: String,password: String,rememberPassword: Boolean = false) = action {
        val connection = selected!!
        api!!.api("/api/login",payload("username" to username,"password" to password))
        if(!passwordless) store.saveLogin(connection.id,if(rememberPassword) SavedLogin(username,password) else null)
        enter()
    }
    fun importLink(link: String) {
        try {
            val url = link.trim().toHttpUrl(); val token = url.fragment?.takeIf { it.startsWith("pair=") }?.substringAfter("pair=")
            val origin = normalizeOrigin(url.newBuilder().fragment(null).build().toString())
            val row = connections.find { it.origin == origin } ?: Connection(name=url.host,origin=origin).also {
                connections = connections + it; store.saveConnections(connections)
            }
            select(row,token)
        } catch(e: Exception) { error = e.message ?: "Invalid link" }
    }
    private suspend fun enter() {
        authenticated = true; screen = "sessions"; refreshListNow()
        startListPolling();startReminders()
    }
    fun showConnections() { LiveNotifications.stop(getApplication());LiveNotifications.visible=null;saveDraft(); cancelScope(); tool = null; screen = "connections"; target = null }
    fun showSessions() { LiveNotifications.visible=null;saveDraft(); cancelScope(); tool = null; target = null; timeline = TimelineState(); screen = "sessions"; refreshList() }
    fun logout() = action {
        LiveNotifications.stop(getApplication())
        api!!.api("/api/logout",payload()); api!!.clearSession(); selected?.let {store.put("activity:${it.id}",null)}; activities=emptyMap(); authenticated = false; target = null
        polling?.cancel(); listPolling?.cancel(); screen = "login"; sessions = emptyList(); tool = null
    }
    private fun failure(e: Exception) {
        error = e.message ?: t("请求失败","Request failed")
        if(e is GatewayError && e.status == 401) { LiveNotifications.stop(getApplication());authenticated = false; screen = "login"; tool = null; polling?.cancel(); listPolling?.cancel() }
    }
    private fun action(block: suspend () -> Unit) {
        if(busy || api == null) return
        busy = true; error = ""; notice = ""; val gen = generation
        scope.launch {
            try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { if(gen == generation) failure(e) }
            finally { if(gen == generation) busy = false }
        }
    }
    private suspend fun refreshListNow(more: Boolean = false) {
        val gen = ++listGeneration
        val offset = if(more) listOffset else 0
        val url = "https://placeholder.invalid/api/sessions".toHttpUrl().newBuilder()
            .addQueryParameter("q",search).addQueryParameter("offset",offset.toString()).addQueryParameter("archived",archived.toString()).build()
        val value = api!!.api(url.encodedPath+"?"+url.encodedQuery)
        if(gen != listGeneration) return
        val rows = value.rows("sessions")
        sessions = if(more) (sessions+rows).distinctBy { it.str("host")+"|"+it.str("id") } else rows
        store.put("noticeRows:${selected!!.id}",JsonArray(sessions).toString())
        hostErrors = value.rows("unavailableHosts"); listOffset = sessions.size; listHasMore = rows.size >= 100
    }
    fun refreshList(more: Boolean = false) = action { refreshListNow(more); startListPolling() }
    private fun saveActivities(value: Map<String,SessionActivity>) {
        if(value==activities)return
        activities=value;selected?.let {store.put("activity:${it.id}",json.encodeToString(value))}
    }
    private fun startListPolling() {
        listPolling?.cancel(); if(!foreground || !authenticated || screen !in listOf("sessions","chat")) return
        val gen = generation;val gateway=api ?: return
        listPolling = scope.launch {
            var warmup=0
            while(isActive && gen == generation) {
                var awaitingConnection=false
                val listGen=listGeneration
                try {
                    val hosts = sessions.groupBy { it.str("host","local") }.mapValues { (_,rows) -> JsonArray(rows.map { JsonPrimitive(it.str("id")) }) }
                    if(hosts.isNotEmpty()) {
                        // Query immediately, including after refresh and foreground resume.
                        val rows = gateway.api("/api/activity",payload("hosts" to JsonObject(hosts))).rows("sessions")
                        if(gen == generation && listGen==listGeneration) {
                            saveActivities(updateActivities(activities,rows,target?.let {it.host+"|"+it.id}))
                            notifyRows(rows.map {row->JsonObject(row+payload("title" to (sessions.find {activityKey(it)==activityKey(row)}?.str("title") ?: "Codex")))})
                            sessions = sessions.map { old ->
                                val status=rows.find {activityKey(it)==activityKey(old)}
                                if(status!=null)JsonObject(old+status+payload("recency" to maxOf(old.num("recency"),status.num("recency")))) else old
                            }.sortedByDescending {it.num("recency")}
                            awaitingConnection=rows.any {!it.bool("connected")}
                        }
                    }
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    if(gen!=generation)break
                    saveActivities(activities.mapValues {it.value.copy(offline=true)})
                    if(e is GatewayError && e.status == 401) { failure(e); break }
                }
                // Cold subscriptions attach asynchronously. Allow three short checks,
                // then use the same five-second cadence as the HTML client.
                delay(if(awaitingConnection && warmup++<3)500 else 5000)
            }
        }
    }
    fun saveDraft() { target?.let { store.saveDraft(it.key,draft) } }
    fun editDraft(value: Draft) { draft = value; saveDraft() }
    fun scrollPosition(position: Int,offset: Int,anchor: String = "",following: Boolean = draft.following, expected: ChatTarget? = target, expectedGeneration: Int? = null) { if(target != null && target == expected && (expectedGeneration==null || expectedGeneration==generation)) { draft = draft.copy(position=position,offset=offset,anchor=anchor,following=following); saveDraft() } }
    fun openChat(id: String,host: String = "local") {
        saveDraft(); cancelScope(); details = emptyMap(); answered = emptySet(); tool = null; error = ""
        val row = ChatTarget(selected!!.id,id,host); target = row; draft = store.draft(row.key); timeline = TimelineState()
        attachments = store.get("attachments:${row.key}")?.let { runCatching { json.decodeFromString<List<Attachment>>(it) }.getOrNull() }?.map {
            if(it.status == "uploading" || it.status == "pending") it.copy(status="failed",error=t("请重试上传","Retry upload")) else it
        } ?: emptyList()
        saveActivities(activities.mapValues {if(it.key==host+"|"+id)it.value.copy(pending=null) else it.value})
        store.put("noticeChat:${selected!!.id}",host+"|"+id)
        store.put("last:${selected!!.id}",payload("id" to id,"host" to host).toString()); screen = "chat"
        scope.launch { try { api!!.api(row.route("reconnect"),payload("activate" to true)) } catch(e: CancellationException) { throw e } catch(_: Exception) {} }
        visibleChat();startPolling(); startListPolling()
    }
    private fun startPolling() {
        polling?.cancel(); val row = target ?: return; if(!foreground || !authenticated) return
        val gateway = api!!; val gen = generation
        polling = scope.launch {
            while(isActive && gen == generation) {
                try {
                    val previous = timeline
                    val page = if(previous.sequence < 0) gateway.api(row.route("timeline",mapOf("limit" to "20")))
                        else gateway.api(row.route("changes",mapOf("after" to previous.sequence.toString(),"epoch" to previous.epoch,"start" to (previous.before ?: ""))))
                    if(gen != generation || target != row) break
                    if(previous.revision != timeline.revision && previous.sequence >= 0) continue
                    timeline = timeline.apply(page)
                    val latestTurn=timeline.messages.lastOrNull {it.str("turnId").isNotBlank() && it.str("turnStatus").isNotBlank()}
                    val activityRow=JsonObject(timeline.meta+payload("id" to row.id,"host" to row.host)+
                        (latestTurn?.let {payload("turnId" to it.str("turnId"),"turnStatus" to it.str("turnStatus"))} ?: payload()))
                    saveActivities(updateActivities(activities,listOf(activityRow),row.host+"|"+row.id))
                    notifyRows(listOf(activityRow))
                    if(previous.sequence < 0 && draft.anchor.isNotEmpty() && !draft.following) {
                        historyBusy=true
                        try { while(timeline.hasMore && timeline.messages.none { it.str("key") == draft.anchor }) {
                            val older = gateway.api(row.route("timeline",mapOf("before" to (timeline.before ?: ""),"limit" to "100")))
                            val before = timeline.before
                            timeline = timeline.apply(older,true)
                            if(before == timeline.before || older.rows("rows").isEmpty()) break
                        } } finally { if(gen==generation)historyBusy=false }
                    }
                    details = details.filterKeys { k -> timeline.messages.any { it.str("key")+":"+it.str("version") == k } }
                    delay(200)
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    if(gen == generation) { failure(e); if(!authenticated) break }
                    delay(3000)
                }
            }
        }
    }
    fun older() {
        if(historyBusy || !timeline.hasMore) return
        val row = target ?: return; val old = timeline; val gen = generation; historyBusy = true
        scope.launch {
            try {
                val page = api!!.api(row.route("timeline",mapOf("before" to (old.before ?: ""),"limit" to "100")))
                if(gen == generation && timeline.epoch == old.epoch) timeline = timeline.apply(page,true)
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if(gen == generation) failure(e) }
            finally { if(gen == generation) historyBusy = false }
        }
    }
    private fun attempt(key: String,fields: JsonObject,idField: String = "id"): JsonObject {
        val old = store.get("attempt:$key")?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
        val body = pendingAttempt(old,fields,idField); store.put("attempt:$key",body.toString()); return body
    }
    fun send() {
        val row = target ?: return
        if(draft.text.isBlank() && attachments.isEmpty()) return
        if(attachments.any { it.status != "ready" }) { error = t("请等待附件上传或重试失败项","Wait for uploads or retry failed files"); return }
        val snap = draft; val files = attachments; val gateway = api!!
        action {
            require(snap.text.length <= 100000)
            require(snap.workMode != "goal" || files.isEmpty()) { t("目标模式暂不支持附件","Goal mode does not support attachments") }
            val key = "send:${row.key}"
            val fields = payload("text" to snap.text,"mode" to snap.sendMode,"skills" to snap.skills,"workMode" to if(snap.sendMode == "steer") null else snap.workMode,
                "attachments" to files.map { it.id },"uiLocale" to if(english) "en" else "zh-CN")
            val result = gateway.api(row.route("send"),attempt(key,fields))
            check(result.str("status") != "unknown") { t("结果尚未确认，请查看记录；重试使用原请求","Result unknown. Inspect history; retry keeps the original request.") }
            store.put("attempt:$key",null)
            if(target == row) {
                val mode = if(snap.workMode == "goal") "default" else snap.workMode
                draft = draft.copy(text="",workMode=mode,skills=emptyList()); saveDraft()
                files.forEach { File(it.path).delete() }; attachments = emptyList(); persistAttachments(row)
                notice = t("已发送","Sent")
            }
        }
    }
    fun postChat(actionName: String,fields: JsonObject = payload(),durable: Boolean = false) {
        val row = target ?: return; val gateway = api!!
        action {
            val key = "$actionName:${row.key}"
            val result = gateway.api(row.route(actionName),if(durable) attempt(key,fields) else fields)
            check(result.str("status") != "unknown") { t("操作结果尚未确认，请刷新查看","Operation is not confirmed; refresh to inspect") }
            if(durable) store.put("attempt:$key",null)
            if(actionName == "rename") {
                timeline = timeline.copy(meta=JsonObject(timeline.meta+("title" to JsonPrimitive(result.str("title")))))
                refreshListNow()
            }
            tool = null; notice = t("操作已提交","Submitted")
        }
    }
    fun respond(request: JsonObject,response: JsonObject) {
        val row = target ?: return; val gateway = api!!
        if(request.str("id") in answered) return
        action {
            val result = gateway.api(row.route("respond"),payload("requestId" to request["id"],"response" to response))
            answered = answered+request.str("id"); tool = null
            if(result.str("status") == "unknown") error = t("回应结果尚未确认，请检查聊天","Response not confirmed; check the chat")
        }
    }
    suspend fun fullText(row: JsonObject): String {
        val key = row.str("key")+":"+row.str("version")
        details[key]?.let { return it }; if(!row.bool("truncated")) return row.str("text")
        val chat = target!!; val epoch = timeline.epoch; val gateway = api!!; var offset = 0; val text = StringBuilder()
        do {
            val page = gateway.api(chat.route("detail",mapOf("key" to "$epoch.${row.str("key")}","offset" to offset.toString(),"version" to row.str("version"))))
            check(page.str("version") == row.str("version") && page.num("offset").toInt() == offset && target == chat && timeline.epoch == epoch) { t("内容已变化，请重新选择","Content changed; select the message again") }
            text.append(page.str("text")); offset = (page["next"] as? JsonPrimitive)?.intOrNull ?: -1
        } while(offset >= 0)
        details = details+(key to text.toString()); return text.toString()
    }
    fun expand(row: JsonObject) = action { fullText(row) }
    fun messageAction(kind: String,row: JsonObject,text: String) {
        val chat = target ?: return; val gateway = api!!
        action {
            val key = "message:${chat.key}:${row.str("key")}"; val fields = payload("action" to kind,"key" to "${timeline.epoch}.${row.str("key")}","version" to row.str("version"),"text" to if(kind=="fork") null else text)
            val result = gateway.api(chat.route("message-action"),attempt(key,fields))
            check(result.str("status") != "unknown") { t("操作结果尚未确认，请查看聊天记录后重试原请求","Result unknown. Inspect history before retrying the original request.") }
            store.put("attempt:$key",null); store.put("editDraft:${chat.key}:${row.str("key")}",null); if(kind=="edit")store.put("editFallback:${chat.key}",null); tool = null
            if(kind != "edit") {
                if(result["draft"] != null && result["draft"] != JsonNull) {
                    val next = ChatTarget(chat.connection,result.str("id"),result.str("host"))
                    store.put("editFallback:${next.key}",payload("text" to result.str("draft"),"sourceTurnId" to result.obj("source").str("turnId")).toString())
                    notice = t("分支已创建；修改未发送，请编辑最后一条消息后重发","Branch created; edit was not sent. Edit its last message to resend.")
                }
                openChat(result.str("id"),result.str("host","local"))
            }
        }
    }
    fun openTool(name: String, data: JsonObject = payload()) {
        tool = name; toolData = data; error = ""
        if(name=="app-notifications")appNotifications=store.get("appNotifications")=="true"
        if(name == "message" && target != null) {
            val fallback = store.get("editFallback:${target!!.key}")?.let {runCatching{json.parseToJsonElement(it) as JsonObject}.getOrNull()}
            val saved = store.get("editDraft:${target!!.key}:${data.str("key")}")
            if(saved != null || fallback?.str("sourceTurnId") == data.str("turnId")) toolData = JsonObject(data+payload("editText" to (saved ?: fallback!!.str("text"))))
        }
        val row = target; val gen = generation; val gateway = api ?: return
        val path = when(name) {
            "new" -> "/api/projects"; "model", "skills" -> row?.route("catalog",mapOf("refresh" to "true"))
            "notifications" -> row?.route("notifications"); "pushplus" -> "/api/notifications/pushplus"
            "defaults" -> "/api/notifications/defaults"; "accounts" -> "/api/accounts"; "usage" -> "/api/account"
            else -> null
        } ?: return
        toolLoading = true
        scope.launch {
            try {
                val value = gateway.api(path)
                if(gen == generation && tool == name) { toolData = value; toolLoading = false }
                if(name == "accounts") {
                    while(isActive && gen == generation && tool == name && foreground) {
                        delay(2000); val next = gateway.api(path); if(tool == name) toolData = next
                    }
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if(gen == generation && tool == name) failure(e) }
            finally { if(gen == generation && tool == name) toolLoading = false }
        }
    }
    fun closeTool() { if(!busy) { tool = null; error = "" } }
    fun perform(path: String,fields: JsonObject,keepOpen: Boolean = false,durableKey: String? = null,idField: String = "id") {
        val gateway = api ?: return; val connection = selected!!.id
        action {
            val key = durableKey?.let { "$connection:$it" }
            val result = gateway.api(path,if(key != null) attempt(key,fields,idField) else fields)
            check(result.str("status") != "unknown") { t("操作结果尚未确认，请刷新后重试原请求","Operation unconfirmed; refresh before retrying the original request") }
            if(key != null) store.put("attempt:$key",null)
            if(path == "/api/sessions") { openChat(result.str("id"),result.str("host","local")); return@action }
            if(keepOpen) { if(tool != "accounts")toolData = result } else tool = null
            notice = t("设置已保存","Saved")
        }
    }
    fun resetAccount(fields: JsonObject) {
        val gateway = api ?: return
        val key = "reset:${selected!!.id}:${fields.str("accountKey")}:${fields.str("creditId")}"
        action {
            val pending = fields.str("requestId")
            val body = if(pending.isNotEmpty()) JsonObject(fields+payload("confirmed" to true)) else attempt(key,JsonObject(fields+payload("confirmed" to true)),"requestId")
            val result = gateway.api("/api/account/reset",body)
            check(result.str("status") != "unknown") { t("重置结果待确认，请重试原请求","Reset unconfirmed; retry the original request") }
            store.put("attempt:$key",null); toolData = result.obj("account")
        }
    }
    fun goalAction(kind: String, fields: JsonObject = payload()) {
        val goal = timeline.meta.obj("goal")
        postChat("goal/$kind",JsonObject(fields+payload("expected" to goalIdentity(goal),"uiLocale" to if(english) "en" else "zh-CN")),true)
    }
    private fun persistAttachments(row: ChatTarget) = store.put("attachments:${row.key}",json.encodeToString(attachments))
    fun addAttachments(uris: List<Uri>) {
        val row = target ?: return; if(busy) return; val gen = generation
        action {
            val values = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val result = mutableListOf<Attachment>()
                try {
                    for(uri in uris) {
                        var name = "attachment"; var declared: Long = -1
                        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                            if(cursor.moveToFirst()) { name=cursor.getString(0); if(!cursor.isNull(1)) declared=cursor.getLong(1) }
                        }
                        require(declared <= 20L*1024*1024)
                        val id = newId(); val file = File(getApplication<Application>().cacheDir,"upload-$id")
                        try {
                            resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                                val buffer=ByteArray(8192); var count: Int; var total=0L
                                while(input.read(buffer).also { count=it } != -1) { total+=count; require(total <= 20L*1024*1024); output.write(buffer,0,count) }
                            } }
                            result += Attachment(id,name,file.length(),file.path)
                        } catch(e: Exception) { file.delete(); throw e }
                    }
                    validateAttachments(attachments,result); result
                } catch(e: Exception) { result.forEach { File(it.path).delete() }; throw e }
            }
            if(gen != generation) { values.forEach { File(it.path).delete() }; return@action }
            attachments = attachments+values; persistAttachments(row)
            uploadPending(row)
        }
    }
    private suspend fun uploadPending(row: ChatTarget) {
        val gateway = api!!; val gen = generation
        for(file in attachments.filter { it.status == "pending" }) {
            attachments = attachments.map { if(it.id==file.id) it.copy(status="uploading") else it }; persistAttachments(row)
            try {
                val source=File(file.path); check(source.isFile) { t("缓存附件已清理，请重新选择","Cached file was removed; select it again") }
                val result = gateway.api(row.route("uploads",mapOf("id" to file.id,"name" to file.name)),raw=source.asRequestBody("application/octet-stream".toMediaType()))
                if(gen == generation && target == row) attachments = attachments.map { if(it.id == file.id) it.copy(status="ready",error="",image=result.bool("image")) else it }
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                if(gen == generation && target == row) attachments = attachments.map { if(it.id == file.id) it.copy(status="failed",error=e.message ?: "Upload failed") else it }
            }
            if(gen == generation && target == row) persistAttachments(row)
        }
    }
    fun retryAttachment(id: String) {
        val row = target ?: return
        action { attachments = attachments.map { if(it.id == id) it.copy(status="pending") else it }; persistAttachments(row); uploadPending(row) }
    }
    fun removeAttachment(id: String) {
        if(busy) return
        val row = target ?: return
        attachments.find { it.id == id }?.let { File(it.path).delete() }; attachments = attachments.filter { it.id != id }; persistAttachments(row)
    }
    fun download(file: JsonObject,uri: Uri) {
        val row = target ?: return; val gateway = api!!
        action { withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { output -> gateway.file(row.route("files/${file.str("id")}"),output) }
        }; notice = t("文件已保存","File saved") }
    }
    override fun onCleared() { updates.cancel(); saveDraft(); scope.cancel(); api?.cancel(); super.onCleared() }
}
