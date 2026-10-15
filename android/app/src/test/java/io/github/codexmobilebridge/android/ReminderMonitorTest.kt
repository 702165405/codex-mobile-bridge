package io.github.codexmobilebridge.android

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReminderMonitorTest {
    private class Computer(val id: String): AutoCloseable {
        val server = MockWebServer()
        val calls = AtomicInteger()
        val authCalls = AtomicInteger()
        @Volatile var offline = false
        @Volatile var expired = false
        @Volatile var slow = false
        @Volatile var completed = false
        @Volatile var renew = false
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val connection: Connection
        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.path!!.startsWith("/api/auth")) authCalls.incrementAndGet()
                    if(slow) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    if(offline) return MockResponse().setResponseCode(503).setBody("{}")
                    if(expired && request.getHeader("Cookie")?.contains("session=renewed") != true)
                        return MockResponse().setResponseCode(401).setBody("{}")
                    val row = payload("id" to "11111111-1111-4111-8111-111111111111", "host" to "local", "title" to id, "connected" to true,
                        "status" to if(completed) "idle" else "active", "turnId" to "turn-one",
                        "turnStatus" to if(completed) "completed" else "inProgress")
                    val body = when(request.path!!.substringBefore('?')) {
                        "/api/auth" -> payload("authenticated" to true, "csrf" to id)
                        "/api/mobile/events" -> return MockResponse().setResponseCode(404).setBody("{}")
                        "/api/sessions" -> payload("sessions" to JsonArray(listOf(row)))
                        "/api/activity" -> { calls.incrementAndGet(); payload("sessions" to JsonArray(listOf(row))) }
                        else -> payload("meta" to payload("requests" to JsonArray(emptyList())))
                    }
                    return MockResponse().setBody(body.toString()).apply {
                        if(renew) addHeader("Set-Cookie", "session=renewed; Secure; Path=/; Max-Age=${3600 + authCalls.get()}")
                    }
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            connection = Connection(id, id, server.url("/").newBuilder().host("127.0.0.1").build().toString())
        }
        fun gateway(state: MutableMap<String, String>) = Gateway(connection, state::get, {key, value ->
            if(value == null) state.remove(key) else state[key] = value
        }) { it.sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).callTimeout(2, TimeUnit.SECONDS) }
        override fun close() = server.shutdown()
    }

    private class Fixture(vararg val computers: Computer) {
        val saved = CopyOnWriteArrayList(computers.map { it.connection })
        val state = ConcurrentHashMap<String, String>()
        val notices = CopyOnWriteArrayList<Pair<String, String>>()
        val observed = ConcurrentHashMap<String, Int>()
        private fun write(key: String, value: String?) { if(value == null) state.remove(key) else state[key] = value }
        val monitor = ReminderMonitor({saved.toList()}, state::get, ::write,
            {connection -> computers.first { it.id == connection.id }.gateway(state)},
            {connection, rows ->
                val key = "notice:${connection.id}"
                val previous = state[key]?.let { json.decodeFromString<Map<String, NoticeState>>(it) }.orEmpty()
                val batch = noticeEvents(previous, rows)
                write(key, json.encodeToString(batch.states))
                batch.events.forEach { notices += connection.id to it.kind }
                observed.merge(connection.id, 1, Int::plus)
            },
            {connection, _, kind, _ -> notices += connection.id to kind})
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) {
        while(!condition()) delay(25)
    }

    @Test fun slowHostAndNavigationDoNotBlockOtherHostsAndCancellationStopsAll() = runBlocking {
        Computer("slow").use { a -> Computer("online").use { b ->
            a.slow = true
            val fixture = Fixture(a, b)
            val job = launch { fixture.monitor.run() }
            try {
                await { fixture.observed.containsKey("online") }
                fixture.state["selected"] = "slow"
                fixture.state["screen"] = "connections"
                b.completed = true
                await { fixture.notices.contains("online" to "completed") }
                assertFalse(fixture.observed.containsKey("slow"))
                await { b.calls.get() >= 3 }
                assertEquals(1, fixture.notices.count { it == "online" to "completed" })
            } finally { job.cancelAndJoin() }
            val count = b.calls.get()
            delay(1100)
            assertEquals(count, b.calls.get())
        }}
    }

    @Test fun offlineHostReconnectsAndRemovingItKeepsOtherHostRunning() = runBlocking {
        Computer("offline").use { a -> Computer("online").use { b ->
            a.offline = true
            val fixture = Fixture(a, b)
            val job = launch { fixture.monitor.run() }
            try {
                await { a.authCalls.get() > 0 && fixture.observed.containsKey("online") }
                a.offline = false
                await { fixture.observed.containsKey("offline") }
                a.completed = true
                await { fixture.notices.contains("offline" to "completed") }
                fixture.saved.removeIf { it.id == "offline" }
                delay(1200)
                val count = a.calls.get()
                val bCount = b.calls.get()
                await { b.calls.get() > bCount }
                assertEquals(count, a.calls.get())
            } finally { job.cancelAndJoin() }
        }}
    }

    @Test fun expiredLoginPausesOnlyItsHostAndResumesAfterNewLogin() = runBlocking {
        Computer("expired").use { a -> Computer("online").use { b ->
            a.expired = true
            val fixture = Fixture(a, b)
            fixture.state["cookies:expired"] = "session=old; Path=/; Secure; Max-Age=3600"
            val job = launch { fixture.monitor.run() }
            try {
                await { fixture.notices.contains("expired" to "login") && fixture.observed.containsKey("online") }
                val count = a.authCalls.get()
                val bCount = b.calls.get()
                await { b.calls.get() > bCount }
                assertEquals(count, a.authCalls.get())
                fixture.state["cookies:expired"] = "session=renewed; Path=/; Secure; Max-Age=3600"
                await { fixture.observed.containsKey("expired") }
                assertEquals(1, fixture.notices.count { it == "expired" to "login" })
                assertTrue(a.authCalls.get() > count)
            } finally { job.cancelAndJoin() }
        }}
    }

    @Test fun newlySavedHostStartsAndCookieRenewalDoesNotRestartAuthentication() = runBlocking {
        Computer("first").use { a -> Computer("added").use { b ->
            b.renew = true
            val fixture = Fixture(a, b)
            fixture.saved.removeIf { it.id == "added" }
            fixture.state["cookies:added"] = "session=renewed; Path=/; Secure; Max-Age=3600"
            val job = launch { fixture.monitor.run() }
            try {
                await { fixture.observed.containsKey("first") }
                assertEquals(0, b.authCalls.get())
                fixture.saved += b.connection
                await { b.calls.get() >= 2 }
                assertEquals(1, b.authCalls.get())
                assertTrue(fixture.observed.containsKey("added"))
                assertTrue(fixture.notices.isEmpty())
            } finally { job.cancelAndJoin() }
        }}
    }
}
