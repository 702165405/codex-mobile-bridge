package io.github.codexmobilebridge.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

class ReminderService:Service() {
    companion object {
        internal var gatewayFactory:(Connection,AppStore)->Gateway={connection,store->Gateway(connection,store::get,store::put){it.readTimeout(20,java.util.concurrent.TimeUnit.SECONDS).callTimeout(30,java.util.concurrent.TimeUnit.SECONDS)}}
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val store=AppStore(this)
        if(intent?.action==LiveNotifications.STOP) {store.put("appNotifications","false");stopSelf();return START_NOT_STICKY}
        if(store.connections().isEmpty() || store.get("appNotifications")!="true" || !LiveNotifications.permitted(this)) {stopSelf();return START_NOT_STICKY}
        val notification=LiveNotifications.ongoing(this)
        if(Build.VERSION.SDK_INT>=34)startForeground(LiveNotifications.ONGOING,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(LiveNotifications.ONGOING,notification)
        if(job?.isActive!=true) job=scope.launch {
            ReminderMonitor(store::connections,store::get,store::put,{gatewayFactory(it,store)},
                {connection,rows->LiveNotifications.accept(this@ReminderService,store,connection,rows)},
                {connection,title,kind,key->LiveNotifications.post(this@ReminderService,connection,title,kind,key)}).run()
        }
        return START_STICKY
    }
    override fun onDestroy() {scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
