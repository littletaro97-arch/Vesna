package com.littletaro.vesna

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.littletaro.vesna.core.ForegroundAppTracker
import com.littletaro.vesna.core.GameSpecialConfig
import com.littletaro.vesna.core.GameSpecialPrefs
import com.littletaro.vesna.core.OperationLog
import com.littletaro.vesna.core.OverlayPrefs
import com.littletaro.vesna.core.RuntimeProtection
import com.littletaro.vesna.overlay.OverlayService
import com.littletaro.vesna.ui.applyTheme
import com.littletaro.vesna.ui.badgeChip
import com.littletaro.vesna.ui.cardContainer
import com.littletaro.vesna.ui.dp
import com.littletaro.vesna.ui.labelText
import com.littletaro.vesna.ui.numberSlider
import com.littletaro.vesna.ui.palette
import com.littletaro.vesna.ui.replaceContentPreservingScroll
import com.littletaro.vesna.ui.screenRoot
import com.littletaro.vesna.ui.screenScroll
import com.littletaro.vesna.ui.sectionLabel
import com.littletaro.vesna.ui.toggleRow

/**
 * 游戏优化设置页：原神针对性功能。
 *
 * 提供「切后台后自动返回」和原神体力条自动切后台实验功能。
 */
class GameOptimizerActivity : Activity() {

    private val nightAtCreate by lazy { com.littletaro.vesna.core.ThemeColors.isNight(this) }
    private val gamePackage by lazy {
        intent.getStringExtra(GameSpecialPrefs.EXTRA_GAME_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?: GameSpecialPrefs.resolveGenshinPackage(this)
    }
    private val gameLabel by lazy {
        intent.getStringExtra(GameSpecialPrefs.EXTRA_GAME_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: ForegroundAppTracker.applicationLabel(this, gamePackage)
    }
    private var promptedForMissingProjection = false

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        renderSafely()
    }

    override fun onResume() {
        super.onResume()
        maybeRequestSavedLabAuthorization()
        renderSafely()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (com.littletaro.vesna.core.ThemeColors.isNightConfig(newConfig) != nightAtCreate) recreate()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            GameSpecialPrefs.setActivePackage(this, gamePackage)
            saveConfig { it.copy(staminaAutoSwitchEnabled = true) }
            OverlayPrefs.setEnabled(this, true)
            OverlayService.requestMediaProjection(this, data)
            OperationLog.record(this, "已授权 MediaProjection，开启体力条自动切后台")
        } else {
            OperationLog.record(this, "用户取消或未授权 MediaProjection")
        }
        renderSafely()
    }

    private fun renderSafely() {
        if (isFinishing || isDestroyed) return
        replaceContentPreservingScroll { buildScreen() }
    }

    private fun buildScreen(): View {
        val config = GameSpecialPrefs.load(this, gamePackage)
        val profileActive = GameSpecialPrefs.isActive(this, gamePackage)
        val scroll = screenScroll()
        val root = screenRoot()
        scroll.addView(root, LinearLayout.LayoutParams(-1, -2))

        root.addView(
            labelText("‹ 返回", 15f, palette().accent).apply {
                setPadding(0, dp(4), 0, dp(12))
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(labelText("$gameLabel 特调", 24f, palette().textPrimary))
        root.addView(
            labelText(
                "本方案独立保存；切换到其它游戏特调时，本方案设置会保留。体力条识别仍处于实验阶段。",
                12f,
                palette().textSecondary,
                top = 6,
            ),
        )

        root.addView(
            toggleRow(
                title = "设为当前生效特调",
                description = if (profileActive) {
                    if (OverlayService.isRunning()) {
                        "${gameLabel}特调正在生效；启用其它游戏特调会自动切换。"
                    } else {
                        "已选中此方案；开启悬浮按钮后生效。"
                    }
                } else {
                    "尚未生效；启用后会自动停用其它游戏方案，原有设置保留。"
                },
                checked = profileActive,
                onToggle = { toggleActiveProfile(profileActive, config) },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
        )

        // ---- 切后台后自动返回
        root.addView(sectionLabel("切后台后自动返回", top = 26))
        root.addView(
            toggleRow(
                title = "自动返回游戏",
                description = if (config.autoReturnEnabled) {
                    "已开启 —— 手动触发返回刚才的应用；体力触发倒计时结束后返回 $gameLabel。"
                } else {
                    "已关闭 —— 切到后台后停留在最近任务/桌面，不会自动返回。"
                },
                checked = config.autoReturnEnabled,
                onToggle = { toggleAutoReturn(config) },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            numberSlider(
                title = "返回倒计时",
                value = (config.autoReturnDelayMs / 1_000L).toInt(),
                range = 2..15,
                label = { "$it 秒" },
                onChanged = { seconds -> saveConfig { it.copy(autoReturnDelayMs = seconds * 1_000L) } },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            labelText(
                "建议设为 10 秒：为体力恢复和游戏画面切换留出余量。",
                12f,
                palette().textFaint,
                top = 8,
            ),
        )

        // ---- 体力条自动切后台（原神适配，实验室）
        root.addView(sectionLabel("体力条自动切后台（实验室）", top = 26))
        root.addView(
            toggleRow(
                title = "体力耗尽自动切后台",
                description = if (config.staminaAutoSwitchEnabled) {
                    if (OverlayService.isStaminaCaptureRunning()) {
                        "实验室识别运行中 —— 红色达到阈值并确认后进入最近任务界面；黄色恢复后重新待命。"
                    } else {
                        "方案已保存，但当前屏幕捕获未运行；返回本页会请求重新授权。"
                    }
                } else {
                    "已关闭 —— 需要悬浮窗、无障碍和屏幕捕获授权；仅在原神画面中使用。"
                },
                checked = config.staminaAutoSwitchEnabled,
                onToggle = { toggleStaminaAutoSwitch(config) },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            numberSlider(
                title = "红色像素占比阈值",
                value = config.staminaThresholdPercent,
                range = 5..60,
                label = { "$it%" },
                onChanged = { value -> saveConfig { it.copy(staminaThresholdPercent = value) } },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            numberSlider(
                title = "连续确认帧数",
                value = config.staminaConfirmFrames,
                range = 1..5,
                label = { "$it 帧（≈ ${it * 0.25} 秒）" },
                onChanged = { value -> saveConfig { it.copy(staminaConfirmFrames = value) } },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            cardContainer(paddingV = 12).apply {
                addView(
                    LinearLayout(this@GameOptimizerActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(badgeChip("实验功能", palette().accent, palette().cardBackground))
                        addView(
                            labelText(
                                "建议先限定 $gameLabel 并从 25% 阈值开始。若同时开启自动返回，会显示倒计时并返回此游戏。",
                                12f,
                                palette().textSecondary,
                            ),
                            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) },
                        )
                    },
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        return scroll
    }

    private fun toggleAutoReturn(config: GameSpecialConfig) {
        val next = !config.autoReturnEnabled
        if (next) GameSpecialPrefs.setActivePackage(this, gamePackage)
        saveConfig { it.copy(autoReturnEnabled = next) }
        OperationLog.record(
            this,
            if (next) "已开启自动返回游戏" else "已关闭自动返回游戏",
        )
        renderSafely()
    }

    private fun toggleStaminaAutoSwitch(config: GameSpecialConfig) {
        if (config.staminaAutoSwitchEnabled) {
            // 关闭：直接保存，服务会在 reloadConfig 中释放 MediaProjection。
            saveConfig { it.copy(staminaAutoSwitchEnabled = false) }
            OperationLog.record(this, "已关闭体力条自动切后台")
            renderSafely()
            return
        }

        val runtimeStatus = RuntimeProtection.inspect(this)
        if (!runtimeStatus.overlayReady) {
            AlertDialog.Builder(this)
                .setTitle("先开启悬浮窗权限")
                .setMessage("体力耗尽后需要显示悬浮按钮并执行切后台。请先授予悬浮窗权限，再开启实验室功能。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去开启") { _, _ -> RuntimeProtection.openOverlaySettings(this) }
                .show()
            return
        }
        if (!runtimeStatus.accessibilityReady) {
            AlertDialog.Builder(this)
                .setTitle("先开启无障碍服务")
                .setMessage("体力耗尽后由无障碍服务执行切后台。请先开启该服务，再申请屏幕捕获授权。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去开启") { _, _ -> RuntimeProtection.openAccessibilitySettings(this) }
                .show()
            return
        }

        // 开启：先二次确认，再请求 MediaProjection 授权。
        AlertDialog.Builder(this)
            .setTitle("实验室功能")
            .setMessage(
                    "「体力条自动切后台」目前还在实验阶段，会根据角色附近体力条的黄/红颜色判断。" +
                    "不同分辨率、画面效果和场景可能导致误触发、漏触发或额外耗电。\n\n" +
                    "确定要继续开启吗？",
            )
            .setPositiveButton("仍要开启") { _, _ ->
                OperationLog.record(this, "用户确认开启实验室功能：体力条自动切后台")
                requestMediaProjectionConsent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveConfig(transform: (GameSpecialConfig) -> GameSpecialConfig) {
        val updated = transform(GameSpecialPrefs.load(this, gamePackage))
        GameSpecialPrefs.save(this, gamePackage, updated)
        OverlayService.reloadConfig()
    }

    private fun toggleActiveProfile(isActive: Boolean, config: GameSpecialConfig) {
        val nextPackage = if (isActive) null else gamePackage
        GameSpecialPrefs.setActivePackage(this, nextPackage)
        OverlayService.reloadConfig()
        OperationLog.record(
            this,
            if (nextPackage == null) "已停用游戏特调" else "已切换生效游戏特调",
            nextPackage ?: gamePackage,
        )
        if (nextPackage != null && config.staminaAutoSwitchEnabled &&
            !OverlayService.isStaminaCaptureRunning()
        ) {
            requestMediaProjectionConsent()
        } else {
            renderSafely()
        }
    }

    private fun maybeRequestSavedLabAuthorization() {
        if (promptedForMissingProjection || !OverlayPrefs.load(this).enabled ||
            !GameSpecialPrefs.isActive(this, gamePackage)
        ) return
        val config = GameSpecialPrefs.load(this, gamePackage)
        if (config.staminaAutoSwitchEnabled && !OverlayService.isStaminaCaptureRunning()) {
            requestMediaProjectionConsent()
        }
    }

    private fun requestMediaProjectionConsent() {
        promptedForMissingProjection = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }
}
