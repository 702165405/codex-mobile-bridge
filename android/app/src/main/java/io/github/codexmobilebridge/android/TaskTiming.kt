package io.github.codexmobilebridge.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.util.Locale

fun taskTimingText(timing: JsonObject, connected: Boolean, now: Long, english: Boolean = false): String {
    val status=timing.str("status")
    if(status !in listOf("inProgress","completed","failed","interrupted"))return ""
    val running=status=="inProgress"
    if(running && !connected)return if(english)"Task duration awaiting sync" else "执行时长待同步"
    val start=(timing["startedAtMs"] as? JsonPrimitive)?.longOrNull?.takeIf{it>=0}
    val elapsed=if(running && start!=null)now-start else if(!running)(timing["durationMs"] as? JsonPrimitive)?.longOrNull else null
    if(elapsed==null || elapsed<0)return if(english)"Task duration unavailable" else "执行时长暂未提供"
    val seconds=elapsed/1000
    val duration=if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
    return (if(running){if(english)"Running" else "执行中"}else{if(english)"Duration" else "耗时"})+" · "+duration
}

@Composable fun TaskTiming(vm: BridgeViewModel, timing: JsonObject, connected: Boolean) {
    var now by remember(timing){mutableStateOf(System.currentTimeMillis())}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(timing,connected,lifecycle) {
        if(timing.str("status")=="inProgress" && connected && "startedAtMs" in timing) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(true){now=System.currentTimeMillis();delay(1000)}
            }
        }
    }
    val text=taskTimingText(timing,connected,now,vm.t("执行中","Running")=="Running")
    if(text.isNotEmpty())Text(text,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
