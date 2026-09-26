package com.littletaro.vesna.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.os.Process

/** 一个可被选中的目标应用（用于「仅在指定应用显示」）。 */
data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/**
 * 前台应用识别：用于「仅在指定应用显示悬浮按钮」。
 *
 * 基于 UsageStatsManager 的事件流（ACTIVITY_RESUMED / MOVE_TO_FOREGROUND）判断当前前台应用，
 * 需要用户在系统设置里授予「使用情况访问」权限。
 *
 * 若用户不开启这个权限，悬浮按钮就走「一直显示」模式 —— 功能不受影响，只是少了一层过滤。
 */
object ForegroundAppTracker {

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            OperationLog.record(context, "打开「使用情况访问」设置失败")
        }
    }

    /**
     * 返回最近一次进入前台的包名；没有可用事件时返回 null。
     * 本应用自身的包名会被过滤（打开 Vesna 设置页不应被当成目标应用）。
     *
     * 事件窗口由短到长重试：用户可能已经在游戏里停留很久，
     * 长窗口才能捞到那次「进入前台」事件。
     */
    fun currentForegroundPackage(context: Context): String? {
        val appContext = context.applicationContext
        val usageStatsManager = appContext
            .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val now = System.currentTimeMillis()
            val windows = longArrayOf(5_000L, 30_000L, 120_000L, 600_000L)
            for (window in windows) {
                val events = usageStatsManager.queryEvents(now - window, now)
                val event = UsageEvents.Event()
                var lastPackage: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    ) {
                        lastPackage = event.packageName
                    }
                }
                val result = lastPackage
                    ?.takeIf { it != appContext.packageName }
                    ?.takeIf { it.isNotBlank() }
                if (result != null) return result
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 已安装且可启动的应用列表（用于挑选目标游戏）。 */
    fun installedLaunchableApps(context: Context): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            context.packageManager
                .queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != context.packageName }
                .map {
                    LaunchableApp(
                        packageName = it.packageName,
                        label = runCatching {
                            context.packageManager.getApplicationLabel(it).toString()
                        }.getOrDefault(it.packageName),
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun applicationLabel(context: Context, packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
