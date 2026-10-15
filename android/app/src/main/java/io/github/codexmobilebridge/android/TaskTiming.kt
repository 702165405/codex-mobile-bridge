package io.github.codexmobilebridge.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

fun taskTimingText(timing: JsonObject, english: Boolean = false, now: Long = System.currentTimeMillis(), connected: Boolean = true): String {
    if(timing.str("status")=="inProgress") {
        val start=(timing["startedAtMs"] as? JsonPrimitive)?.longOrNull?.takeIf{it>=0} ?: return ""
        if(!connected || now<start)return ""
        val seconds=(now-start)/1000
        val clock=(if(seconds>=3600)"${seconds/3600}:"+(seconds/60%60).toString().padStart(2,'0') else (seconds/60).toString())+":"+(seconds%60).toString().padStart(2,'0')
        return (if(english)"Running" else "执行中")+" · "+clock
    }
    if(timing.str("status") !in listOf("completed","failed","interrupted"))return ""
    val elapsed=(timing["durationMs"] as? JsonPrimitive)?.longOrNull?.takeIf{it>=0} ?: return ""
    val seconds=elapsed/1000
    val parts=mutableListOf<String>()
    if(seconds>=3600)parts.add("${seconds/3600}"+if(english)"h" else "小时")
    if(seconds>=60)parts.add("${seconds/60%60}"+if(english)"m" else "分钟")
    parts.add("${seconds%60}"+if(english)"s" else "秒")
    return (if(english)"Time taken" else "用时")+" "+parts.joinToString(" ")
}

@Composable fun TaskTiming(vm: BridgeViewModel, timing: JsonObject, connected: Boolean = true) {
    var now by remember(timing){mutableStateOf(System.currentTimeMillis())}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(timing,connected,lifecycle) {
        if(timing.str("status")=="inProgress" && connected && "startedAtMs" in timing)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true){now=System.currentTimeMillis();delay(1000)}
        }
    }
    val text=taskTimingText(timing,vm.t("用时","Time taken")=="Time taken",now,connected)
    if(text.isNotEmpty())Text(text,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
