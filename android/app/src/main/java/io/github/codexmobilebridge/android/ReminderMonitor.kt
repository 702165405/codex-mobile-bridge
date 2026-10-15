package io.github.codexmobilebridge.android

import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One independently retried client per saved computer; navigation never owns these clients. */
internal class ReminderMonitor(
    private val connections: () -> List<Connection>,
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val gatewayFactory: (Connection) -> Gateway,
    private val accept: (Connection, List<JsonObject>) -> Unit,
    private val post: (Connection, String, String, String) -> Unit,
) {
    private fun credentials(connection: Connection): List<List<String>> {
        val origin = connection.origin.toHttpUrlOrNull() ?: return emptyList()
        // Renewal changes expiry dates, not the login identity.
        return read("cookies:${connection.id}")?.lines().orEmpty().mapNotNull { Cookie.parse(origin, it) }
            .map { listOf(it.name, it.value, it.domain, it.path, it.secure.toString(), it.hostOnly.toString()) }
            .sortedBy { it.take(4).joinToString("|") }
    }

    suspend fun run() = coroutineScope {
        val workers = mutableMapOf<Connection, Job>()
        try {
            while(isActive) {
                val saved = connections().associateBy { it.id }
                for(connection in workers.keys.toList()) {
                    // Renaming/reordering a computer must not reset its listener.
                    if(saved[connection.id]?.origin != connection.origin) workers.remove(connection)?.cancel()
                }
                for(connection in saved.values) {
                    if(workers.keys.none { it.id == connection.id }) workers[connection] = launch { follow(connection) }
                }
                delay(1000)
            }
        } finally {
            workers.values.forEach { it.cancel() }
        }
    }

    private suspend fun follow(connection: Connection) {
        var api: Gateway? = null
        var listed = emptyList<JsonObject>()
        var refreshAt = 0L
        var authorized = false
        var credentials = credentials(connection)
        var loginExpired = false
        var retry = 5000L
        var mobileCursor = read("mobile-cursor:${connection.id}")?.toLongOrNull()
        var mobileStream = read("mobile-stream:${connection.id}")
        fun current() = connections().any { it.id == connection.id && it.origin == connection.origin } &&
            credentials(connection) == credentials
        try {
            while(currentCoroutineContext().isActive) {
                val cookies = credentials(connection)
                if(cookies != credentials) {
                    credentials = cookies; authorized = false; loginExpired = false; refreshAt = 0L
                    api?.reloadCookies()
                }
                if(loginExpired) { delay(1000); continue }
                try {
                    val client = api ?: gatewayFactory(connection).also { api = it }
                    if(!authorized) {
                        val auth = client.api("/api/auth")
                        // Authentication can refresh the persisted cookie jar.
                        if(!current()) continue
                        if(!auth.bool("authenticated")) throw GatewayError(401, "Login expired")
                        authorized = true
                    }
                    try {
                        val events = client.api("/api/mobile/events" + (mobileCursor?.let { "?after=$it" } ?: ""))
                        if(!current()) continue
                        val stream = events.str("streamId")
                        val cursor = events["cursor"]?.jsonPrimitive?.longOrNull
                        if(events.bool("enabled") && mobileCursor != null && stream == mobileStream) {
                            for(event in events.rows("events").filter { it.str("provider") == "claude" || it.str("host") == "desktop:claude" }) {
                                val sequence = event["sequence"]?.jsonPrimitive?.longOrNull ?: continue
                                if(sequence > mobileCursor!!) post(connection, event.str("title", "Claude Code"),
                                    if(event.str("kind") in listOf("approval", "request")) "approval" else "completed",
                                    "claude:" + event.str("id", sequence.toString()))
                            }
                        }
                        if(cursor != null) {
                            mobileCursor = cursor; mobileStream = stream
                            write("mobile-cursor:${connection.id}", cursor.toString())
                            write("mobile-stream:${connection.id}", stream)
                        }
                    } catch(e: GatewayError) { if(e.status != 404) throw e }
                    if(System.currentTimeMillis() >= refreshAt) {
                        listed = client.api("/api/sessions?offset=0&archived=false").rows("sessions")
                        refreshAt = System.currentTimeMillis() + 30000
                    }
                    val saved = read("noticeRows:${connection.id}")?.let {
                        runCatching { json.parseToJsonElement(it).jsonArray.map { row -> row.jsonObject } }.getOrNull()
                    }.orEmpty()
                    val rows = (saved + listed).distinctBy(::activityKey).take(500)
                    val hosts = rows.groupBy { it.str("host", "local") }.mapValues { (_, group) -> JsonArray(group.map { JsonPrimitive(it.str("id")) }) }
                    if(hosts.isNotEmpty()) {
                        val activity = client.api("/api/activity", payload("hosts" to JsonObject(hosts))).rows("sessions")
                        if(!current()) continue
                        val decorated = activity.map { row -> JsonObject(row + payload("title" to (rows.find { activityKey(it) == activityKey(row) }?.str("title") ?: "Codex"))) }
                        accept(connection, decorated)
                        val recent = read("noticeChat:${connection.id}")
                        val pending = read("notice:${connection.id}")?.let {
                            runCatching { json.decodeFromString<Map<String, NoticeState>>(it) }.getOrNull()
                        }?.filterValues { it.requests.isNotEmpty() }?.keys.orEmpty()
                        for(row in decorated.filter { it.bool("connected") && (it.str("status") !in listOf("idle", "unknown") || activityKey(it) == recent || activityKey(it) in pending) }) {
                            val chat = ChatTarget(connection.id, row.str("id"), row.str("host", "local"))
                            val meta = client.api(chat.route("timeline", mapOf("limit" to "1"))).obj("meta")
                            if(!current()) break
                            accept(connection, listOf(JsonObject(row + payload("requests" to (meta["requests"] ?: JsonArray(emptyList()))))))
                        }
                    }
                    retry = 5000L
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    authorized = false
                    if(e is GatewayError && e.status == 401) {
                        if(current()) {
                            loginExpired = true
                            // Saved but never signed-in computers do not need an expiry alert.
                            if(credentials.isNotEmpty()) post(connection, "Codex", "login", "login")
                        }
                    } else retry = (retry * 2).coerceAtMost(30000L)
                }
                delay(retry)
            }
        } finally {
            api?.cancel()
        }
    }
}
