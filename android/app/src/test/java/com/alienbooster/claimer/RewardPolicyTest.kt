package com.alienbooster.claimer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardPolicyTest {
    @Test
    fun stageIncreaseCredits() {
        val before = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 1_000)
        val after = UiState(stageCompleted = intArrayOf(6, 0, 0), durationSec = 1_000)
        assertTrue(RewardPolicy.credited(before, after))
    }

    @Test
    fun durationDeltaOf500Credits() {
        val before = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 1_000)
        val after = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 1_500)
        assertTrue(RewardPolicy.credited(before, after))
    }

    @Test
    fun durationDeltaOf499DoesNotCredit() {
        val before = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 1_000)
        val after = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 1_499)
        assertFalse(RewardPolicy.credited(before, after))
    }

    @Test
    fun loadFailureTextAloneDoesNotCredit() {
        val before = UiState(
            stageCompleted = intArrayOf(5, 0, 0),
            durationSec = 1_770_307,
            rawText = "5 / 9"
        )
        val after = UiState(
            stageCompleted = intArrayOf(5, 0, 0),
            durationSec = 1_770_307,
            rawText = "广告加载失败",
            ctaText = "点击重试"
        )
        assertFalse(RewardPolicy.credited(before, after))
        assertFalse(RewardPolicy.textIndicatesCredit("广告加载失败"))
        assertFalse(RewardPolicy.textIndicatesCredit(after.rawText))
    }

    @Test
    fun loadFailureTextDoesNotVetoARealStageIncrease() {
        val before = UiState(stageCompleted = intArrayOf(5, 0, 0), durationSec = 100)
        val after = UiState(
            stageCompleted = intArrayOf(6, 0, 0),
            durationSec = 100,
            rawText = "广告加载失败"
        )
        assertTrue(RewardPolicy.credited(before, after))
    }

    @Test
    fun doneDetection() {
        val max = intArrayOf(9, 3, 9)
        assertTrue(RewardPolicy.isDone("今日广告已看完，请明日再来", null, intArrayOf(5, 0, 0), max))
        assertTrue(RewardPolicy.isDone("明日再来", null, intArrayOf(0, 0, 0), max))
        assertTrue(RewardPolicy.isDone("", "已看完", intArrayOf(1, 0, 0), max))
        assertTrue(RewardPolicy.isDone("已领完", null, intArrayOf(1, 0, 0), max))
        assertTrue(RewardPolicy.isDone("", null, intArrayOf(9, 3, 9), max))
        assertFalse(RewardPolicy.isDone("广告加载失败", "点击重试", intArrayOf(5, 0, 0), max))
        assertFalse(RewardPolicy.isDone("5 / 9", "看广告 领时长", intArrayOf(5, 0, 0), max))
        assertFalse(RewardPolicy.isDone("已完成", null, intArrayOf(9, 3, 0), max))
    }

    @Test
    fun loginDetection() {
        assertTrue(RewardPolicy.isLogin("登录/注册", hasStageList = true))
        assertTrue(RewardPolicy.isLogin("请登录", hasStageList = false))
        assertFalse(RewardPolicy.isLogin("请登录", hasStageList = true))
        assertFalse(RewardPolicy.isLogin("用户昵称", hasStageList = true))
    }

    @Test
    fun nullDurationDoesNotCreditWithoutAStageIncrease() {
        val before = UiState(stageCompleted = intArrayOf(1, 0, 0), durationSec = null)
        val after = UiState(stageCompleted = intArrayOf(1, 0, 0), durationSec = null)
        assertFalse(RewardPolicy.credited(before, after))
        assertFalse(RewardPolicy.credited(1, 1, 100, null))
        assertFalse(RewardPolicy.credited(1, 1, null, 900))
        assertTrue(RewardPolicy.credited(4, 5, null, null))
    }

    @Test
    fun dailyFlagOnlyForANormalFinishedRun() {
        assertFalse(RewardPolicy.shouldWriteDailyFlag(1, done = true, credits = 1, quotaExhausted = false))
        assertFalse(RewardPolicy.shouldWriteDailyFlag(20, done = true, credits = 20, quotaExhausted = true))
        assertFalse(RewardPolicy.shouldWriteDailyFlag(21, done = false, credits = 3, quotaExhausted = false))
        assertTrue(RewardPolicy.shouldWriteDailyFlag(21, done = true, credits = 0, quotaExhausted = false))
        assertTrue(RewardPolicy.shouldWriteDailyFlag(21, done = false, credits = 21, quotaExhausted = false))
        assertTrue(RewardPolicy.shouldWriteDailyFlag(21, done = false, credits = 4, quotaExhausted = true))
        assertTrue(RewardPolicy.quotaReached(21))
        assertFalse(RewardPolicy.quotaReached(20))
        assertFalse(RewardPolicy.textIndicatesCredit("广告加载失败"))
    }

    @Test
    fun safetyWindowIsNotLowered() {
        org.junit.Assert.assertEquals(18_000L, RewardEngine.KILL_AT_MS)
        org.junit.Assert.assertEquals(15_000L, RewardEngine.KILL_AFTER_AD_OPEN_MS)
        org.junit.Assert.assertEquals(2_000L, RewardEngine.SETTLE_FAST_MS)
        org.junit.Assert.assertEquals(30_000L, RewardEngine.FALLBACK_MS)
        org.junit.Assert.assertEquals(45_000L, RewardEngine.HARD_TIMEOUT_MS)
        org.junit.Assert.assertEquals(300L, RewardEngine.FOCUS_POLL_MS)
    }
}
