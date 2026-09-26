package com.littletaro.vesna.core

import android.content.Context
import android.content.res.Configuration

/**
 * 悬浮按钮的外观与可见范围配置。
 *
 * 位置用「屏幕宽高的比例」而不是绝对像素保存，这样屏幕旋转、分辨率变化、
 * 甚至换设备后，按钮仍然落在视觉上相同的位置。
 *
 * 为了处理游戏（横屏）与最近任务/桌面（竖屏）之间方向切换导致按钮跳位的问题，
 * 位置按方向分别保存：横屏一套、竖屏一套。加载时按当前方向读取，保存时按当前方向写入。
 */
data class OverlayConfig(
    /** 用户是否已开启悬浮按钮（重启后按此自动恢复） */
    val enabled: Boolean = false,
    /** 按钮中心点横坐标占屏幕宽度的比例 */
    val xRatio: Float = 0.90f,
    /** 按钮中心点纵坐标占屏幕高度的比例 */
    val yRatio: Float = 0.55f,
    /** 按钮直径（dp） */
    val sizeDp: Int = 56,
    /** 不透明度百分比（30–100） */
    val alphaPercent: Int = 85,
    /** 是否只在指定应用里显示 */
    val restrictToApps: Boolean = false,
    /** 指定应用的包名集合 */
    val targetPackages: Set<String> = emptySet(),
    /**
     * 是否把自己从「最近任务」里隐藏。
     *
     * 只影响本应用任务在最近任务列表里的可见性，由 Activity 启动时按此设置即时生效
     * （见 [RecentsVisibility]）—— 因此可以做成随时开关，不需要重装或改 manifest。
     * 默认开启：这样清理后台时不会把它连常驻服务一起清掉。
     */
    val hideFromRecents: Boolean = true,
    /**
     * 切到后台后是否自动返回游戏（原神专属优化）。
     * 默认关闭：用户需要到「游戏优化」里手动开启。
     */
    val autoReturnEnabled: Boolean = false,
    /**
     * 自动返回的等待时间，单位毫秒。默认 8 秒。
     */
    val autoReturnDelayMs: Long = 8_000L,
    /**
     * 是否开启原神体力条自动切后台。
     *
     * 开启后，[com.littletaro.vesna.overlay.OverlayService] 会通过 MediaProjection 周期性截屏，
     * 在屏幕底部中央检测绿色体力条；当绿色比例低于阈值时触发切后台。
     */
    val staminaAutoSwitchEnabled: Boolean = false,
    /**
     * 体力条绿色像素占比阈值（百分比）。低于此值视为体力耗尽。
     * 默认 25：在 1920×884 分辨率下，满体力绿色占比约 40-60%，耗尽时接近 0。
     */
    val staminaThresholdPercent: Int = 25,
    /**
     * 连续确认帧数，避免单帧误报。每 500ms 采样一次，默认 2 帧 ≈ 1 秒。
     */
    val staminaConfirmFrames: Int = 2,
) {
    val alpha: Float get() = alphaPercent.coerceIn(MIN_ALPHA_PERCENT, 100) / 100f

    val sizePx: Int get() = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    companion object {
        const val MIN_SIZE_DP = 40
        const val MAX_SIZE_DP = 88
        const val MIN_ALPHA_PERCENT = 30
    }
}

object OverlayPrefs {
    private const val PREFS_NAME = "vesna_overlay_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X_RATIO = "x_ratio"
    private const val KEY_Y_RATIO = "y_ratio"
    private const val KEY_X_RATIO_LANDSCAPE = "x_ratio_landscape"
    private const val KEY_Y_RATIO_LANDSCAPE = "y_ratio_landscape"
    private const val KEY_X_RATIO_PORTRAIT = "x_ratio_portrait"
    private const val KEY_Y_RATIO_PORTRAIT = "y_ratio_portrait"
    private const val KEY_SIZE_DP = "size_dp"
    private const val KEY_ALPHA = "alpha_percent"
    private const val KEY_RESTRICT = "restrict_to_apps"
    private const val KEY_PACKAGES = "target_packages"
    private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
    private const val KEY_AUTO_RETURN_ENABLED = "auto_return_enabled"
    private const val KEY_AUTO_RETURN_DELAY_MS = "auto_return_delay_ms"
    private const val KEY_STAMINA_AUTO_SWITCH_ENABLED = "stamina_auto_switch_enabled"
    private const val KEY_STAMINA_THRESHOLD_PERCENT = "stamina_threshold_percent"
    private const val KEY_STAMINA_CONFIRM_FRAMES = "stamina_confirm_frames"

    private const val PACKAGE_SEPARATOR = "|"

    private fun isLandscape(context: Context): Boolean =
        context.applicationContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun load(context: Context): OverlayConfig {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = OverlayConfig()
        val landscape = isLandscape(context)

        // 旧的 x_ratio/y_ratio 作为首次迁移或单方向数据的回退。
        val fallbackX = prefs.getFloat(KEY_X_RATIO, defaults.xRatio)
        val fallbackY = prefs.getFloat(KEY_Y_RATIO, defaults.yRatio)

        val (xRatio, yRatio) = if (landscape) {
            prefs.getFloat(KEY_X_RATIO_LANDSCAPE, fallbackX) to
                prefs.getFloat(KEY_Y_RATIO_LANDSCAPE, fallbackY)
        } else {
            prefs.getFloat(KEY_X_RATIO_PORTRAIT, fallbackX) to
                prefs.getFloat(KEY_Y_RATIO_PORTRAIT, fallbackY)
        }

        return OverlayConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            xRatio = xRatio,
            yRatio = yRatio,
            sizeDp = prefs.getInt(KEY_SIZE_DP, defaults.sizeDp),
            alphaPercent = prefs.getInt(KEY_ALPHA, defaults.alphaPercent),
            restrictToApps = prefs.getBoolean(KEY_RESTRICT, defaults.restrictToApps),
            targetPackages = prefs.getString(KEY_PACKAGES, null)
                ?.split(PACKAGE_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: defaults.targetPackages,
            hideFromRecents = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, defaults.hideFromRecents),
            autoReturnEnabled = prefs.getBoolean(KEY_AUTO_RETURN_ENABLED, defaults.autoReturnEnabled),
            autoReturnDelayMs = prefs.getLong(KEY_AUTO_RETURN_DELAY_MS, defaults.autoReturnDelayMs),
            staminaAutoSwitchEnabled = prefs.getBoolean(KEY_STAMINA_AUTO_SWITCH_ENABLED, defaults.staminaAutoSwitchEnabled),
            staminaThresholdPercent = prefs.getInt(KEY_STAMINA_THRESHOLD_PERCENT, defaults.staminaThresholdPercent),
            staminaConfirmFrames = prefs.getInt(KEY_STAMINA_CONFIRM_FRAMES, defaults.staminaConfirmFrames),
        )
    }

    fun save(context: Context, config: OverlayConfig) {
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_SIZE_DP, config.sizeDp)
            .putInt(KEY_ALPHA, config.alphaPercent)
            .putBoolean(KEY_RESTRICT, config.restrictToApps)
            .putString(KEY_PACKAGES, config.targetPackages.joinToString(PACKAGE_SEPARATOR))
            .putBoolean(KEY_HIDE_FROM_RECENTS, config.hideFromRecents)
            .putBoolean(KEY_AUTO_RETURN_ENABLED, config.autoReturnEnabled)
            .putLong(KEY_AUTO_RETURN_DELAY_MS, config.autoReturnDelayMs)
            .putBoolean(KEY_STAMINA_AUTO_SWITCH_ENABLED, config.staminaAutoSwitchEnabled)
            .putInt(KEY_STAMINA_THRESHOLD_PERCENT, config.staminaThresholdPercent)
            .putInt(KEY_STAMINA_CONFIRM_FRAMES, config.staminaConfirmFrames)

        // 同时写旧 key，作为方向未保存时的回退；再按方向写专属 key。
        editor.putFloat(KEY_X_RATIO, config.xRatio)
        editor.putFloat(KEY_Y_RATIO, config.yRatio)
        if (isLandscape(context)) {
            editor.putFloat(KEY_X_RATIO_LANDSCAPE, config.xRatio)
            editor.putFloat(KEY_Y_RATIO_LANDSCAPE, config.yRatio)
        } else {
            editor.putFloat(KEY_X_RATIO_PORTRAIT, config.xRatio)
            editor.putFloat(KEY_Y_RATIO_PORTRAIT, config.yRatio)
        }
        editor.apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        save(context, load(context).copy(enabled = enabled))
    }
}
