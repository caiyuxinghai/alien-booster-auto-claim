package com.alienbooster.claimer

/**
 * Pure credit / done / login decisions. A lone "广告加载失败" is a shared retry
 * state and is not evidence of credit or of failure.
 */
object RewardPolicy {
    const val DURATION_DELTA_FLOOR = 500
    const val FULL_QUOTA = 21

    val DONE_PHRASES = listOf("今日广告已看完", "明日再来", "已看完", "已领完")

    fun credited(before: UiState, after: UiState): Boolean {
        return credited(
            before.stageCompleted.sum(),
            after.stageCompleted.sum(),
            before.durationSec,
            after.durationSec
        )
    }

    fun credited(
        beforeStages: Int,
        afterStages: Int,
        beforeDuration: Int?,
        afterDuration: Int?
    ): Boolean {
        if (afterStages > beforeStages) return true
        if (beforeDuration != null && afterDuration != null &&
            afterDuration - beforeDuration >= DURATION_DELTA_FLOOR
        ) {
            return true
        }
        return false
    }

    /** Text alone never grants credit. Counters and duration do. */
    fun textIndicatesCredit(text: String?): Boolean = false

    fun isDone(rawText: String, ctaText: String?, completed: IntArray, max: IntArray): Boolean {
        if (containsDone(rawText) || containsDone(ctaText)) return true
        if (completed.size < 3 || max.size < 3) return false
        return (0..2).all { completed[it] == max[it] }
    }

    fun isLogin(rawText: String, hasStageList: Boolean): Boolean {
        if (rawText.contains("登录/注册")) return true
        if (rawText.contains("登录") && !hasStageList) return true
        return false
    }

    fun quotaReached(stageTotal: Int): Boolean = stageTotal >= FULL_QUOTA

    /**
     * Daily flags are only for a normal run (maxAds >= 21) that finished,
     * hit 21 credits, or ran out of CTA after the first slot. Test mode never writes.
     */
    fun shouldWriteDailyFlag(
        maxAds: Int,
        done: Boolean,
        credits: Int,
        quotaExhausted: Boolean
    ): Boolean {
        if (maxAds < FULL_QUOTA) return false
        return done || quotaExhausted || credits >= maxAds
    }

    private fun containsDone(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return DONE_PHRASES.any { text.contains(it) }
    }
}
