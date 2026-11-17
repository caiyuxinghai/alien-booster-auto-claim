package com.alienbooster.claimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusParserTest {
    @Test
    fun user0MainActivity() {
        val info = checkNotNull(
            FocusInfo.parse(
                "mCurrentFocus=Window{abc123 u0 com.etalien.booster/com.etalien.booster.ui.MainActivity}"
            )
        )
        assertEquals(FocusInfo.TARGET_PKG, info.pkg)
        assertEquals("com.etalien.booster.ui.MainActivity", info.activity)
        assertEquals(0, info.user)
        assertTrue(info.onMain)
        assertFalse(info.isAd)
    }

    @Test
    fun user128AdActivity() {
        val info = checkNotNull(
            FocusInfo.parse(
                "mCurrentFocus=Window{abc u128 com.etalien.booster/com.qq.e.ads.PortraitADActivity}"
            )
        )
        assertEquals(128, info.user)
        assertEquals("com.qq.e.ads.PortraitADActivity", info.activity)
        assertFalse(info.onMain)
        assertTrue(info.isAd)
    }

    @Test
    fun rewardActivityIsAd() {
        val info = checkNotNull(
            FocusInfo.parse(
                "mCurrentFocus=Window{abc u0 com.etalien.booster/com.beizi.ad.RewardVideoActivity}"
            )
        )
        assertEquals(0, info.user)
        assertTrue(info.isAd)
        assertFalse(info.onMain)
    }

    @Test
    fun missingUserTokenDefaultsToZero() {
        val info = checkNotNull(
            FocusInfo.parse(
                "mCurrentFocus=Window{abc123 com.etalien.booster/com.etalien.booster.ui.MainActivity}"
            )
        )
        assertEquals(0, info.user)
        assertTrue(info.onMain)
        assertFalse(info.isAd)
    }

    @Test
    fun otherPackageIsNeitherMainNorAd() {
        val info = checkNotNull(
            FocusInfo.parse(
                "mCurrentFocus=Window{abc u0 com.android.settings/com.android.settings.Settings}"
            )
        )
        assertEquals(0, info.user)
        assertFalse(info.onMain)
        assertFalse(info.isAd)
    }

    @Test
    fun blankOrMissingFocus() {
        assertNull(FocusInfo.parse(null))
        assertNull(FocusInfo.parse(""))
        assertNull(FocusInfo.parse("mCurrentFocus=null"))
    }
}
