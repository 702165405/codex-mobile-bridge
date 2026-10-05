package io.github.codexmobilebridge.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var bridge: BridgeViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bridge = ViewModelProvider(this)[BridgeViewModel::class.java]
        setContent { BridgeApp(bridge) }
        if(savedInstanceState == null) importSharedLink(intent)
    }
    private fun importSharedLink(value: Intent) {
        if(value.action==LiveNotifications.OPEN) {
            val id=value.getStringExtra("noticeConnection")
            bridge.connections.find {it.id==id}?.let {bridge.select(it)}
        }
        if(value.action == Intent.ACTION_SEND) value.getStringExtra(Intent.EXTRA_TEXT)?.let(bridge::importLink)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importSharedLink(intent)
    }
}
