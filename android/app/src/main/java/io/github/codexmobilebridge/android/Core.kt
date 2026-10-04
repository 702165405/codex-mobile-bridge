package io.github.codexmobilebridge.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun JsonObject.str(key: String, default: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.bool(key: String, default: Boolean = false) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.num(key: String, default: Long = 0) = (get(key) as? JsonPrimitive)?.longOrNull ?: default
fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.rows(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
fun payload(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) put(key, when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Collection<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
        else -> JsonPrimitive(value.toString())
    })
}
fun newId() = UUID.randomUUID().toString()

@Serializable data class Connection(val id: String = newId(), val name: String, val origin: String, val lastUsed: Long = 0)
fun normalizeOrigin(input: String): String {
    val url = input.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "请输入 HTTPS 根地址，不含账号、路径或查询参数 / Enter an HTTPS origin" }
    return url.toString().removeSuffix("/")
}
data class ChatTarget(val connection: String, val id: String, val host: String = "local") {
    val key get() = "$connection|$host|$id"
    fun route(action: String = "", params: Map<String, String> = emptyMap()): String {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        val url = "https://placeholder.invalid/api/sessions/$id${if(action.isEmpty()) "" else "/$action"}".toHttpUrl().newBuilder()
        url.addQueryParameter("host", host)
        params.forEach { (k,v) -> url.addQueryParameter(k,v) }
        val built = url.build()
        return built.encodedPath + "?" + built.encodedQuery
    }
}
@Serializable data class Draft(val text: String = "", val sendMode: String = "send", val workMode: String = "default", val skills: List<String> = emptyList(), val position: Int = 0, val offset: Int = 0, val anchor: String = "", val following: Boolean = true)
@Serializable data class Attachment(val id: String = newId(), val name: String, val size: Long, val path: String, val status: String = "pending", val error: String = "", val image: Boolean = false)
fun validateAttachments(existing: List<Attachment>, added: List<Attachment>) {
    require(existing.size + added.size <= 10) { "最多 10 个附件 / Maximum 10 attachments" }
    require(added.all { it.size in 1..20L*1024*1024 }) { "每个附件需为 1 字节至 20 MB / Each attachment must be 1 byte–20 MB" }
    require((existing + added).sumOf { it.size } <= 100L*1024*1024) { "附件总计不能超过 100 MB / Maximum total 100 MB" }
}

/** One timeline per connection/host/chat; old history never moves the live sequence backwards. */
data class TimelineState(val epoch: String = "", val sequence: Long = -1, val before: String? = null,
                         val hasMore: Boolean = false, val meta: JsonObject = JsonObject(emptyMap()),
                         val messages: List<JsonObject> = emptyList(), val revision: Long = 0) {
    fun apply(page: JsonObject, history: Boolean = false): TimelineState {
        val newEpoch = page.str("epoch")
        val reset = page.bool("reset") || epoch != newEpoch
        val seq = page.num("sequence", -1)
        if (!reset && !history && seq < sequence) return this
        val rows = (if(reset) emptyList() else messages).associateBy { it.str("key") }.toMutableMap()
        for(row in page.rows("rows")) {
            if(history && !reset && seq < sequence && rows.containsKey(row.str("key"))) continue
            rows[row.str("key")] = row
        }
        return copy(epoch = newEpoch, sequence = if(reset) seq else maxOf(sequence,seq),
                    before = if(reset || history || page.containsKey("before")) page.str("before").ifEmpty { null } else before,
                    hasMore = if(reset || history || page.containsKey("hasMore")) page.bool("hasMore") else hasMore,
                    meta = if(reset || seq >= sequence) page.obj("meta") else meta,
                    messages = rows.values.sortedBy { it.num("order") }, revision = revision + if(reset || history) 1 else 0)
    }
}
/** A durable identity survives errors/cancellation; retries never fabricate a new operation. */
fun pendingAttempt(previous: JsonObject?, fields: JsonObject, idField: String = "id"): JsonObject {
    if(previous != null && previous.filterKeys { it != idField } == fields) return previous
    return JsonObject(fields + (idField to JsonPrimitive(newId())))
}
fun goalIdentity(goal: JsonObject) = JsonObject(goal.filterKeys { it in setOf("objective", "status", "createdAt", "createdAtMs", "goalId") })

/** Translate supported math delimiters without changing code; external images stay explicit links. */
fun readableMarkdown(source: String): String {
    fun prose(text: String): String {
        var value=text.replace("\\[","$$").replace("\\]","$$").replace("\\(","$$").replace("\\)","$$")
        value=Regex("(?<!\\$)\\$([^\\$\\n]+)\\$(?!\\$)").replace(value){"$$"+it.groupValues[1]+"$$"}
        return Regex("!\\[([^]\\n]*)]\\((https?://[^\\s)]+)\\)").replace(value){"[${it.groupValues[1]}](${it.groupValues[2]})"}
    }
    val output=StringBuilder();var start=0
    for(code in Regex("```[\\s\\S]*?```|`[^`\\n]*`").findAll(source)) {
        output.append(prose(source.substring(start,code.range.first))).append(code.value)
        start=code.range.last+1
    }
    return output.append(prose(source.substring(start))).toString()
}
