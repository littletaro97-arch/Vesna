package com.littletaro.vesna.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.Window

/**
 * 应用界面配色：浅色 / 深色两套，跟随系统深色模式开关（Configuration.uiMode）。
 *
 * 只覆盖中性界面色（背景、卡片、文字、按钮、描边）；
 * 悬浮按钮上的功能色（待机蓝 / 触发绿）保持固定，深浅模式下不变。
 */
object ThemeColors {
    fun of(context: Context): Palette = Palette(
        dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES,
    )

    fun isNight(context: Context): Boolean = of(context).dark

    /** 把状态栏与导航栏刷成当前主题背景色，并按主题设置系统栏图标亮暗。 */
    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity) {
        val palette = of(activity)
        activity.window.statusBarColor = palette.screenBackground
        activity.window.navigationBarColor = palette.screenBackground
        applySystemBarLightFlags(activity.window, palette.lightSystemBars)
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarLightFlags(window: Window, light: Boolean) {
        // minSdk 26 起这两个标志都已可用，无需再按版本分支。
        var flags = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        flags = flags or if (light) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        window.decorView.systemUiVisibility = flags
    }

    /** 供 Activity 在 onConfigurationChanged 里判断是否需要在深浅色之间重建。 */
    fun isNightConfig(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    class Palette(val dark: Boolean) {
        val lightSystemBars: Boolean get() = !dark

        val screenBackground = c(247, 248, 250, 22, 24, 29)
        val cardBackground = c(255, 255, 255, 34, 37, 44)
        val cardStroke = c(226, 230, 236, 52, 56, 66)
        val textPrimary = c(26, 31, 39, 235, 237, 241)
        val textSecondary = c(90, 100, 114, 154, 163, 175)
        val textFaint = c(122, 132, 148, 118, 126, 138)
        val accent = c(116, 167, 255, 143, 180, 255)
        val accentSoft = c(226, 234, 246, 40, 50, 68)
        val buttonPrimaryText = c(9, 17, 28, 9, 17, 28)
        val buttonSecondaryBg = c(232, 236, 242, 44, 48, 57)
        val buttonSecondaryStroke = c(206, 212, 222, 62, 67, 78)
        val subtleBoxBg = c(244, 246, 249, 30, 33, 39)
        val subtleBoxStroke = c(222, 227, 234, 48, 52, 61)
        val noticeBg = c(255, 245, 220, 58, 50, 30)
        val noticeStroke = c(214, 176, 106, 140, 112, 60)
        val statusGreen = c(24, 130, 90, 94, 211, 153)
        val statusYellow = c(176, 122, 26, 230, 170, 80)
        val statusRed = c(200, 60, 60, 235, 110, 110)
        val green = c(102, 217, 163, 102, 217, 163)
        val greenBadgeBg = c(24, 58, 44, 24, 58, 44)
        val yellowBadgeText = c(255, 193, 107, 255, 193, 107)
        val yellowBadgeBg = c(64, 48, 24, 64, 48, 24)

        private fun c(lr: Int, lg: Int, lb: Int, dr: Int, dg: Int, db: Int): Int =
            Color.rgb(
                if (dark) dr else lr,
                if (dark) dg else lg,
                if (dark) db else lb,
            )
    }
}
