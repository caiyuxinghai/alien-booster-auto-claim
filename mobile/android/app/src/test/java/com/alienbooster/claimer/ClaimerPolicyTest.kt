package com.alienbooster.claimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ClaimerPolicyTest {
    @Test
    fun outsideAction_limitsAdJumpReturnsAndRespectsGrace() {
        assertEquals(
            AdPolicy.OutsideAction.WAIT,
            AdPolicy.outsideAction(
                samePackage = false,
                sinceStartMs = 30_000L,
                outsideElapsedMs = 8_000L,
                adJump = true,
                outsideReturns = 0
            )
        )
        assertEquals(
            AdPolicy.OutsideAction.RETURN,
            AdPolicy.outsideAction(
                samePackage = false,
                sinceStartMs = 30_000L,
                outsideElapsedMs = 8_001L,
                adJump = true,
                outsideReturns = 0
            )
        )
        assertEquals(
            AdPolicy.OutsideAction.RETURN,
            AdPolicy.outsideAction(
                samePackage = false,
                sinceStartMs = 30_000L,
                outsideElapsedMs = 8_001L,
                adJump = true,
                outsideReturns = 1
            )
        )
        assertEquals(
            AdPolicy.OutsideAction.PAUSE,
            AdPolicy.outsideAction(
                samePackage = false,
                sinceStartMs = 30_000L,
                outsideElapsedMs = 8_001L,
                adJump = true,
                outsideReturns = 2
            )
        )
        assertEquals(
            AdPolicy.OutsideAction.PAUSE,
            AdPolicy.outsideAction(
                samePackage = false,
                sinceStartMs = 30_000L,
                outsideElapsedMs = 2_501L,
                adJump = false,
                outsideReturns = 0
            )
        )
        assertEquals(
            AdPolicy.OutsideAction.WAIT,
            AdPolicy.outsideAction(
                samePackage = true,
                sinceStartMs = 5_000L,
                outsideElapsedMs = 10_000L,
                adJump = false,
                outsideReturns = 0
            )
        )
    }

    @Test
    fun stageCountsAndCredited_matchExpectedProgress() {
        assertEquals(listOf(9, 1, 0), AdPolicy.stageCounts(listOf("已完成", "1/3", "待解锁")))
        assertTrue(AdPolicy.credited(listOf(9, 0, 0), listOf(9, 1, 0), 1000, 1000))
        assertTrue(AdPolicy.credited(listOf(9, 1, 0), listOf(9, 1, 0), 1000, 1602))
        assertFalse(AdPolicy.credited(listOf(9, 1, 0), listOf(9, 1, 0), 1000, 1005))
    }

    @Test
    fun dueTime_skipsPastAndSentDays() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2026, 11, 25, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val fromEarlier = LocalDateTime.of(2026, 11, 25, 7, 30).atZone(zone).toInstant().toEpochMilli()
        val fromLater = LocalDateTime.of(2026, 11, 25, 9, 0).atZone(zone).toInstant().toEpochMilli()

        val dueTodayMissed = RunSchedule.dueTime(now, zone, 8 * 60, fromEarlier, emptySet())
        assertEquals(
            LocalDateTime.of(2026, 11, 25, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            dueTodayMissed
        )

        val dueAfterSwitch = RunSchedule.dueTime(now, zone, 8 * 60, fromLater, emptySet())
        assertEquals(
            LocalDateTime.of(2026, 11, 26, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            dueAfterSwitch
        )

        val dueAfterSent = RunSchedule.dueTime(now, zone, 8 * 60, fromEarlier, setOf("2026-11-25"))
        assertEquals(
            LocalDateTime.of(2026, 11, 26, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            dueAfterSent
        )
    }
}
