package com.littletaro.vesna.core

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils

/**
 * 运行条件体检结果。
 *
 * 「必需」= 悬浮窗权限 + 无障碍服务；两者缺一，悬浮按钮都无法完成一次切后台。
 * 「可选」= 通知权限（Android 13+，前台服务需要）、电池优化白名单。
 */
data class RuntimeStatus(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationRequired: Boolean,
    val notificationGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val usageAccessGranted: Boolean,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
) {
    /** 悬浮窗权限 —— 必需 */
    val overlayReady: Boolean get() = overlayGranted

    /** 无障碍服务 —— 必需（真正执行切后台的那一环） */
    val accessibilityReady: Boolean get() = accessibilityEnabled

    /** 通知权限 —— 可选但推荐（前台服务通知被禁会降低存活率） */
    val notificationReady: Boolean get() = !notificationRequired || notificationGranted

    /** 电池优化白名单 —— 可选但推荐 */
    val batteryReady: Boolean get() = batteryUnrestricted

    /** 能否真正工作：两个必需项都就绪 */
    val coreReady: Boolean get() = overlayReady && accessibilityReady

    /** 是否还有可优化的项没做 */
    val hasOptionalWarnings: Boolean get() = !notificationReady || !batteryReady
}

object RuntimeProtection {

    fun inspect(context: Context): RuntimeStatus {
        val appContext = context.applicationContext
        val overlayGranted = runCatching { Settings.canDrawOverlays(appContext) }
            .onFailure { OperationLog.record(appContext, "悬浮窗权限检查异常") }
            .getOrDefault(false)

        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationGranted = !notificationRequired || appContext.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        val batteryUnrestricted = runCatching {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(false)

        return RuntimeStatus(
            overlayGranted = overlayGranted,
            accessibilityEnabled = isAccessibilityServiceEnabled(appContext),
            notificationRequired = notificationRequired,
            notificationGranted = notificationGranted,
            batteryUnrestricted = batteryUnrestricted,
            usageAccessGranted = ForegroundAppTracker.hasUsageAccess(appContext),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 从系统设置里读取无障碍服务是否已启用。
     *
     * 注意这是「系统设置里的开关状态」，不等于服务进程此刻存活 ——
     * 部分厂商（OPLUS 系）会周期性回收无障碍服务，实际可用性以
     * BackgroundAccessibilityService.isConnected() 为准。这里两者配合判断。
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)
        val enabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        if (enabledServices.isEmpty()) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val parsed = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (parsed.packageName == expected.packageName &&
                parsed.className == expected.className
            ) {
                return true
            }
        }
        return false
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean =
        isAccessibilityServiceEnabled(
            context,
            com.littletaro.vesna.a11y.BackgroundAccessibilityService::class.java,
        )

    fun openOverlaySettings(context: Context) {
        OperationLog.record(context, "跳转：悬浮窗权限设置")
        startSafely(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAccessibilitySettings(context: Context) {
        OperationLog.record(context, "跳转：无障碍设置")
        startSafely(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) {
        OperationLog.record(context, "跳转：电池优化白名单")
        startSafely(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openNotificationSettings(context: Context) {
        OperationLog.record(context, "跳转：通知设置")
        startSafely(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 打开本应用在系统设置里的详情页（用于手动清理权限）。 */
    fun openAppDetailsSettings(context: Context) {
        startSafely(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startSafely(context: Context, intent: Intent, fallback: Intent? = null) {
        val appContext = context.applicationContext
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
            if (fallback != null) {
                try {
                    appContext.startActivity(fallback)
                    return
                } catch (_: Exception) {
                    // 继续走下面的兜底记录
                }
            }
            OperationLog.record(appContext, "打开系统设置页失败", intent.action)
        }
    }

    fun overlayLabel(status: RuntimeStatus): String =
        if (status.overlayReady) "已允许" else "未允许"

    fun accessibilityLabel(status: RuntimeStatus): String =
        if (status.accessibilityReady) "已开启" else "未开启"

    fun notificationLabel(status: RuntimeStatus): String = when {
        !status.notificationRequired -> "无需授权"
        status.notificationGranted -> "已允许"
        else -> "未允许"
    }

    fun batteryLabel(status: RuntimeStatus): String =
        if (status.batteryUnrestricted) "已加入白名单" else "未加入白名单"

    /** 主页顶部的一句话状态。 */
    fun homeSummary(context: Context): String {
        val status = inspect(context)
        return when {
            !status.overlayReady && !status.accessibilityReady -> "还需要开启悬浮窗权限和无障碍服务"
            !status.overlayReady -> "还需要开启悬浮窗权限"
            !status.accessibilityReady -> "还需要开启无障碍服务"
            status.hasOptionalWarnings -> "已就绪（还有可优化的项）"
            else -> "全部就绪"
        }
    }

    /** 生成可复制给他人排查的诊断文本。不包含任何个人数据。 */
    fun diagnosticText(context: Context): String {
        val status = inspect(context)
        return buildString {
            appendLine("设备：${status.manufacturer} ${status.brand} ${status.model}")
            appendLine("系统：Android ${status.androidRelease}（API ${status.apiLevel}）")
            appendLine("悬浮窗权限：${overlayLabel(status)}")
            appendLine("无障碍服务：${accessibilityLabel(status)}")
            appendLine("通知权限：${notificationLabel(status)}")
            appendLine("电池优化：${batteryLabel(status)}")
            appendLine("使用情况访问：${if (status.usageAccessGranted) "已授权" else "未授权"}")
            appendLine()
            appendLine("—— 最近运行记录 ——")
            append(OperationLog.exportText(context))
        }
    }
}
