package com.alienbooster.claimer

import android.app.KeyguardManager
import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Per-user reward loop from SPEC section 7.
 *
 * Kill timing is fixed: 18s after the tap command returns AND 15s after the ad
 * activity is first seen, then 2s settle. Returning to MainActivity on its own
 * is the only early exit, and that still waits [SETTLE_FAST_MS]. A 30s fallback
 * applies only when no ad activity opened. "广告加载失败" is never a credit or a
 * failure by itself.
 *
 * TODO: fastRefresh is stored but ignored. v1 always force-stops and cold-starts.
 * A 30s fallback must not cut an ad that opened late; that round waits out the
 * 15s-after-open rule, and the 45s cap stretches by the same amount.
 */
class RewardEngine(
    private val context: Context,
    private val stopped: () -> Boolean,
    private val maxAds: Int,
    private val userOverride: Int,
    private val fastRefresh: Boolean
) {
    private var activeUser: Int = -1
    private var selftestMode: Boolean = false

    fun run(): Int {
        var code = EXIT_OK
        try {
            code = runBody()
        } catch (_: Stopped) {
            log("已停止")
            publish { it.copy(error = null) }
            code = EXIT_STOPPED
            abortForceStop()
        } catch (t: Throwable) {
            log("运行异常 ${t.javaClass.simpleName}: ${t.message}")
            publish { it.copy(error = t.message ?: "运行异常") }
            code = EXIT_ERROR
            abortForceStop()
        } finally {
            publish { it.copy(running = false) }
        }
        return code
    }

    fun selfTest(): Int {
        selftestMode = true
        var code = EXIT_OK
        try {
            code = selfTestBody()
        } catch (_: Stopped) {
            log("自检已停止")
            publish { it.copy(error = null) }
            code = EXIT_STOPPED
        } catch (t: Throwable) {
            log("自检异常 ${t.javaClass.simpleName}: ${t.message}")
            publish { it.copy(error = t.message ?: "自检异常") }
            code = EXIT_ERROR
        } finally {
            publish { it.copy(running = false, selftest = false) }
        }
        return code
    }

    private fun runBody(): Int {
        if (!ShizukuManager.ready()) {
            val label = ShizukuManager.statusLabel()
            log(label)
            publish { it.copy(error = label, shizuku = false) }
            return EXIT_NOT_READY
        }
        call { Shell.stayOn(true) }
        publish {
            it.copy(
                running = true,
                maxAds = maxAds,
                error = null,
                shizuku = true,
                selftest = false,
                day = DailyFlags.today()
            )
        }
        if (!unlock()) {
            val message = context.getString(R.string.unlock_failed)
            publish { it.copy(error = message) }
            return EXIT_UNLOCK
        }
        var code = EXIT_OK
        for (user in resolveUsers()) {
            checkStop()
            activeUser = user
            val one = runUser(user)
            if (one != EXIT_OK) return one
            code = EXIT_OK
        }
        log("全部结束")
        publish { it.copy(error = null) }
        return code
    }

    private fun runUser(user: Int): Int {
        publish {
            it.copy(
                running = true,
                user = user,
                slot = 0,
                credits = 0,
                maxAds = maxAds,
                error = null,
                day = DailyFlags.today()
            )
        }
        val normal = maxAds >= RewardPolicy.FULL_QUOTA
        if (DailyFlags.exists(context, user)) {
            if (normal) {
                log("user $user 今日已完成，跳过")
                return EXIT_OK
            }
            log("user $user 今日已有标记，测试模式继续且不覆盖")
        }
        val installed = call { Shell.isInstalled(user) }
        if (!installed) {
            log("user $user 未安装")
            publish { it.copy(error = "user $user 未安装目标应用") }
            return EXIT_NOT_INSTALLED
        }
        if (!ensureUserFront(user)) {
            publish { it.copy(error = "无法把 user $user 拉到前台") }
            return EXIT_FRONT
        }
        val opened = readStateWithRetry(user)
        if (opened == null) {
            publish { it.copy(error = "读不到主界面") }
            return EXIT_STATE
        }
        publishState(user, 0, 0, opened)
        if (opened.login) {
            log("未登录，停止")
            publish { it.copy(error = "未登录") }
            return EXIT_LOGIN
        }
        if (opened.done) {
            if (normal) {
                log("user $user 打开即已完成 ${opened.stageCompleted.toList()}")
                maybeFlag(user, done = true, credits = 0, quotaExhausted = false)
                return EXIT_OK
            }
            // Test mode only: the target still serves rewarded videos after the daily quota,
            // they just do not credit. Tapping one is the only way to exercise the whole
            // tap/watch/kill/force-stop loop without waiting for the next day's refresh.
            log("user $user 今日已完成，测试模式仍点一条（预期不入账）")
        }

        var credits = 0
        var fails = 0
        for (slot in 1..maxAds) {
            checkStop()
            publish { it.copy(slot = slot, credits = credits, user = user) }
            log("slot $slot/$maxAds user $user")
            val rawCta = ensureCta(user)
            val cta = if (!normal && slot == 1 && rawCta is CtaOutcome.Done) {
                val bounds = rawCta.state.ctaBounds
                if (bounds != null) {
                    log("测试模式：完成态仍点击 ${rawCta.state.ctaText}")
                    CtaOutcome.Ready(rawCta.state, bounds.centerX, bounds.centerY)
                } else {
                    rawCta
                }
            } else {
                rawCta
            }
            when (cta) {
                CtaOutcome.Login -> {
                    log("未登录，停止")
                    publish { it.copy(error = "未登录") }
                    return EXIT_LOGIN
                }
                is CtaOutcome.Done -> {
                    publishState(user, slot, credits, cta.state)
                    log("完成 ${cta.state.ctaText}")
                    maybeFlag(user, done = true, credits = credits, quotaExhausted = false)
                    return EXIT_OK
                }
                CtaOutcome.None -> {
                    if (slot == 1) {
                        log("首条没有领取按钮")
                        publish { it.copy(error = "没有领取按钮") }
                        return EXIT_NO_CTA
                    }
                    log("没有领取按钮，额度用尽")
                    maybeFlag(user, done = false, credits = credits, quotaExhausted = true)
                    return EXIT_OK
                }
                is CtaOutcome.Ready -> {
                    val before = readMain() ?: cta.state
                    log("tap ${cta.state.ctaText} @${cta.x},${cta.y}")
                    call { Shell.tap(cta.x, cta.y) }
                    // Clock starts after the tap command returns, so the 18s is not eaten by shell latency.
                    val tapTime = System.currentTimeMillis()
                    val watched = watch(tapTime)
                    log("watch=$watched")
                    settle(user)
                    val after = refresh(user)
                    if (after?.login == true) {
                        log("未登录，停止")
                        publish { it.copy(error = "未登录") }
                        return EXIT_LOGIN
                    }
                    val credited = after != null && RewardPolicy.credited(before, after)
                    publishState(user, slot, if (credited) credits + 1 else credits, after)
                    if (credited) {
                        credits += 1
                        fails = 0
                        val delta = durationDelta(before, after)
                        log("入账 credits=$credits stages=${after?.stageTotal()} durDelta=$delta")
                    } else {
                        fails += 1
                        log("未入账 consecutive=$fails stages=${after?.stageTotal()} durDelta=${durationDelta(before, after)}")
                    }
                    if (after != null &&
                        (after.done || RewardPolicy.quotaReached(after.stageTotal()))
                    ) {
                        log("达到完成条件 done=${after.done} total=${after.stageTotal()}")
                        maybeFlag(user, done = true, credits = credits, quotaExhausted = false)
                        return EXIT_OK
                    }
                    if (!credited) {
                        if (fails >= MAX_CONSECUTIVE_FAILS) {
                            log("连续失败 $MAX_CONSECUTIVE_FAILS 次，停止")
                            publish { it.copy(error = "连续失败 $MAX_CONSECUTIVE_FAILS 次") }
                            return EXIT_FAILS
                        }
                        log("等待 10s")
                        if (!sleep(10_000)) throw Stopped()
                    }
                }
            }
        }
        maybeFlag(user, done = false, credits = credits, quotaExhausted = false)
        log("user $user 结束 credits=$credits/$maxAds")
        return EXIT_OK
    }

    private fun selfTestBody(): Int {
        val user = if (userOverride >= 0) userOverride else 0
        activeUser = user
        log("=== selftest user=$user ===")
        log("shizuku=${ShizukuManager.statusLabel()}")
        publish {
            it.copy(
                running = true,
                selftest = true,
                user = user,
                slot = 0,
                maxAds = 0,
                error = null,
                day = DailyFlags.today(),
                shizuku = ShizukuManager.ready()
            )
        }
        if (!ShizukuManager.ready()) {
            log("selftest: Shizuku 未就绪")
            publish { it.copy(error = ShizukuManager.statusLabel(), shizuku = false) }
            return EXIT_NOT_READY
        }
        call { Shell.stayOn(true) }
        // Wake only. No swipe, no PIN, no tap, no force-stop.
        call { Shell.key(224) }
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked || km.isDeviceLocked) {
            log("selftest: 锁屏中，不滑动、不输入 PIN")
        }
        val user0 = call { Shell.isInstalled(0) }
        val user128 = call { Shell.isInstalled(128) }
        log("installed user0=$user0 user128=$user128")
        val present = if (user == 128) user128 else user0
        if (!present) {
            log("selftest: user $user 未安装")
            publish { it.copy(error = "user $user 未安装目标应用") }
            return EXIT_NOT_INSTALLED
        }
        log("selftest start user $user")
        call { Shell.start(user) }
        var state: UiState? = null
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            val focus = call { Shell.focus() }
            log("focus pkg=${focus?.pkg} act=${focus?.activity} user=${focus?.user}")
            state = dumpState()?.state
            if (state != null && (state.mainVisible || state.login || state.done)) break
            if (!sleep(1_000)) throw Stopped()
        }
        if (state == null) {
            log("selftest: 没有读到界面")
            publish { it.copy(error = "没有读到界面") }
            return EXIT_STATE
        }
        publishState(user, 0, 0, state)
        log(
            "stage1=${state.stageCompleted[0]}/${state.stageMax[0]} " +
                "stage2=${state.stageCompleted[1]}/${state.stageMax[1]} " +
                "stage3=${state.stageCompleted[2]}/${state.stageMax[2]}"
        )
        log(
            "durationSec=${state.durationSec} cta=${state.ctaText} done=${state.done} " +
                "login=${state.login} main=${state.mainVisible}"
        )
        log("userName=${state.userName} userId=${state.userId}")
        log("=== selftest done ===")
        publish { it.copy(error = null) }
        return EXIT_OK
    }

    private fun resolveUsers(): List<Int> {
        if (userOverride >= 0) return listOf(userOverride)
        when (AppSettings.userMode(context)) {
            "0" -> return listOf(0)
            "128" -> return listOf(128)
        }
        val users = ArrayList<Int>()
        users.add(0)
        if (AppSettings.autoDual(context)) {
            val installed = call { Shell.isInstalled(128) }
            log("pm list packages --user 128 -> $installed")
            if (installed) users.add(128)
        }
        return users
    }

    private fun unlock(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = km.isKeyguardLocked || km.isDeviceLocked
        call { Shell.key(224) }
        if (!locked) {
            log("screen wake")
            return true
        }
        if (!sleep(300)) throw Stopped()
        call { Shell.swipe(636, 2200, 636, 700, 300) }
        if (!sleep(600)) throw Stopped()
        val pin = AppSettings.pin(context)
        if (pin.isNotEmpty()) {
            call { Shell.text(pin) }
            if (!sleep(300)) throw Stopped()
            call { Shell.key(66) }
            if (!sleep(800)) throw Stopped()
        }
        val still = km.isKeyguardLocked || km.isDeviceLocked
        if (still) {
            log(context.getString(R.string.unlock_failed))
            return false
        }
        log("unlocked")
        return true
    }

    private fun ensureUserFront(user: Int): Boolean {
        repeat(3) { attempt ->
            checkStop()
            call { Shell.start(user) }
            if (!sleep(1_200)) throw Stopped()
            val focus = call { Shell.focus() }
            if (focus != null && focus.pkg == FocusInfo.TARGET_PKG && focus.user == user) {
                log("user $user front ${focus.activity}")
                return true
            }
            log("foreground user=${focus?.user} pkg=${focus?.pkg} want $user (${attempt + 1}/3)")
        }
        return false
    }

    private fun ensureCta(user: Int): CtaOutcome {
        for (attempt in 0 until 3) {
            checkStop()
            val focus = call { Shell.focus() }
            if (focus != null && focus.pkg == FocusInfo.TARGET_PKG && focus.user != user) {
                log("foreground is user ${focus.user}, want $user -> relaunch")
                relaunch(user, 1_200)
                continue
            }
            var snap = dumpState()
            if (clearBlockers(snap?.xml)) {
                if (!sleep(800)) throw Stopped()
                snap = dumpState()
            }
            classify(snap?.state)?.let { return it }
            val first = snap?.state
            if (first != null && isRealCta(first)) {
                val bounds = first.ctaBounds!!
                return CtaOutcome.Ready(first, bounds.centerX, bounds.centerY)
            }
            log("no CTA (attempt ${attempt + 1}) -> force-stop + start")
            relaunch(user, 1_500)
            snap = dumpState()
            if (clearBlockers(snap?.xml)) {
                if (!sleep(800)) throw Stopped()
                snap = dumpState()
            }
            classify(snap?.state)?.let { return it }
            val second = snap?.state
            if (second != null && isRealCta(second)) {
                val bounds = second.ctaBounds!!
                return CtaOutcome.Ready(second, bounds.centerX, bounds.centerY)
            }
        }
        return CtaOutcome.None
    }

    private fun watch(tapTime: Long): WatchResult {
        var adOpenAt: Long? = null
        var sawAd = false
        while (true) {
            checkStop()
            val opened = adOpenAt
            val elapsed = System.currentTimeMillis() - tapTime
            val hardLimit = if (opened != null) {
                maxOf(HARD_TIMEOUT_MS, (opened - tapTime) + KILL_AFTER_AD_OPEN_MS)
            } else {
                HARD_TIMEOUT_MS
            }
            if (elapsed > hardLimit) {
                if (opened != null) {
                    log("hard timeout ${hardLimit}ms, settle ${SETTLE_FAST_MS}ms")
                    if (!sleep(SETTLE_FAST_MS)) throw Stopped()
                } else {
                    log("hard timeout ${HARD_TIMEOUT_MS}ms")
                }
                return WatchResult.TIMEOUT
            }
            val focus = call { Shell.focus() }
            if (focus?.isAd == true) {
                if (adOpenAt == null) {
                    adOpenAt = System.currentTimeMillis()
                    log("ad start +${adOpenAt!! - tapTime}ms ${focus.activity}")
                }
                sawAd = true
            }
            if (focus?.onMain == true && sawAd) {
                log("back on main, confirm ${SETTLE_FAST_MS}ms")
                if (confirmStillMain()) {
                    log("on-main early return")
                    return WatchResult.ON_MAIN
                }
                log("ad returned during settle, keep watching")
                continue
            }
            val adAt = adOpenAt
            val now = System.currentTimeMillis()
            if (adAt != null &&
                now - tapTime >= KILL_AT_MS &&
                now - adAt >= KILL_AFTER_AD_OPEN_MS
            ) {
                log("kill window tap=${now - tapTime}ms ad=${now - adAt}ms, settle ${SETTLE_FAST_MS}ms")
                if (!sleep(SETTLE_FAST_MS)) throw Stopped()
                return WatchResult.KILL
            }
            if (adOpenAt == null && System.currentTimeMillis() - tapTime > FALLBACK_MS) {
                log("fallback ${FALLBACK_MS}ms, no ad opened, settle ${SETTLE_FAST_MS}ms")
                if (!sleep(SETTLE_FAST_MS)) throw Stopped()
                return WatchResult.FALLBACK
            }
            if (!sleep(FOCUS_POLL_MS)) throw Stopped()
        }
    }

    /** The 2s on-main settle. If the ad activity comes back, the watch loop continues. */
    private fun confirmStillMain(): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SETTLE_FAST_MS) {
            checkStop()
            val focus = call { Shell.focus() }
            if (focus?.isAd == true) return false
            if (!sleep(FOCUS_POLL_MS)) throw Stopped()
        }
        val focus = call { Shell.focus() }
        if (focus?.isAd == true) return false
        return focus?.onMain == true
    }

    private fun settle(user: Int) {
        val focus = call { Shell.focus() }
        if (focus?.isAd == true) {
            log("ad still open -> BACK")
            call { Shell.key(4) }
            if (!sleep(600)) throw Stopped()
            val still = call { Shell.focus() }
            if (still?.isAd == true) {
                log("ad still open after BACK -> force-stop")
                call { Shell.forceStop(user) }
            }
        }
        var backs = 0
        var clears = 0
        while (backs < 3) {
            val now = call { Shell.focus() }
            if (now?.onMain == true) break
            if (now != null && now.pkg != FocusInfo.TARGET_PKG && clears < 2) {
                val snap = dumpState()
                if (clearBlockers(snap?.xml)) {
                    clears += 1
                    if (!sleep(600)) throw Stopped()
                    continue
                }
            }
            backs += 1
            log("not on main -> BACK $backs")
            call { Shell.key(4) }
            if (!sleep(600)) throw Stopped()
        }
    }

    private fun refresh(user: Int): UiState? {
        if (fastRefresh) {
            log("fastRefresh=true ignored; force-stop + cold start")
        }
        call { Shell.forceStop(user) }
        if (!sleep(500)) throw Stopped()
        var attempt = 0
        var last: UiState? = null
        while (attempt < 3) {
            attempt += 1
            call { Shell.start(user) }
            val waited = waitMain()
            if (waited != null) return waited
            val focus = call { Shell.focus() }
            if (focus != null && focus.pkg != FocusInfo.TARGET_PKG) {
                log("display owned by ${focus.pkg}, retry start $attempt/3")
                val snap = dumpState()
                clearBlockers(snap?.xml)
                if (!sleep(600)) throw Stopped()
                continue
            }
            last = dumpState()?.state
            if (last?.mainVisible == true || last?.done == true || last?.login == true) return last
        }
        return last
    }

    private fun waitMain(): UiState? {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            val focus = call { Shell.focus() }
            if (focus != null && focus.pkg.isNotEmpty() && focus.pkg != FocusInfo.TARGET_PKG) {
                val snap = dumpState()
                if (clearBlockers(snap?.xml)) {
                    if (!sleep(600)) throw Stopped()
                    continue
                }
                return null
            }
            val state = dumpState()?.state
            if (state?.mainVisible == true || state?.login == true || state?.done == true) {
                if (!sleep(200)) throw Stopped()
                return dumpState()?.state ?: state
            }
            if (!sleep(1_000)) throw Stopped()
        }
        return null
    }

    private fun readStateWithRetry(user: Int): UiState? {
        var state = readMain()
        var n = 0
        while (state == null && n < 3) {
            n += 1
            log("读界面失败，重开 $n/3")
            relaunch(user, 1_500)
            state = readMain()
        }
        return state
    }

    private fun readMain(): UiState? {
        val state = dumpState()?.state ?: return null
        if (state.login || state.mainVisible || state.done || !state.ctaText.isNullOrBlank()) return state
        return null
    }

    private fun relaunch(user: Int, waitMs: Long) {
        call { Shell.forceStop(user) }
        if (!sleep(500)) throw Stopped()
        call { Shell.start(user) }
        if (!sleep(waitMs)) throw Stopped()
    }

    private fun clearBlockers(xml: String?): Boolean {
        val hit = BlockerScan.find(xml) ?: return false
        log("${hit.kind} -> tap ${hit.x},${hit.y}")
        call { Shell.tap(hit.x, hit.y) }
        return true
    }

    private fun maybeFlag(user: Int, done: Boolean, credits: Int, quotaExhausted: Boolean) {
        val would = done || quotaExhausted || credits >= maxAds
        if (maxAds < RewardPolicy.FULL_QUOTA) {
            if (would) log("test mode: skip daily flag")
            return
        }
        if (!RewardPolicy.shouldWriteDailyFlag(maxAds, done, credits, quotaExhausted)) return
        DailyFlags.write(context, user, credits)
        log("flag claimed_u${user}_${DailyFlags.today()}")
    }

    private fun dumpState(): Snap? {
        val xml = call { Shell.dumpXml() } ?: return null
        if (xml.isBlank()) return null
        return Snap(xml, UiXmlParser.parse(xml))
    }

    private fun isRealCta(state: UiState): Boolean {
        val text = state.ctaText ?: return false
        if (state.ctaBounds == null) return false
        if (state.done || state.login) return false
        if (text.contains("重试") || text.contains("加载失败")) return false
        return true
    }

    private fun classify(state: UiState?): CtaOutcome? {
        if (state == null) return null
        if (state.login) return CtaOutcome.Login
        if (state.done) return CtaOutcome.Done(state)
        return null
    }

    private fun publishState(user: Int, slot: Int, credits: Int, state: UiState?) {
        publish { current ->
            current.copy(
                running = true,
                user = user,
                slot = slot,
                maxAds = if (selftestMode) current.maxAds else maxAds,
                credits = credits,
                stage1 = state?.stageCompleted?.getOrElse(0) { current.stage1 } ?: current.stage1,
                stage2 = state?.stageCompleted?.getOrElse(1) { current.stage2 } ?: current.stage2,
                stage3 = state?.stageCompleted?.getOrElse(2) { current.stage3 } ?: current.stage3,
                durationSec = state?.durationSec ?: current.durationSec,
                day = DailyFlags.today(),
                shizuku = true
            )
        }
    }

    private fun durationDelta(before: UiState?, after: UiState?): Int? {
        val from = before?.durationSec ?: return null
        val to = after?.durationSec ?: return null
        return to - from
    }

    private fun abortForceStop() {
        if (selftestMode) return
        val user = activeUser
        if (user < 0) return
        Thread {
            try {
                Shell.forceStop(user)
            } catch (_: Throwable) {
            }
        }.apply {
            name = "claimer-abort"
            isDaemon = false
            start()
        }
    }

    private fun log(message: String) {
        LogStore.append(context, message)
        val shizuku = try {
            ShizukuManager.ready()
        } catch (_: Throwable) {
            false
        }
        StatusStore.note(context, message, shizuku)
    }

    private fun publish(block: (StatusStore.Snapshot) -> StatusStore.Snapshot) {
        StatusStore.update(context, block)
    }

    private fun checkStop() {
        if (stopped() || Thread.currentThread().isInterrupted) throw Stopped()
    }

    private fun sleep(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (stopped()) return false
            val step = minOf(200L, left)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= step
        }
        return !stopped()
    }

    private fun <T> call(block: () -> T): T {
        if (stopped() || Thread.currentThread().isInterrupted) throw Stopped()
        val task = FutureTask(Callable { block() })
        val thread = Thread(task, "claimer-shell")
        thread.isDaemon = true
        thread.start()
        try {
            while (!task.isDone) {
                if (stopped() || Thread.currentThread().isInterrupted) throw Stopped()
                try {
                    return task.get(200, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                }
            }
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Stopped()
        }
    }

    private class Stopped : RuntimeException()

    private data class Snap(val xml: String, val state: UiState)

    private sealed class CtaOutcome {
        class Ready(val state: UiState, val x: Int, val y: Int) : CtaOutcome()
        class Done(val state: UiState) : CtaOutcome()
        object Login : CtaOutcome()
        object None : CtaOutcome()
    }

    enum class WatchResult { ON_MAIN, KILL, FALLBACK, TIMEOUT }

    companion object {
        const val KILL_AT_MS = 18_000L
        const val KILL_AFTER_AD_OPEN_MS = 15_000L
        const val SETTLE_FAST_MS = 2_000L
        const val FALLBACK_MS = 30_000L
        const val HARD_TIMEOUT_MS = 45_000L
        const val FOCUS_POLL_MS = 300L
        const val MAX_CONSECUTIVE_FAILS = 4

        const val EXIT_OK = 0
        const val EXIT_ERROR = 1
        const val EXIT_NOT_READY = 2
        const val EXIT_NO_CTA = 3
        const val EXIT_LOGIN = 4
        const val EXIT_NOT_INSTALLED = 5
        const val EXIT_FAILS = 6
        const val EXIT_UNLOCK = 7
        const val EXIT_STOPPED = 8
        const val EXIT_STATE = 9
        const val EXIT_FRONT = 10
    }
}
