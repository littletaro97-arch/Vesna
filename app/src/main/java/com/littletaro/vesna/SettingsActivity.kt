package com.littletaro.vesna

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.littletaro.vesna.core.ForegroundAppTracker
import com.littletaro.vesna.core.OperationLog
import com.littletaro.vesna.core.OverlayConfig
import com.littletaro.vesna.core.OverlayPrefs
import com.littletaro.vesna.core.RecentsVisibility
import com.littletaro.vesna.core.RuntimeProtection
import com.littletaro.vesna.core.RuntimeStatus
import com.littletaro.vesna.core.ThemeColors
import com.littletaro.vesna.overlay.OverlayService
import com.littletaro.vesna.ui.actionButton
import com.littletaro.vesna.ui.cardContainer
import com.littletaro.vesna.ui.dp
import com.littletaro.vesna.ui.labelText
import com.littletaro.vesna.ui.palette
import com.littletaro.vesna.ui.replaceContentPreservingScroll
import com.littletaro.vesna.ui.screenRoot
import com.littletaro.vesna.ui.screenScroll
import com.littletaro.vesna.ui.sectionLabel
import com.littletaro.vesna.ui.statusRow
import com.littletaro.vesna.ui.toggleRow
import com.littletaro.vesna.update.UpdateController

/**
 * 设置页：运行条件体检 + 悬浮按钮外观 + 显示范围 + 应用更新 + 运行记录。
 *
 * 所有改动都即时落盘并通知运行中的悬浮服务热更新，不需要用户重启开关。
 */
class SettingsActivity : Activity() {

    private lateinit var updateController: UpdateController
    private var updateStatusText = ""

    private val nightAtCreate by lazy { ThemeColors.isNight(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeColors.applySystemBars(this)
        updateController = UpdateController(
            activity = this,
            onStatus = { status ->
                updateStatusText = status
                renderSafely()
            },
        )
        renderSafely()
    }

    override fun onStart() {
        super.onStart()
        updateController.attach()
    }

    override fun onStop() {
        updateController.detach()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderSafely()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemeColors.isNightConfig(newConfig) != nightAtCreate) recreate()
    }

    private fun renderSafely() {
        if (isFinishing || isDestroyed) return
        replaceContentPreservingScroll { buildScreen() }
    }

    // ------------------------------------------------------------ 页面

    private fun buildScreen(): View {
        val status = RuntimeProtection.inspect(this)
        val config = OverlayPrefs.load(this)

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
        root.addView(labelText("设置", 24f, palette().textPrimary))

        // ---- 运行条件
        root.addView(sectionLabel("运行条件", top = 24))
        root.addView(
            statusRow(
                "悬浮窗权限",
                RuntimeProtection.overlayLabel(status),
                stateColor(status.overlayReady),
                "›",
            ) { RuntimeProtection.openOverlaySettings(this) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            statusRow(
                "无障碍服务",
                RuntimeProtection.accessibilityLabel(status),
                stateColor(status.accessibilityReady),
                "›",
            ) { RuntimeProtection.openAccessibilitySettings(this) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        root.addView(
            statusRow(
                "通知权限",
                RuntimeProtection.notificationLabel(status),
                stateColor(status.notificationReady),
                "›",
            ) { RuntimeProtection.openNotificationSettings(this) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        root.addView(
            statusRow(
                "电池优化",
                RuntimeProtection.batteryLabel(status),
                stateColor(status.batteryReady),
                "›",
            ) { RuntimeProtection.openBatterySettings(this) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        root.addView(
            statusRow(
                "使用情况访问",
                if (status.usageAccessGranted) "已授权" else "未授权",
                stateColor(status.usageAccessGranted),
                "›",
            ) { ForegroundAppTracker.openUsageAccessSettings(this) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        root.addView(
            labelText(
                "前两项缺一不可。后三项只影响稳定性：通知被禁会降低前台服务存活率，" +
                    "电池优化未加白名单会让系统更容易在后台回收服务。",
                12f,
                palette().textFaint,
                top = 10,
            ),
        )
        root.addView(
            labelText(
                "Vesna 不会出现在最近任务列表里 —— 这样清理后台时不会把它连服务一起清掉。" +
                    "要回到本页，走通知栏的常驻通知或桌面图标。",
                12f,
                palette().textFaint,
                top = 8,
            ),
        )

        // 「从最近任务隐藏」：默认开启。用户看不见自己被隐藏了，所以给一行状态说明，
        // 免得以为没生效 —— 这个功能的效果恰恰就是「你找不到它」。
        val hiddenFromRecents = config.hideFromRecents
        root.addView(
            toggleRow(
                title = "从最近任务隐藏",
                description = if (hiddenFromRecents) {
                    "已开启 —— 清理后台时不会把 Vesna 连服务一起清掉。" +
                        "要回到本页，走通知栏的常驻通知或桌面图标。"
                } else {
                    "已关闭 —— Vesna 会出现在最近任务里，随时可以从那里切回来。" +
                        "一键清理后台时可能被连服务一起清掉。"
                },
                checked = hiddenFromRecents,
                onToggle = { toggleHideFromRecents(config) },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )

        // ---- 悬浮按钮外观
        root.addView(sectionLabel("悬浮按钮外观", top = 26))
        root.addView(
            numberSlider(
                title = "大小",
                value = config.sizeDp,
                range = OverlayConfig.MIN_SIZE_DP..OverlayConfig.MAX_SIZE_DP,
                label = { "$it dp" },
                onChanged = { size -> saveConfig { it.copy(sizeDp = size) } },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            numberSlider(
                title = "不透明度",
                value = config.alphaPercent,
                range = OverlayConfig.MIN_ALPHA_PERCENT..100,
                label = { "$it%" },
                onChanged = { alpha -> saveConfig { it.copy(alphaPercent = alpha) } },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        root.addView(
            labelText(
                "位置直接拖动悬浮按钮即可调整，会自动记住。",
                12f,
                palette().textFaint,
                top = 10,
            ),
        )

        // ---- 显示范围
        root.addView(sectionLabel("显示范围", top = 26))
        root.addView(
            restrictCard(config, status),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        // ---- 应用更新
        root.addView(sectionLabel("应用更新", top = 26))
        root.addView(
            updateCard(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        // ---- 运行记录
        root.addView(sectionLabel("运行记录", top = 26))
        root.addView(
            logCard(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        return scroll
    }

    private fun stateColor(ok: Boolean): Int =
        if (ok) palette().statusGreen else palette().statusRed

    // ------------------------------------------------------------ 最近任务可见性

    private fun toggleHideFromRecents(config: OverlayConfig) {
        val next = !config.hideFromRecents
        saveConfig { it.copy(hideFromRecents = next) }
        OperationLog.record(
            this,
            if (next) "已开启「从最近任务隐藏」" else "已关闭「从最近任务隐藏」",
        )
        // 立刻对当前任务生效，不用等下次启动。
        RecentsVisibility.apply(this, next)
        Toast.makeText(
            this,
            if (next) "已隐藏：最近任务里看不到 Vesna" else "已显示：最近任务里可以看到 Vesna",
            Toast.LENGTH_SHORT,
        ).show()
        renderSafely()
    }

    // ------------------------------------------------------------ 控件

    private fun saveConfig(transform: (OverlayConfig) -> OverlayConfig) {
        val updated = transform(OverlayPrefs.load(this))
        OverlayPrefs.save(this, updated)
        OverlayService.reloadConfig()
    }

    /** 数字滑块：拖动时实时写入配置并热更新悬浮按钮。 */
    private fun numberSlider(
        title: String,
        value: Int,
        range: IntRange,
        label: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): View {
        val valueView = labelText(label(value), 12f, palette().textSecondary, top = 4)
        return cardContainer().apply {
            addView(labelText(title, 15f, palette().textPrimary))
            addView(valueView)
            addView(
                SeekBar(this@SettingsActivity).apply {
                    max = range.last - range.first
                    progress = (value - range.first).coerceIn(0, max)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar?,
                                progressValue: Int,
                                fromUser: Boolean,
                            ) {
                                if (!fromUser) return
                                val actual = range.first + progressValue
                                valueView.text = label(actual)
                                onChanged(actual)
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
        }
    }

    private fun restrictCard(config: OverlayConfig, status: RuntimeStatus): View =
        cardContainer().apply {
            val toggleRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { toggleRestrict(config, status) }
            }
            toggleRow.addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(labelText("只在指定应用里显示", 15f, palette().textPrimary))
                    addView(
                        labelText(
                            "关掉就一直显示在所有界面",
                            12f,
                            palette().textSecondary,
                            top = 4,
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            toggleRow.addView(
                CheckBox(this@SettingsActivity).apply {
                    isChecked = config.restrictToApps
                    isClickable = false
                },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
            )
            addView(toggleRow, LinearLayout.LayoutParams(-1, -2))

            if (config.restrictToApps) {
                addView(
                    labelText(
                        if (status.usageAccessGranted) {
                            "已选 ${config.targetPackages.size} 个应用"
                        } else {
                            "需要先授予「使用情况访问」权限才能识别前台应用"
                        },
                        12f,
                        if (status.usageAccessGranted) {
                            palette().textSecondary
                        } else {
                            palette().statusYellow
                        },
                        top = 10,
                    ),
                )
                addView(
                    actionButton("选择应用", primary = false).apply {
                        setOnClickListener {
                            if (!status.usageAccessGranted) {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "请先授予「使用情况访问」权限",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                ForegroundAppTracker.openUsageAccessSettings(this@SettingsActivity)
                                return@setOnClickListener
                            }
                            startActivity(Intent(this@SettingsActivity, AppPickerActivity::class.java))
                        }
                    },
                    LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) },
                )
            }
        }

    private fun toggleRestrict(
        config: OverlayConfig,
        status: RuntimeStatus,
    ) {
        val next = !config.restrictToApps
        if (next && !status.usageAccessGranted) {
            AlertDialog.Builder(this)
                .setTitle("需要「使用情况访问」权限")
                .setMessage("识别前台应用需要这个权限。Vesna 只读取「最近进入前台的是哪个应用」，不读取任何内容。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去授权") { _, _ ->
                    ForegroundAppTracker.openUsageAccessSettings(this)
                }
                .show()
            return
        }
        saveConfig { it.copy(restrictToApps = next) }
        OperationLog.record(
            this,
            if (next) "开启「仅在指定应用显示」" else "关闭「仅在指定应用显示」",
        )
        renderSafely()
    }

    private fun updateCard(): View = cardContainer().apply {
        addView(
            labelText(
                "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                15f,
                palette().textPrimary,
            ),
        )

        val autoRow = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                val next = !updateController.updatesEnabled()
                updateController.setUpdatesEnabled(next)
                renderSafely()
            }
        }
        autoRow.addView(
            labelText("启动时自动检查更新", 14f, palette().textPrimary).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        autoRow.addView(
            CheckBox(this@SettingsActivity).apply {
                isChecked = updateController.updatesEnabled()
                isClickable = false
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        addView(autoRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        addView(
            actionButton("立即检查更新", primary = false).apply {
                setOnClickListener { updateController.checkNow() }
            },
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) },
        )

        // 状态就近显示在按钮下方。之前统一甩在整页最底部，点完根本看不到结果，
        // 看起来就像「点了没反应」。
        if (updateStatusText.isNotBlank()) {
            addView(labelText(updateStatusText, 12f, palette().textSecondary, top = 10))
        }
    }

    private fun logCard(): View = cardContainer().apply {
        addView(
            labelText(
                "只记录「什么时候发生了什么」，不含任何屏幕内容或个人数据。",
                12f,
                palette().textSecondary,
            ),
        )
        addView(
            actionButton("查看与导出运行记录", primary = false).apply {
                setOnClickListener { showLogDialog() }
            },
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) },
        )
        addView(
            actionButton("清空运行记录", primary = false).apply {
                setOnClickListener {
                    OperationLog.clear(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) },
        )
    }

    private fun showLogDialog() {
        val text = RuntimeProtection.diagnosticText(this)
        val scroll = android.widget.ScrollView(this).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(
                labelText(text, 12f, palette().textSecondary).apply {
                    setTextIsSelectable(true)
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle("运行记录")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }
}
