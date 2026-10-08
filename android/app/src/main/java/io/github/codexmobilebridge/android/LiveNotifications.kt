package io.github.codexmobilebridge.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

object LiveNotifications {
    const val OPEN="io.github.codexmobilebridge.android.OPEN_NOTICE"
    const val STOP="stop-reminders"
    const val ONGOING=7001
    @Volatile var foreground=false
    @Volatile var visible: String?=null
    private fun english(store:AppStore)=store.get("language").let {it=="en" || (it==null || it=="system") && java.util.Locale.getDefault().language!="zh"}
    fun permitted(context:Context)= (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun channels(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("chat-reminders","聊天提醒 / Chat reminders",NotificationManager.IMPORTANCE_HIGH).apply {enableVibration(true)})
        manager.createNotificationChannel(NotificationChannel("background-monitor","后台提醒连接 / Background monitor",NotificationManager.IMPORTANCE_LOW))
    }
    private fun click(context:Context,connection:String?):PendingIntent {
        val intent=Intent(context,MainActivity::class.java).setAction(OPEN).putExtra("noticeConnection",connection).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context,connection?.hashCode() ?: 0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun ongoing(context:Context,connection:Connection):Notification {
        channels(context);val en=english(AppStore(context))
        val stop=PendingIntent.getService(context,0,Intent(context,ReminderService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,"background-monitor").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(en)"Chat reminders enabled" else "聊天后台提醒已开启")
            .setContentText(connection.name).setContentIntent(click(context,connection.id)).setOngoing(true).setSilent(true)
            .addAction(0,if(en)"Stop reminders" else "停止提醒",stop).build()
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun post(context:Context,connection:Connection?,title:String,kind:String,key:String="test") {
        if(!permitted(context))return
        channels(context);val en=english(AppStore(context))
        val text=when(kind) {"completed" -> if(en)"Task completed" else "任务已完成";"failed" -> if(en)"Task failed" else "任务运行失败";"interrupted" -> if(en)"Task stopped" else "任务已停止";"approval" -> if(en)"Waiting for your response" else "等待你审批或回答问题";"login" -> if(en)"Login expired. Open the app to sign in." else "登录已过期，请打开 App 重新登录";else -> if(en)"App notifications are working" else "App 通知测试成功"}
        val notification=NotificationCompat.Builder(context,"chat-reminders").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(listOfNotNull(connection?.name,title).joinToString(" · ")).setContentText(text)
            .setContentIntent(click(context,connection?.id)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        NotificationManagerCompat.from(context).notify("${connection?.id}:$key",7002,notification)
    }
    @Synchronized fun accept(context:Context,store:AppStore,connection:Connection,rows:List<JsonObject>) {
        if(store.get("appNotifications")!="true")return
        val previous=store.get("notice:${connection.id}")?.let {runCatching {json.decodeFromString<Map<String,NoticeState>>(it)}.getOrNull()} ?: emptyMap()
        val visibleKey=if(foreground)visible?.takeIf {it.startsWith(connection.id+"|") }?.removePrefix(connection.id+"|") else null
        val batch=noticeEvents(previous,rows,visibleKey)
        if(batch.states!=previous)store.put("notice:${connection.id}",json.encodeToString(batch.states))
        for(event in batch.events)post(context,connection,event.title,event.kind,event.key)
    }
    fun start(context:Context,connection:Connection) {
        if(!permitted(context))return
        ContextCompat.startForegroundService(context,Intent(context,ReminderService::class.java).putExtra("connection",connection.id))
    }
    fun stop(context:Context)=context.stopService(Intent(context,ReminderService::class.java))
}
