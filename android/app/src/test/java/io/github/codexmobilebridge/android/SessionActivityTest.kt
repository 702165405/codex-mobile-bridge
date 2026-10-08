package io.github.codexmobilebridge.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class SessionActivityTest {
    private fun row(status:String="idle",connected:Boolean=true,turn:String="one",end:String="",host:String="local")=payload("id" to "chat","host" to host,"status" to status,"connected" to connected,"turnId" to turn,"turnStatus" to end)
    @Test fun idleAndColdUnknownAreNotUserFacingStatuses() {
        assertNull(updateActivities(emptyMap(),listOf(row())).getValue("local|chat").indicator)
        assertTrue(updateActivities(emptyMap(),listOf(row("unknown",false))).isEmpty())
        assertNull(updateActivities(emptyMap(),listOf(row(end="completed"))).getValue("local|chat").indicator)
    }
    @Test fun unavailableActivityPreservesRunningAndRecovers() {
        val running=updateActivities(emptyMap(),listOf(row("active")))
        assertEquals("running",running.getValue("local|chat").indicator)
        val offline=updateActivities(running,listOf(row("unknown",false)))
        assertEquals("unknown",offline.getValue("local|chat").indicator)
        assertNull(updateActivities(offline,listOf(row())).getValue("local|chat").indicator)
    }
    @Test fun completedTurnsBecomeUnreadUntilOpenedButVisibleChatStaysRead() {
        val running=updateActivities(emptyMap(),listOf(row("active")))
        val completed=updateActivities(running,listOf(row(end="completed")))
        assertEquals("completed",completed.getValue("local|chat").indicator)
        assertEquals("completed",updateActivities(completed,listOf(row(end="completed"))).getValue("local|chat").indicator)
        assertNull(updateActivities(completed,listOf(row(end="completed")),"local|chat").getValue("local|chat").indicator)
        assertNull(updateActivities(running,listOf(row(end="completed")),"local|chat").getValue("local|chat").indicator)
    }
    @Test fun activityHostsAndPersistedStatesAreIndependent() {
        val states=updateActivities(emptyMap(),listOf(row("active"),row(host="ssh")))
        val next=updateActivities(states,listOf(row(end="failed")))
        assertEquals("failed",next.getValue("local|chat").indicator)
        assertNull(next.getValue("ssh|chat").indicator)
        assertEquals(next,json.decodeFromString<Map<String,SessionActivity>>(json.encodeToString(next)))
    }
}
