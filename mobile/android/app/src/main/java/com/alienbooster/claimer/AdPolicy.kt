package com.alienbooster.claimer

/** Decisions shared by the accessibility loop and its focused unit tests. */
object AdPolicy {
    enum class OutsideAction { WAIT, RETURN, PAUSE }
    private val fraction = Regex("(\\d+)\\s*/\\s*(\\d+)")
    private val countdown = Regex("\\d+\\s*(?:秒|s|S)|(?:跳过|关闭|skip).{0,8}\\d+|\\d+.{0,8}(?:跳过|关闭|skip)", RegexOption.IGNORE_CASE)
    private val reward = Regex("奖励已到账|奖励已发放|已获得奖励|领取成功|已领取奖励|恭喜获得|恭喜完成|任务已完成|reward received|reward granted", RegexOption.IGNORE_CASE)
    fun stageCounts(labels: List<String>): List<Int>? {
        if (labels.size != 3) return null
        val max = listOf(9, 3, 9)
        return labels.mapIndexed { i, label ->
            val match = fraction.find(label)
            when {
                match != null && match.groupValues[2].toIntOrNull() == max[i] ->
                    match.groupValues[1].toIntOrNull()?.takeIf { it in 0..max[i] } ?: return null
                label.contains("已完成") -> max[i]
                label.contains("待解锁") -> 0
                else -> return null
            }
        }
    }
    fun complete(stages: List<Int>?) = stages == listOf(9, 3, 9)
    fun credited(before: List<Int>?, after: List<Int>?, beforeDuration: Int?, afterDuration: Int?): Boolean {
        if (before != null && after != null && after.sum() > before.sum()) return true
        if (beforeDuration == null || afterDuration == null) return false
        val delta = afterDuration - beforeDuration
        // A running target timer may tick while the normal video plays.
        return listOf(600, 1200, 1800).any { delta in (it - 10)..(it + 5) }
    }
    fun hasCountdown(text: String) = countdown.containsMatchIn(text)
    fun rewardConfirmed(text: String) = reward.containsMatchIn(text)
    fun readyCloseText(text: String): Boolean {
        val compact = text.trim().replace(Regex("\\s+"), "")
        if (compact.any(Char::isDigit)) return false
        return compact in setOf("跳过", "跳过广告", "关闭广告", "关闭", "返回领取", "返回领奖", "领取奖励", "完成", "Skip", "SKIP", "Close", "CLOSE", "close")
    }
    fun closeAllowed(text: String, reward: Boolean): Boolean {
        val compact = text.trim().replace(Regex("\\s+"), "")
        if (!readyCloseText(compact)) return false
        // Plain close buttons may exist before a video has finished. A skip
        // button without a countdown is the SDK's normal enabled exit.
        return reward || compact in setOf("跳过", "跳过广告", "返回领取", "返回领奖", "领取奖励", "Skip", "SKIP")
    }
    fun outsideAction(
        samePackage: Boolean,
        sinceStartMs: Long,
        outsideElapsedMs: Long,
        adJump: Boolean,
        outsideReturns: Int
    ): OutsideAction {
        val grace = when {
            samePackage && sinceStartMs < 12_000L -> 12_000L
            adJump -> 8_000L
            else -> 2_500L
        }
        if (outsideElapsedMs <= grace) return OutsideAction.WAIT
        return if (adJump && outsideReturns < 2) OutsideAction.RETURN else OutsideAction.PAUSE
    }
}
