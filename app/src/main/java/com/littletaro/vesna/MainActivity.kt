package com.littletaro.vesna

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.littletaro.vesna.a11y.BackgroundAccessibilityService
import com.littletaro.vesna.core.OperationLog
import com.littletaro.vesna.core.OverlayPrefs
import com.littletaro.vesna.core.RecentsVisibility
import com.littletaro.vesna.core.RuntimeProtection
import com.littletaro.vesna.core.ThemeColors
import com.littletaro.vesna.overlay.OverlayService
import com.littletaro.vesna.ui.actionButton
import com.littletaro.vesna.ui.badgeChip
import com.littletaro.vesna.ui.capsuleButton
import com.littletaro.vesna.ui.dp
import com.littletaro.vesna.ui.labelText
import com.littletaro.vesna.ui.palette
import com.littletaro.vesna.ui.replaceContentPreservingScroll
import com.littletaro.vesna.ui.roundedBackground
import com.littletaro.vesna.ui.screenRoot
import com.littletaro.vesna.ui.screenScroll
import com.littletaro.vesna.ui.sectionLabel
import com.littletaro.vesna.ui.statusRow
import com.littletaro.vesna.update.UpdateController

/**
 * 主页：一个开关、两项必需权限、一个设置入口。
 *
 * 刻意保持极简 —— 这个工具的核心诉求就是「玩的时候点一下」，主页不该比按钮本身复杂。
 * 其余配置（尺寸、透明度、只在哪个应用里出现、更新）全部收进设置页。
 */
class MainActivity : Activity() {

    private lateinit var updateController: UpdateController
    private var updateStatusText = ""
    private var pendingStart = false
    private var accessibilityGuideShown = false

    private val nightAtCreate by lazy { ThemeColors.isNight(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeColors.applySystemBars(this)
        applyTaskVisibility()
        updateController = UpdateController(
            activity = this,
            onStatus = { status ->
                updateStatusText = status
                renderSafely()
            },
        )
        OperationLog.record(this, "应用启动")
        window.decorView.post { updateController.checkOnLaunch() }
    }

    /**
     * 按用户设置决定自己要不要出现在「最近任务」里。
     *
     * manifest 里的 `android:excludeFromRecents="true"` 只是默认值（默认隐藏），
     * 这里再用 [RecentsVisibility] 在运行时覆盖它 —— 设置页那个开关因此是即时生效的，
     * 不需要重装或改清单。
     *
     * 放在 onCreate 里尽早调用：任务刚建立时设置最可靠，晚了用户可能已经在最近任务里看见它了。
     */
    private fun applyTaskVisibility() {
        val hide = OverlayPrefs.load(this).hideFromRecents
        if (!RecentsVisibility.apply(this, hide)) {
            OperationLog.record(this, "本次未能设置最近任务可见性", "任务尚未建立，沿用清单默认值")
        }
    }

    override fun onStart() {
        super.onStart()
        updateController.attach()
        OverlayService.addRunningObserver(onServiceRunningChanged)
    }

    override fun onStop() {
        OverlayService.removeRunningObserver(onServiceRunningChanged)
        updateController.detach()
        super.onStop()
    }

    /**
     * 悬浮服务真正起来/停下时回调。
     *
     * 启动是异步的：点下开关那一刻服务还没创建完，直接去问 isRunning() 只会得到
     * 「未开启」，界面就卡在错误状态，直到用户切走再回来才刷新。让服务自己回报状态，
     * 才能做到点完即变。
     */
    private val onServiceRunningChanged: () -> Unit = { renderSafely() }

    override fun onResume() {
        super.onResume()
        updateController.resumePendingInstallIfAllowed()
        if (pendingStart) {
            val status = RuntimeProtection.inspect(this)
            when {
                status.coreReady -> {
                    pendingStart = false
                    reallyStartOverlay()
                }

                // 悬浮窗刚授权回来，接着把无障碍这一步走完，不让用户自己回来点第二次。
                status.overlayReady && !status.accessibilityReady && !accessibilityGuideShown -> {
                    accessibilityGuideShown = true
                    showAccessibilityGuide()
                }
            }
        }
        renderSafely()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemeColors.isNightConfig(newConfig) != nightAtCreate) recreate()
    }

    // ------------------------------------------------------------ 渲染

    private fun renderSafely() {
        if (isFinishing || isDestroyed) return
        replaceContentPreservingScroll { buildScreen() }
    }

    private fun buildScreen(): View {
        val status = RuntimeProtection.inspect(this)
        val scroll = screenScroll()
        val root = screenRoot()
        scroll.addView(root, LinearLayout.LayoutParams(-1, -2))

        root.addView(labelText("Vesna, keep flying", 26f, palette().textPrimary))
        root.addView(
            labelText("把正在玩的游戏一键切到后台", 14f, palette().textSecondary, top = 6),
        )

        root.addView(
            switchCard(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) },
        )

        root.addView(sectionLabel("运行条件", top = 26))
        root.addView(
            statusRow(
                title = "悬浮窗权限",
                value = RuntimeProtection.overlayLabel(status),
                valueColor = if (status.overlayReady) {
                    palette().statusGreen
                } else {
                    palette().statusRed
                },
                actionHint = "›",
                onClick = { RuntimeProtection.openOverlaySettings(this) },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            statusRow(
                title = "无障碍服务",
                value = RuntimeProtection.accessibilityLabel(status),
                valueColor = if (status.accessibilityReady) {
                    palette().statusGreen
                } else {
                    palette().statusRed
                },
                actionHint = "›",
                onClick = { showAccessibilityGuide() },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )

        // 系统开关是开的、但服务进程被厂商系统回收了 —— 这时按钮点了没反应，
        // 必须显式提示，否则用户会以为工具坏了。
        if (status.accessibilityReady && !BackgroundAccessibilityService.isConnected()) {
            root.addView(
                noticeBox(
                    "无障碍服务当前被系统暂停了（部分厂商会定期回收）。" +
                        "若点按钮没反应，请到系统设置里把 Vesna 的无障碍关掉再打开一次。",
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }

        if (!status.coreReady) {
            root.addView(
                noticeBox(
                    "悬浮窗权限 + 无障碍服务缺一不可：前者让按钮能显示在游戏上，" +
                        "后者才是真正执行「切到后台」的那一环。",
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }

        root.addView(sectionLabel("更多", top = 26))
        root.addView(
            entryCard(
                title = "设置",
                description = "按钮大小与透明度、显示范围、应用更新与运行记录",
            ) { open(Intent(this, SettingsActivity::class.java)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        root.addView(sectionLabel("游戏特调", top = 26))
        root.addView(
            gameTuneCard(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        root.addView(sectionLabel("问题反馈", top = 26))
        root.addView(
            feedbackCard(
                iconRes = R.drawable.ic_xiaohongshu,
                title = "小红书",
                subtitle = "小红书号：5067916575",
                extra = "昵称：小芋头不会取名",
                url = "https://www.xiaohongshu.com/user/profile/63cfb2e10000000027028d15",
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        root.addView(
            feedbackCard(
                iconRes = R.drawable.ic_bilibili,
                title = "bilibili",
                subtitle = "UID：3546980829629370",
                extra = "昵称：小芋头不会取名小号",
                url = "https://space.bilibili.com/3546980829629370",
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )

        if (updateStatusText.isNotBlank()) {
            root.addView(
                labelText(updateStatusText, 12f, palette().textFaint, top = 14),
            )
        }

        return scroll
    }

    /**
     * 主开关卡片。「运行中」的判定同时看两件事：用户是否开着（持久化偏好），
     * 以及服务是否真的活着 —— 只看其中一个都会骗人。
     */
    private fun switchCard(): View {
        val config = OverlayPrefs.load(this)
        val running = config.enabled && OverlayService.isRunning()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(palette().cardBackground, palette().cardStroke)

            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(
                labelText("悬浮按钮", 17f, palette().textPrimary).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            titleRow.addView(
                badgeChip(
                    if (running) "运行中" else "未开启",
                    if (running) palette().green else palette().textFaint,
                    if (running) palette().greenBadgeBg else palette().subtleBoxBg,
                ),
                LinearLayout.LayoutParams(-2, -2),
            )
            addView(titleRow, LinearLayout.LayoutParams(-1, -2))

            addView(
                labelText(
                    if (running) {
                        "按钮已挂在屏幕上。游戏里点一下即退到后台，长按可拖动改位置。"
                    } else {
                        "开启后会在屏幕边缘显示一个圆形按钮，点一下把当前应用切到后台。"
                    },
                    12f,
                    palette().textSecondary,
                    top = 8,
                ),
            )

            addView(
                actionButton(
                    if (running) "关闭悬浮按钮" else "开启悬浮按钮",
                    primary = !running,
                ).apply {
                    setOnClickListener { onSwitchTapped() }
                },
                LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) },
            )
        }
    }

    private fun entryCard(title: String, description: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(palette().cardBackground, palette().cardStroke)
            isClickable = true
            setOnClickListener { onClick() }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(labelText(title, 16f, palette().textPrimary))
                    addView(labelText(description, 12f, palette().textSecondary, top = 4))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                labelText("›", 18f, palette().textFaint).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) },
            )
        }

    private fun noticeBox(text: String): View =
        labelText(text, 12f, palette().textSecondary).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(palette().noticeBg, palette().noticeStroke)
        }

    // ------------------------------------------------------------ 行为

    private fun onSwitchTapped() {
        val config = OverlayPrefs.load(this)
        if (config.enabled && OverlayService.isRunning()) {
            stopOverlay()
            return
        }
        val status = RuntimeProtection.inspect(this)
        when {
            !status.overlayReady -> {
                pendingStart = true
                showOverlayGuide()
            }

            !status.accessibilityReady -> {
                pendingStart = true
                showAccessibilityGuide()
            }

            else -> reallyStartOverlay()
        }
    }

    private fun reallyStartOverlay() {
        OverlayPrefs.setEnabled(this, true)
        // minSdk 26 起 startForegroundService 可用，无需再分支。
        startForegroundService(Intent(this, OverlayService::class.java))
        OperationLog.record(this, "用户开启悬浮按钮")
        renderSafely()
    }

    private fun stopOverlay() {
        OverlayService.markUserStop()
        OverlayPrefs.setEnabled(this, false)
        stopService(Intent(this, OverlayService::class.java))
        OperationLog.record(this, "用户关闭悬浮按钮")
        renderSafely()
    }

    private fun showOverlayGuide() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage(
                "Vesna 需要「显示在其他应用上层」权限，才能把按钮画在游戏画面上。\n\n" +
                    "在接下来的设置页里打开开关，返回后会自动继续。",
            )
            .setNegativeButton("稍后") { _, _ -> pendingStart = false }
            .setPositiveButton("去设置") { _, _ ->
                RuntimeProtection.openOverlaySettings(this)
            }
            .show()
    }

    private fun showAccessibilityGuide() {
        AlertDialog.Builder(this)
            .setTitle("还需要开启无障碍服务")
            .setMessage(
                "真正执行「切到后台」的是系统无障碍接口，这一步不能跳过。\n\n" +
                    "Vesna 只调用 GLOBAL_ACTION_RECENTS 这一个系统动作：不读取屏幕内容，" +
                    "不模拟触摸，不记录你的任何操作。在无障碍列表里找到「Vesna」并开启即可。",
            )
            .setNegativeButton("稍后") { _, _ -> pendingStart = false }
            .setPositiveButton("去开启") { _, _ ->
                RuntimeProtection.openAccessibilitySettings(this)
            }
            .show()
    }

    // ---- 游戏特调卡片：左（原神图标）中（标题）右（进入特调 + 一键启动 横排）
    private fun gameTuneCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedBackground(palette().cardBackground, palette().cardStroke)

        // 左侧：原神图标
        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_genshin)
                background = roundedBackground(palette().subtleBoxBg, palette().subtleBoxStroke, cornerDp = 12)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) },
        )

        // 中间：标题
        addView(
            labelText("原神", 17f, palette().textPrimary).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )

        // 右侧：进入特调 + 一键启动，两个按钮横向并排
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    capsuleButton("进入特调", accent = true).apply {
                        setOnClickListener {
                            open(Intent(this@MainActivity, GameOptimizerActivity::class.java))
                        }
                    },
                    LinearLayout.LayoutParams(-2, -2),
                )
                addView(
                    capsuleButton("一键启动", accent = false).apply {
                        setOnClickListener { launchGenshin() }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
                )
            },
            LinearLayout.LayoutParams(-2, -1).apply { leftMargin = dp(10) },
        )
    }

    private fun launchGenshin() {
        val candidates = listOf(
            "com.miHoYo.Yuanshen",
            "com.miHoYo.GenshinImpact",
            "com.miHoYo.ys.mihoyo",
        )
        for (pkg in candidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                OperationLog.record(this, "一键启动原神", pkg)
                startActivity(intent)
                return
            }
        }
        OperationLog.record(this, "一键启动失败", "原神未安装")
        Toast.makeText(this, "未检测到原神，请确认已安装", Toast.LENGTH_SHORT).show()
    }

    // ---- 问题反馈卡片
    private fun feedbackCard(
        iconRes: Int,
        title: String,
        subtitle: String,
        extra: String,
        url: String,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedBackground(palette().cardBackground, palette().cardStroke)
        isClickable = true
        setOnClickListener { openSocial(url) }

        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                background = roundedBackground(palette().subtleBoxBg, palette().subtleBoxStroke, cornerDp = 10)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) },
        )

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelText(title, 16f, palette().textPrimary))
                addView(labelText(subtitle, 13f, palette().textSecondary, top = 3))
                addView(labelText(extra, 13f, palette().textSecondary, top = 1))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )

        addView(
            capsuleButton("点击跳转", accent = true),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) },
        )
    }

    private fun openSocial(url: String) {
        // Let Android route the profile URL to its verified app link or a browser.
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                OperationLog.record(this, "打开链接失败", url)
                Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show()
            }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { OperationLog.record(this, "打开页面失败", intent.component?.className) }
    }
}
