package io.github.codexmobilebridge.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

fun activityKey(row: JsonObject) = row.str("host","local")+"|"+row.str("id")
@Serializable data class SessionActivity(
    val status: String = "", val turnStatus: String = "", val token: String? = null,
    val pending: String? = null, val offline: Boolean = false
) {
    val indicator: String? get() = when {
        status=="active" -> if(offline) "unknown" else "running"
        pending!=null -> if(turnStatus=="completed") "completed" else if(turnStatus=="failed") "failed" else "ended"
        else -> null
    }
}
// Match web/activity.js: unavailable status preserves the last known activity;
// the first sighting of an old completed turn never becomes an unread notification.
fun updateActivities(previous: Map<String,SessionActivity>, rows: List<JsonObject>, visible: String? = null): Map<String,SessionActivity> {
    val result=previous.toMutableMap()
    for(row in rows) {
        val key=activityKey(row);val old=result[key]
        if(!row.bool("connected")) {if(old!=null)result[key]=old.copy(offline=true);continue}
        val terminal=row.str("turnStatus") in listOf("completed","failed","interrupted")
        val token=if(terminal && row.str("turnId").isNotBlank())row.str("turnId")+":"+row.str("turnStatus") else old?.token
        val pending=if(token!=null && old!=null && (token!=old.token || old.pending!=null))token else null
        result[key]=SessionActivity(row.str("status"),row.str("turnStatus",old?.turnStatus ?: ""),token,if(visible==key)null else pending,false)
    }
    return result
}
