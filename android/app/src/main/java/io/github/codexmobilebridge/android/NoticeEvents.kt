package io.github.codexmobilebridge.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class NoticeState(val terminal: String? = null, val requests: Set<String> = emptySet())
data class ChatNotice(val key: String, val title: String, val kind: String)
data class NoticeBatch(val states: Map<String,NoticeState>, val events: List<ChatNotice>)
fun noticeEvents(previous: Map<String,NoticeState>, rows: List<JsonObject>, visible: String? = null): NoticeBatch {
    val states=previous.toMutableMap();val events=mutableListOf<ChatNotice>()
    for(row in rows) {
        if(!row.bool("connected"))continue
        val key=activityKey(row);val old=states[key]
        val end=row.str("turnStatus")
        val token=if(row.str("status")!="active" && end in listOf("completed","failed","interrupted") && row.str("turnId").isNotBlank())row.str("turnId")+":"+end else old?.terminal
        val requests=if("requests" in row)row.rows("requests").map {it.str("method")+"|"+it.str("id")}.toSet() else old?.requests.orEmpty()
        if(visible!=key) {
            if(token!=null && old!=null && token!=old.terminal)events+=ChatNotice(key,row.str("title","Codex"),end)
            if((requests-old?.requests.orEmpty()).isNotEmpty())events+=ChatNotice(key,row.str("title","Codex"),"approval")
        }
        states[key]=NoticeState(token,requests)
    }
    return NoticeBatch(states,events)
}
