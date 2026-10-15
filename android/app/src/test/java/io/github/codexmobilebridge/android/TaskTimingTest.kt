package io.github.codexmobilebridge.android

import org.junit.Assert.*
import org.junit.Test

class TaskTimingTest {
    @Test fun nativeCompletedDurationUsesMinutesAndSeconds() {
        assertEquals("用时 1分钟 58秒",taskTimingText(payload("status" to "completed","durationMs" to 118000)))
        assertEquals("Time taken 1m 58s",taskTimingText(payload("status" to "completed","durationMs" to 118000),true))
        assertEquals("用时 1小时 1分钟 1秒",taskTimingText(payload("status" to "completed","durationMs" to 3661000)))
        assertEquals("用时 0秒",taskTimingText(payload("status" to "failed","durationMs" to 0)))
    }
    @Test fun runningClockIsRetainedAndMissingTimesHaveNoPlaceholders() {
        assertEquals("执行中 · 1:39",taskTimingText(payload("status" to "inProgress","startedAtMs" to 1000),now=100000))
        assertEquals("Running · 1:01:01",taskTimingText(payload("status" to "inProgress","startedAtMs" to 1000),true,3662000))
        assertEquals("",taskTimingText(payload("status" to "inProgress","startedAtMs" to 1000),now=100000,connected=false))
        assertEquals("",taskTimingText(payload()))
        assertEquals("",taskTimingText(payload("status" to "completed","startedAtMs" to 1000)))
        assertEquals("",taskTimingText(payload("status" to "inProgress","durationMs" to 118000)))
        assertEquals("",taskTimingText(payload("status" to "completed","durationMs" to -1)))
    }
}
