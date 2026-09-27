package com.littletaro.vesna.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.littletaro.vesna.MainActivity
import com.littletaro.vesna.R
import com.littletaro.vesna.a11y.BackgroundAccessibilityService
import com.littletaro.vesna.a11y.SendToBackgroundOutcome
import com.littletaro.vesna.core.ForegroundAppTracker
import com.littletaro.vesna.core.GameSpecialConfig
import com.littletaro.vesna.core.GameSpecialPrefs
import com.littletaro.vesna.core.OperationLog
import com.littletaro.vesna.core.OverlayConfig
import com.littletaro.vesna.core.OverlayPrefs
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * 常驻前台服务：负责把悬浮按钮挂到游戏画面之上，并把点击转成一次「切到后台」。
 *
 * 生命周期与用户手动开关绑定（[com.littletaro.vesna.core.OverlayPrefs] 的 enabled 字段）：
 * 服务不在任何情况下自行开启；系统回收后用 START_STICKY 拉起时会重新读配置决定去留。
 *
 * 关于「仅在指定应用显示」：该模式用 1.5 秒一次的轻量轮询判断前台应用。
 * 轮询本身不读取任何内容，只问系统「最近进入前台的是哪个包名」。
 *
 * 关于「原神体力条自动切后台」：需要用户在游戏优化页授权 MediaProjection 后，
 * 服务会创建 [ImageReader] + [VirtualDisplay]，每 250ms 在角色附近的中央搜索区分析黄/红体力条；
 * 红色像素比例达到阈值并连续确认后，调用 [onButtonTriggered] 自动切后台。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var buttonView: ToggleButtonView? = null
    private var config = OverlayConfig()
    private var activeGamePackage: String? = null

    private var dragStartX = 0
    private var dragStartY = 0

    private var pollingForeground = false
    private var lastForegroundPackage: String? = null

    /** 自动返回游戏的倒计时任务。null 表示当前没有在等返回。 */
    private var autoReturnRunnable: Runnable? = null
    private var autoReturnCountdownRunnable: Runnable? = null
    private var autoReturnPackage: String? = null

    // ---------------- MediaProjection 截屏（体力条识别）
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaProjectionGamePackage: String? = null
    private var staminaConsecutiveLowFrames = 0
    private var staminaConsecutiveNormalFrames = 0
    private var staminaLowTriggered = false

    private val foregroundPoll = object : Runnable {
        override fun run() {
            refreshVisibilityByForeground()
            if (pollingForeground) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val staminaAnalyzer = Runnable { analyzeStaminaFrame() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        OperationLog.record(this, "悬浮服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userStopRequested = false
        config = loadRuntimeConfig()

        val overlayGranted = runCatching { Settings.canDrawOverlays(this) }
            .onFailure { OperationLog.record(this, "悬浮窗权限检查异常", it.javaClass.simpleName) }
            .getOrDefault(false)
        if (!overlayGranted) {
            OperationLog.record(this, "启动中止：缺少悬浮窗权限")
            stopSelf()
            return START_NOT_STICKY
        }

        // MediaProjection 的用户授权会随 Intent 带过来。Android 14+ 要求先以
        // mediaProjection 类型进入前台，再调用 getMediaProjection()。
        val projectionIntent = intent
            ?.getParcelableExtraCompat<Intent>(EXTRA_MEDIA_PROJECTION_DATA)
            ?.takeIf { config.staminaAutoSwitchEnabled }

        // START_STICKY 重建服务时不会恢复旧的 MediaProjection 授权；重新授权前保持功能关闭。
        if (config.staminaAutoSwitchEnabled && projectionIntent == null && mediaProjection == null) {
            config = config.copy(staminaAutoSwitchEnabled = false)
            OperationLog.record(this, "屏幕捕获授权未恢复，体力识别保持关闭")
        }

        return try {
            startAsForeground(includeMediaProjection = projectionIntent != null || mediaProjection != null)
            projectionIntent?.let(::createMediaProjection)
            attachButton()
            restartForegroundPolling()
            OperationLog.record(
                this,
                "悬浮服务已启动",
                "尺寸=${config.sizeDp}dp 不透明度=${config.alphaPercent}%",
            )
            // 到这里按钮才真的挂到屏幕上 —— 界面此刻才该显示「运行中」。
            // 注意 instance 不能更早赋值，否则启动失败时界面会短暂显示成已开启。
            instance = this
            notifyRunningChanged(this)
            START_STICKY
        } catch (error: Exception) {
            OperationLog.record(this, "悬浮按钮创建失败，服务退出", error.javaClass.simpleName)
            removeButton()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = buttonView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        // 记录方向变化前的绝对像素位置，重建按钮后恢复，避免游戏↔最近任务时按钮跳位。
        val previousX = params.x
        val previousY = params.y
        OperationLog.record(this, "屏幕配置变化，重建悬浮按钮")
        config = loadRuntimeConfig()
        attachButton()
        // attachButton 会按当前方向比例落点；这里把位置恢复成变化前的绝对坐标，防止跳变。
        buttonView?.let { newView ->
            val newParams = newView.layoutParams as? WindowManager.LayoutParams ?: return@let
            val screen = screenSize()
            newParams.x = previousX.coerceIn(0, (screen.x - newParams.width).coerceAtLeast(0))
            newParams.y = previousY.coerceIn(0, (screen.y - newParams.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(newView, newParams) }
                .onFailure { OperationLog.record(this, "恢复按钮位置失败", it.javaClass.simpleName) }
        }
    }

    override fun onDestroy() {
        cancelAutoReturn()
        stopForegroundPolling()
        removeButton()
        releaseMediaProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (instance === this) {
            instance = null
            notifyRunningChanged(this)
        }
        OperationLog.record(
            this,
            if (userStopRequested) "悬浮服务已停止（用户操作）" else "悬浮服务已销毁（系统回收或异常）",
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 窗口

    private fun attachButton() {
        removeButton()
        val view = ToggleButtonView(
            context = this,
            onTrigger = { onButtonTriggered() },
            onDragStart = { onDragStarted() },
            onDrag = { dx, dy -> onDragged(dx, dy) },
            onDragEnd = { onDragFinished() },
        )
        view.alpha = config.alpha
        windowManager.addView(view, createLayoutParams())
        buttonView = view
        applyGeometry()
        refreshVisibilityByForeground()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val size = dp(config.sizeDp)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Vesna 悬浮按钮"
        }
    }

    /** 按当前配置重算尺寸与落点。拖动结束、屏幕旋转、设置页保存后都会调用。 */
    private fun applyGeometry() {
        val view = buttonView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val screen = screenSize()

        val size = dp(config.sizeDp)
        params.width = size
        params.height = size

        val maxX = (screen.x - size).coerceAtLeast(0)
        val maxY = (screen.y - size).coerceAtLeast(0)
        params.x = (config.xRatio * screen.x - size / 2f).roundToInt().coerceIn(0, maxX)
        params.y = (config.yRatio * screen.y - size / 2f).roundToInt().coerceIn(0, maxY)

        view.alpha = config.alpha
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { OperationLog.record(this, "更新悬浮按钮布局失败", it.javaClass.simpleName) }
    }

    private fun removeButton() {
        buttonView?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { /* 已被系统移除，忽略 */ }
        }
        buttonView = null
    }

    // ---------------------------------------------------------------- 交互

    private fun onButtonTriggered(
        autoReturnTargetPackage: String? = null,
        allowAutoReturn: Boolean = true,
    ) {
        if (!allowAutoReturn) {
            // 此次触发不启用自动返回时，清除已经排队的返回任务。
            cancelAutoReturn()
        }
        val targetPackage = if (allowAutoReturn && config.autoReturnEnabled) {
            autoReturnTargetPackage ?: ForegroundAppTracker.currentForegroundPackage(this)
        } else {
            null
        }

        when (BackgroundAccessibilityService.sendCurrentAppToBackground()) {
            SendToBackgroundOutcome.SUCCESS -> {
                OperationLog.record(this, "已把当前应用切到后台")
                if (allowAutoReturn && config.autoReturnEnabled) {
                    scheduleAutoReturn(targetPackage, config.autoReturnDelayMs)
                }
            }

            SendToBackgroundOutcome.NOT_CONNECTED -> {
                OperationLog.record(this, "切换失败：无障碍服务未连接")
                notifyUser("无障碍服务未连接，请回到 Vesna 重新开启")
            }

            SendToBackgroundOutcome.REJECTED -> {
                OperationLog.record(this, "切换失败：系统拒绝了本次动作")
                notifyUser("系统没有响应切后台动作，可稍后再试")
            }
        }
    }

    /**
     * 切到后台成功后，按配置延迟拉回目标应用。手动操作依赖 UsageStats，
     * 体力触发则明确使用当前生效方案的游戏包名。
     */
    private fun scheduleAutoReturn(targetPackage: String?, delayMs: Long) {
        cancelAutoReturn()
        if (targetPackage.isNullOrBlank()) {
            OperationLog.record(this, "未记录到当前应用包名，跳过自动返回")
            return
        }
        if (targetPackage == packageName) {
            OperationLog.record(this, "当前应用是 Vesna 自身，跳过自动返回")
            return
        }

        autoReturnPackage = targetPackage
        val totalSeconds = (delayMs / 1_000L).toInt().coerceAtLeast(1)

        // 启动倒计时显示：每秒刷新一次悬浮按钮上的数字。
        startCountdownDisplay(totalSeconds)

        val runnable = Runnable {
            autoReturnRunnable = null
            autoReturnPackage = null
            cancelCountdownDisplay()
            bringAppToForeground(targetPackage)
        }
        autoReturnRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        refreshVisibilityByForeground()
        OperationLog.record(this, "已安排 ${delayMs / 1000L} 秒后自动返回", targetPackage)
    }

    private fun startCountdownDisplay(totalSeconds: Int) {
        cancelCountdownDisplay()
        val runnable = object : Runnable {
            var remaining = totalSeconds
            override fun run() {
                buttonView?.countdownSeconds = remaining
                if (remaining > 0) {
                    remaining--
                    handler.postDelayed(this, 1_000L)
                } else {
                    autoReturnCountdownRunnable = null
                }
            }
        }
        autoReturnCountdownRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelCountdownDisplay() {
        autoReturnCountdownRunnable?.let { handler.removeCallbacks(it) }
        autoReturnCountdownRunnable = null
        buttonView?.countdownSeconds = -1
    }

    private fun cancelAutoReturn() {
        autoReturnRunnable?.let { handler.removeCallbacks(it) }
        autoReturnRunnable = null
        autoReturnPackage = null
        cancelCountdownDisplay()
    }

    private fun bringAppToForeground(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            OperationLog.record(this, "自动返回失败：找不到启动入口", packageName)
            return
        }
        runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            OperationLog.record(this, "已自动返回游戏", packageName)
        }.onFailure {
            OperationLog.record(this, "自动返回失败", it.javaClass.simpleName)
        }
    }

    private fun onDragStarted() {
        val params = buttonView?.layoutParams as? WindowManager.LayoutParams ?: return
        dragStartX = params.x
        dragStartY = params.y
    }

    private fun onDragged(deltaX: Float, deltaY: Float) {
        val view = buttonView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val screen = screenSize()
        params.x = (dragStartX + deltaX).roundToInt()
            .coerceIn(0, (screen.x - params.width).coerceAtLeast(0))
        params.y = (dragStartY + deltaY).roundToInt()
            .coerceIn(0, (screen.y - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { /* 拖动途中窗口被系统移除，忽略 */ }
    }

    private fun onDragFinished() {
        val params = buttonView?.layoutParams as? WindowManager.LayoutParams ?: return
        val screen = screenSize()
        val centerX = params.x + params.width / 2f
        val centerY = params.y + params.height / 2f
        config = config.copy(
            xRatio = (centerX / screen.x).coerceIn(0f, 1f),
            yRatio = (centerY / screen.y).coerceIn(0f, 1f),
        )
        OverlayPrefs.save(this, config)
        OperationLog.record(
            this,
            "悬浮按钮位置已保存",
            "x=${(config.xRatio * 100).roundToInt()}% y=${(config.yRatio * 100).roundToInt()}%",
        )
    }

    private fun notifyUser(message: String) {
        // 已持有悬浮窗权限的应用允许从后台弹提示，这里失败也无所谓。
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- 可见性

    private fun restartForegroundPolling() {
        stopForegroundPolling()
        if (!config.restrictToApps || config.targetPackages.isEmpty()) return
        pollingForeground = true
        lastForegroundPackage = null
        OperationLog.record(this, "开始按前台应用控制显示", "目标应用=${config.targetPackages.size} 个")
        handler.post(foregroundPoll)
    }

    private fun stopForegroundPolling() {
        pollingForeground = false
        lastForegroundPackage = null
        handler.removeCallbacks(foregroundPoll)
    }

    private fun refreshVisibilityByForeground() {
        val view = buttonView ?: return
        if (autoReturnRunnable != null) {
            // 倒计时期间保持悬浮按钮可见，即使目标应用过滤暂时隐藏了它。
            view.visibility = View.VISIBLE
            return
        }
        if (!config.restrictToApps || config.targetPackages.isEmpty()) {
            view.visibility = View.VISIBLE
            return
        }
        val foreground = ForegroundAppTracker.currentForegroundPackage(this)
        if (foreground == lastForegroundPackage) return
        lastForegroundPackage = foreground
        val shouldShow = foreground != null && config.targetPackages.contains(foreground)
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 体力条识别（MediaProjection）

    private fun createMediaProjection(data: Intent) {
        releaseMediaProjection()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(Activity.RESULT_OK, data)
                ?: throw IllegalStateException("MediaProjection 获取失败")
            mediaProjection = projection
            mediaProjectionGamePackage = activeGamePackage

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mediaProjection !== projection) return
                    val ownerPackage = mediaProjectionGamePackage
                    releaseMediaProjection(stopProjection = false)
                    if (!ownerPackage.isNullOrBlank()) {
                        val profile = GameSpecialPrefs.load(this@OverlayService, ownerPackage)
                        GameSpecialPrefs.save(
                            this@OverlayService,
                            ownerPackage,
                            profile.copy(staminaAutoSwitchEnabled = false),
                        )
                    }
                    config = loadRuntimeConfig()
                    updateProjectionForegroundType(false)
                    OperationLog.record(this@OverlayService, "屏幕捕获已停止，体力识别已关闭")
                    notifyRunningChanged(this@OverlayService)
                }
            }
            mediaProjectionCallback = callback
            // Android 14+ 要求在创建 VirtualDisplay 前注册停止回调。
            projection.registerCallback(callback, handler)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "VesnaStamina",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            ) ?: throw IllegalStateException("VirtualDisplay 创建失败")

            OperationLog.record(this, "MediaProjection 已启动，开始识别原神体力条")
            staminaConsecutiveLowFrames = 0
            staminaConsecutiveNormalFrames = 0
            staminaLowTriggered = false
            handler.removeCallbacks(staminaAnalyzer)
            handler.post(staminaAnalyzer)
        } catch (error: Exception) {
            releaseMediaProjection()
            activeGamePackage?.let { packageName ->
                val profile = GameSpecialPrefs.load(this, packageName)
                GameSpecialPrefs.save(this, packageName, profile.copy(staminaAutoSwitchEnabled = false))
            }
            config = loadRuntimeConfig()
            updateProjectionForegroundType(false)
            OperationLog.record(
                this,
                "屏幕捕获启动失败，体力识别已关闭",
                error.javaClass.simpleName,
            )
        }
    }

    private fun releaseMediaProjection(stopProjection: Boolean = true) {
        handler.removeCallbacks(staminaAnalyzer)
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        mediaProjectionGamePackage = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        if (projection != null) {
            if (callback != null) runCatching { projection.unregisterCallback(callback) }
            if (stopProjection) runCatching { projection.stop() }
        }
        staminaConsecutiveLowFrames = 0
        staminaConsecutiveNormalFrames = 0
        staminaLowTriggered = false
    }

    private fun analyzeStaminaFrame() {
        if (!config.staminaAutoSwitchEnabled || imageReader == null) {
            staminaConsecutiveLowFrames = 0
            return
        }

        // 如果用户限制了「仅在指定应用显示」，则只在那些应用里运行识别，避免误触。
        val foreground = ForegroundAppTracker.currentForegroundPackage(this)
        if (config.restrictToApps && config.targetPackages.isNotEmpty() &&
            (foreground == null || !config.targetPackages.contains(foreground))
        ) {
            staminaConsecutiveLowFrames = 0
            staminaConsecutiveNormalFrames = 0
            scheduleNextStaminaAnalysis()
            return
        }

        val reader = imageReader ?: return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            // 首帧尚未就绪时继续轮询，避免实验性识别永久停止。
            scheduleNextStaminaAnalysis()
            return
        }
        val width = image.width
        val height = image.height

        val roiLeft = (width * STAMINA_ROI_LEFT).roundToInt().coerceIn(0, width)
        val roiTop = (height * STAMINA_ROI_TOP).roundToInt().coerceIn(0, height)
        val roiRight = (width * STAMINA_ROI_RIGHT).roundToInt().coerceIn(roiLeft, width)
        val roiBottom = (height * STAMINA_ROI_BOTTOM).roundToInt().coerceIn(roiTop, height)

        var redCount = 0
        var yellowCount = 0
        var total = 0

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride

        try {
            for (y in roiTop until roiBottom) {
                val rowStart = y * rowStride + roiLeft * pixelStride
                if (rowStart < 0 || rowStart >= buffer.capacity()) continue
                buffer.position(rowStart)
                for (x in roiLeft until roiRight) {
                    if (buffer.remaining() < 4) break
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    buffer.get() // alpha
                    if (isStaminaRed(r, g, b)) {
                        redCount++
                    } else if (isStaminaYellow(r, g, b)) {
                        yellowCount++
                    }
                    total++
                }
            }
        } finally {
            image.close()
        }

        val classifiedPixels = redCount + yellowCount
        val minClassifiedPixels = (total * STAMINA_MIN_CLASSIFIED_RATIO)
            .roundToInt()
            .coerceAtLeast(STAMINA_MIN_CLASSIFIED_PIXELS)
        val redPercent = if (classifiedPixels > 0) redCount * 100 / classifiedPixels else 0
        val threshold = config.staminaThresholdPercent.coerceIn(5, 80)
        val confirmFrames = config.staminaConfirmFrames.coerceAtLeast(1)
        val hasNormalYellow = yellowCount >= minClassifiedPixels && redPercent < threshold

        if (classifiedPixels >= minClassifiedPixels && redPercent >= threshold) {
            staminaConsecutiveNormalFrames = 0
            if (!staminaLowTriggered) {
                staminaConsecutiveLowFrames++
                if (staminaConsecutiveLowFrames >= confirmFrames) {
                    staminaLowTriggered = true
                    staminaConsecutiveLowFrames = 0
                    OperationLog.record(
                        this,
                        "检测到红色体力条（红色占比$redPercent% ≥ 阈值$threshold%），进入最近任务界面",
                        foreground ?: "",
                    )
                    onButtonTriggered(
                        autoReturnTargetPackage = activeGamePackage,
                        allowAutoReturn = config.autoReturnEnabled,
                    )
                } else {
                    OperationLog.record(
                        this,
                        "检测到红色体力条（红色占比$redPercent% ≥ 阈值$threshold%），累计 $staminaConsecutiveLowFrames 帧",
                    )
                }
            } else {
                staminaConsecutiveLowFrames = 0
            }
        } else {
            staminaConsecutiveLowFrames = 0
            if (hasNormalYellow) {
                staminaConsecutiveNormalFrames++
                if (staminaConsecutiveNormalFrames >= confirmFrames) {
                    staminaLowTriggered = false
                    staminaConsecutiveNormalFrames = 0
                }
            } else {
                // Unknown/moved pixels do not re-arm the action; require positive yellow evidence.
                staminaConsecutiveNormalFrames = 0
            }
        }

        scheduleNextStaminaAnalysis()
    }

    private fun scheduleNextStaminaAnalysis() {
        if (config.staminaAutoSwitchEnabled && imageReader != null) {
            handler.postDelayed(staminaAnalyzer, STAMINA_POLL_INTERVAL_MS)
        }
    }

    private fun isStaminaRed(red: Int, green: Int, blue: Int): Boolean =
        red >= 185 && green <= 145 && blue <= 150 && red - green >= 60 && red - blue >= 55

    private fun isStaminaYellow(red: Int, green: Int, blue: Int): Boolean =
        red >= 190 && green >= 130 && blue <= 90 && red - blue >= 100 && green - blue >= 70

    private fun updateProjectionForegroundType(includeMediaProjection: Boolean) {
        runCatching { startAsForeground(includeMediaProjection) }
            .onFailure {
                OperationLog.record(this, "更新前台服务类型失败", it.javaClass.simpleName)
            }
    }

    // ---------------------------------------------------------------- 前台服务

    private fun startAsForeground(includeMediaProjection: Boolean = false) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Vesna 悬浮按钮运行中")
            .setContentText("点一下把当前游戏切到后台")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val foregroundTypes =
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    if (includeMediaProjection) {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        0
                    }
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundTypes,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持悬浮按钮在游戏画面上常驻"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- 工具

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val fallback = Point(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        return try {
            val point = Point()
            val display = windowManager.defaultDisplay ?: return fallback
            display.getRealSize(point)
            if (point.x <= 0 || point.y <= 0) fallback else point
        } catch (_: Exception) {
            fallback
        }
    }

    private fun loadRuntimeConfig(): OverlayConfig {
        val base = OverlayPrefs.load(this)
        activeGamePackage = GameSpecialPrefs.activePackage(this)
        val profile = activeGamePackage?.let { GameSpecialPrefs.load(this, it) } ?: GameSpecialConfig()
        return base.copy(
            autoReturnEnabled = profile.autoReturnEnabled,
            autoReturnDelayMs = profile.autoReturnDelayMs,
            staminaAutoSwitchEnabled = profile.staminaAutoSwitchEnabled,
            staminaThresholdPercent = profile.staminaThresholdPercent,
            staminaConfirmFrames = profile.staminaConfirmFrames,
        )
    }

    companion object {
        private const val CHANNEL_ID = "vesna_overlay"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 1_500L
        private const val STAMINA_POLL_INTERVAL_MS = 250L
        private const val STAMINA_ROI_LEFT = 0.46f
        private const val STAMINA_ROI_TOP = 0.43f
        private const val STAMINA_ROI_RIGHT = 0.66f
        private const val STAMINA_ROI_BOTTOM = 0.67f
        private const val STAMINA_MIN_CLASSIFIED_RATIO = 0.0005f
        private const val STAMINA_MIN_CLASSIFIED_PIXELS = 24
        private const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"

        @Volatile
        private var instance: OverlayService? = null

        @Volatile
        private var userStopRequested: Boolean = false

        fun markUserStop() {
            userStopRequested = true
        }

        /** 悬浮按钮此刻是否真的挂在屏幕上。 */
        fun isRunning(): Boolean = instance != null

        /** 屏幕捕获与体力分析器此刻都已运行。 */
        fun isStaminaCaptureRunning(): Boolean = instance?.let {
            it.config.staminaAutoSwitchEnabled && it.mediaProjection != null && it.imageReader != null
        } == true

        /**
         * 存活状态订阅者。服务挂起按钮、以及销毁时都会回调一次。
         *
         * 存在的理由：startForegroundService 是异步的 —— 点下开关的那一刻服务还没创建完，
         * 界面直接去问 isRunning() 只会得到「未开启」，得等用户切走再回来才刷新。
         * 让服务自己回报状态，界面才能做到点完即变。
         */
        private val runningObservers = CopyOnWriteArraySet<() -> Unit>()

        fun addRunningObserver(observer: () -> Unit) {
            runningObservers.add(observer)
        }

        fun removeRunningObserver(observer: () -> Unit) {
            runningObservers.remove(observer)
        }

        private fun notifyRunningChanged(context: Context) {
            runningObservers.forEach { observer ->
                runCatching { observer() }
                    .onFailure {
                        OperationLog.record(context, "界面刷新回调异常", it.javaClass.simpleName)
                    }
            }
        }

        /**
         * 设置页保存后调用：让运行中的服务立刻换用新配置（尺寸、透明度、可见范围）。
         * 服务未运行或动作无效时静默返回。
         */
        @JvmStatic
        fun reloadConfig() {
            val service = instance ?: return
            service.handler.post {
                val previousConfig = service.config
                val previousGamePackage = service.activeGamePackage
                service.config = service.loadRuntimeConfig()
                val gameChanged = previousGamePackage != service.activeGamePackage
                if (gameChanged || (previousConfig.autoReturnEnabled && !service.config.autoReturnEnabled)) {
                    service.cancelAutoReturn()
                }
                if (gameChanged) {
                    service.staminaConsecutiveLowFrames = 0
                    service.staminaConsecutiveNormalFrames = 0
                    service.staminaLowTriggered = false
                    if (service.mediaProjection != null && service.config.staminaAutoSwitchEnabled) {
                        service.mediaProjectionGamePackage = service.activeGamePackage
                    }
                }
                service.applyGeometry()
                service.restartForegroundPolling()
                service.refreshVisibilityByForeground()
                // 用户关闭体力条自动切后台时，立刻释放 MediaProjection。
                if (previousConfig.staminaAutoSwitchEnabled && !service.config.staminaAutoSwitchEnabled) {
                    service.releaseMediaProjection()
                    service.updateProjectionForegroundType(false)
                    OperationLog.record(service, "已关闭体力条自动切后台，释放 MediaProjection")
                }
                if (service.config.staminaAutoSwitchEnabled && service.imageReader != null) {
                    service.handler.removeCallbacks(service.staminaAnalyzer)
                    service.handler.post(service.staminaAnalyzer)
                }
            }
        }

        /**
         * 用户授权 MediaProjection 后，把授权 Intent 传给运行中的服务。
         * 服务未运行时会先被拉起。
         */
        @JvmStatic
        fun requestMediaProjection(context: Context, data: Intent) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_MEDIA_PROJECTION_DATA, data)
            }
            context.startForegroundService(intent)
        }

        private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(name, T::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(name) as? T
            }
    }
}
