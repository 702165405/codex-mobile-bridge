package io.github.codexmobilebridge.android

import org.junit.Assert.*
import org.junit.Test

class TaskTimingTest {
    @Test fun runningUsesSourceStartAndCompletedDurationIsFrozen() {
        assertEquals("Running · 1:39",taskTimingText(payload("status" to "inProgress","startedAtMs" to 1000),true,100000,true))
        assertEquals("耗时 · 1:01:01",taskTimingText(payload("status" to "completed","durationMs" to 3661000),true,9999999))
        assertEquals("耗时 · 0:00",taskTimingText(payload("status" to "failed","durationMs" to 0),true,100000))
    }
    @Test fun missingFutureAndDisconnectedTimesDoNotInventDuration() {
        assertEquals("",taskTimingText(payload(),true,100000))
        assertEquals("执行时长暂未提供",taskTimingText(payload("status" to "completed","startedAtMs" to 1000),true,100000))
        assertEquals("执行时长暂未提供",taskTimingText(payload("status" to "inProgress","startedAtMs" to 200000),true,100000))
        assertEquals("执行时长待同步",taskTimingText(payload("status" to "inProgress","startedAtMs" to 1000),false,100000))
    }
}
