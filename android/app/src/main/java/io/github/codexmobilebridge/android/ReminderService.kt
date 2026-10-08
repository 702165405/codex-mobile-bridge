package io.github.codexmobilebridge.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString

class ReminderService:Service() {
    companion object {
        internal var gatewayFactory:(Connection,AppStore)->Gateway={connection,store->Gateway(connection,store::get,store::put){it.readTimeout(20,java.util.concurrent.TimeUnit.SECONDS).callTimeout(30,java.util.concurrent.TimeUnit.SECONDS)}}
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var gateway:Gateway?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val store=AppStore(this)
        if(intent?.action==LiveNotifications.STOP) {store.put("appNotifications","false");stopSelf();return START_NOT_STICKY}
        val id=intent?.getStringExtra("connection")
        val connection=store.connections().find {it.id==id}
        if(connection==null || store.get("appNotifications")!="true" || !LiveNotifications.permitted(this)) {stopSelf();return START_NOT_STICKY}
        val notification=LiveNotifications.ongoing(this,connection)
        if(Build.VERSION.SDK_INT>=34)startForeground(LiveNotifications.ONGOING,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(LiveNotifications.ONGOING,notification)
        job?.cancel();gateway?.cancel()
        val api=gatewayFactory(connection,store);gateway=api
        job=scope.launch {
            var listed=emptyList<JsonObject>();var refreshAt=0L;var authorized=false
            while(isActive && store.get("appNotifications")=="true" && store.get("selected")==connection.id) {
                // Foreground UI already synchronizes and emits events. No duplicate polling.
                if(LiveNotifications.foreground) {delay(1000);continue}
                try {
                    if(!authorized) {
                        val auth=api.api("/api/auth")
                        if(!auth.bool("authenticated"))throw GatewayError(401,"Login expired")
                        authorized=true
                    }
                    if(System.currentTimeMillis()>=refreshAt) {
                        listed=api.api("/api/sessions?offset=0&archived=false").rows("sessions")
                        refreshAt=System.currentTimeMillis()+30000
                    }
                    val saved=store.get("noticeRows:${connection.id}")?.let {runCatching {json.parseToJsonElement(it).jsonArray.map {row->row.jsonObject}}.getOrNull()}.orEmpty()
                    val rows=(saved+listed).distinctBy(::activityKey).take(500)
                    val hosts=rows.groupBy {it.str("host","local")}.mapValues {(_,group)->JsonArray(group.map {JsonPrimitive(it.str("id"))})}
                    if(hosts.isNotEmpty()) {
                        val activity=api.api("/api/activity",payload("hosts" to JsonObject(hosts))).rows("sessions")
                        val decorated=activity.map {row->JsonObject(row+payload("title" to (rows.find {activityKey(it)==activityKey(row)}?.str("title") ?: "Codex")))}
                        if(store.get("selected")!=connection.id)break
                        LiveNotifications.accept(this@ReminderService,store,connection,decorated)
                        val recent=store.get("noticeChat:${connection.id}")
                        val pending=store.get("notice:${connection.id}")?.let {runCatching {json.decodeFromString<Map<String,NoticeState>>(it)}.getOrNull()}?.filterValues {it.requests.isNotEmpty()}?.keys.orEmpty()
                        for(row in decorated.filter {it.bool("connected") && (it.str("status") !in listOf("idle","unknown") || activityKey(it)==recent || activityKey(it) in pending)}) {
                            val chat=ChatTarget(connection.id,row.str("id"),row.str("host","local"))
                            val meta=api.api(chat.route("timeline",mapOf("limit" to "1"))).obj("meta")
                            if(store.get("selected")!=connection.id)break
                            LiveNotifications.accept(this@ReminderService,store,connection,listOf(JsonObject(row+payload("requests" to (meta["requests"] ?: JsonArray(emptyList()))))))
                        }
                    }
                } catch(e:CancellationException) {throw e} catch(e:Exception) {
                    if(e is GatewayError && e.status==401) {LiveNotifications.post(this@ReminderService,connection,"Codex","login","login");break}
                    authorized=false // Keep session credentials fresh on retry.
                }
                delay(5000)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {scope.cancel();gateway?.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
