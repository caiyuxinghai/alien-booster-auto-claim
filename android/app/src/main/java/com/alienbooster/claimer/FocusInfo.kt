package com.alienbooster.claimer

data class FocusInfo(
    val pkg: String,
    val activity: String,
    val user: Int,
    val raw: String = ""
) {
    val onMain: Boolean
        get() = pkg == TARGET_PKG && activity.endsWith("MainActivity")

    /**
     * An ad is any target-package activity that is not MainActivity.
     * That covers reward, AdActivity, PortraitADActivity, interstitial, and the rest.
     */
    val isAd: Boolean
        get() = pkg == TARGET_PKG && !activity.endsWith("MainActivity")

    companion object {
        const val TARGET_PKG = "com.etalien.booster"

        private val WITH_USER = Regex(
            """mCurrentFocus=Window\{[^}]*?\s+(u\d+)\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\}"""
        )
        private val NO_USER = Regex(
            """mCurrentFocus=Window\{[^}]*?\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\}"""
        )

        fun parse(text: String?): FocusInfo? {
            if (text.isNullOrBlank()) return null
            val withUser = WITH_USER.find(text)
            if (withUser != null) {
                val user = withUser.groupValues[1].drop(1).toIntOrNull() ?: 0
                return FocusInfo(
                    pkg = withUser.groupValues[2],
                    activity = withUser.groupValues[3],
                    user = user,
                    raw = text
                )
            }
            val noUser = NO_USER.find(text)
            if (noUser != null) {
                return FocusInfo(
                    pkg = noUser.groupValues[1],
                    activity = noUser.groupValues[2],
                    user = 0,
                    raw = text
                )
            }
            return null
        }
    }
}
