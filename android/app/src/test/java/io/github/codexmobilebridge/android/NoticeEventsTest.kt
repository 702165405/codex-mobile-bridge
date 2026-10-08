package io.github.codexmobilebridge.android

import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class NoticeEventsTest {
    private fun row(status:String="active",end:String="",id:String="one",connected:Boolean=true)=payload("host" to "local","id" to "chat","title" to "Work","connected" to connected,"status" to status,"turnId" to id,"turnStatus" to end)
    @Test fun historicalCompletionDoesNotNotifyButObservedTaskDoesOnce() {
        assertTrue(noticeEvents(emptyMap(),listOf(row("idle","completed"))).events.isEmpty())
        val baseline=noticeEvents(emptyMap(),listOf(row()))
        val done=noticeEvents(baseline.states,listOf(row("idle","completed")))
        assertEquals("completed",done.events.single().kind)
        assertTrue(noticeEvents(done.states,listOf(row("idle","completed"))).events.isEmpty())
        val restored=json.decodeFromString<Map<String,NoticeState>>(json.encodeToString(done.states))
        assertTrue(noticeEvents(restored,listOf(row("idle","completed"))).events.isEmpty())
    }
    @Test fun visibleChatAndOfflineStatusDoNotInterruptUser() {
        val running=noticeEvents(emptyMap(),listOf(row())).states
        assertTrue(noticeEvents(running,listOf(row("idle","completed")),"local|chat").events.isEmpty())
        assertEquals(running,noticeEvents(running,listOf(row("unknown","completed",connected=false))).states)
        assertTrue(noticeEvents(running,listOf(row("active","completed"))).events.isEmpty())
    }
    @Test fun approvalsAreDeduplicatedAcrossActivityAndTimelineBatches() {
        val ask=JsonObject(row()+payload("requests" to JsonArray(listOf(payload("id" to 7,"method" to "approval")))))
        val first=noticeEvents(emptyMap(),listOf(ask))
        assertEquals("approval",first.events.single().kind)
        val activity=noticeEvents(first.states,listOf(row()))
        assertTrue(noticeEvents(activity.states,listOf(ask)).events.isEmpty())
        val replied=noticeEvents(activity.states,listOf(JsonObject(row()+payload("requests" to JsonArray(emptyList())))))
        assertEquals("approval",noticeEvents(replied.states,listOf(ask)).events.single().kind)
        assertTrue(noticeEvents(emptyMap(),listOf(ask),"local|chat").events.isEmpty())
    }
    @Test fun failuresAndHostsAreIndependent() {
        val baseline=noticeEvents(emptyMap(),listOf(row(),JsonObject(row()+payload("host" to "ssh")))).states
        assertEquals("failed",noticeEvents(baseline,listOf(row("idle","failed"))).events.single().kind)
        assertEquals("interrupted",noticeEvents(baseline,listOf(row("idle","interrupted"))).events.single().kind)
        assertNull(baseline.getValue("ssh|chat").terminal)
    }
}
