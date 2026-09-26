package com.littletaro.vesna.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/**
 * 控制「Vesna 自己要不要出现在最近任务列表里」。
 *
 * ## 为什么不是 Activity.setExcludeFromRecents()
 *
 * 这个方法**不存在** —— 它是这轮踩到的坑。`android:excludeFromRecents` 只是 manifest 里的
 * 静态属性，Activity 起来之后就没法改。Android 也**没有**提供在 Activity 上改这个值的公开 API。
 *
 * 官方真正提供的运行时入口在 [ActivityManager.AppTask] 上：找到本应用自己的那个任务，
 * 调 [ActivityManager.AppTask.setExcludeFromRecents]。它改的是「任务」的可见性，
 * 效果与 manifest 属性一致，但可以随时开关。
 *
 * ## 注意
 *
 * 改的是**整个任务**而非单个 Activity。MainActivity 与 SettingsActivity 属于同一个任务，
 * 所以一改两个都跟着变 —— 这正是想要的效果（不然隐藏了主页却留着设置页，等于没隐藏）。
 */
object RecentsVisibility {

    /**
     * 设置本应用任务「是否从最近任务隐藏」。
     *
     * 任务还没建立时（例如在 App 启动最早的瞬间调用）会找不到，此时返回 false，
     * 不做任何事 —— 反正 manifest 里的默认值仍然在生效。
     *
     * @return 成功设置返回 true；未找到任务或被系统拒绝返回 false。
     */
    fun apply(activity: Activity, hide: Boolean): Boolean {
        val task = findOwnTask(activity) ?: return false
        return runCatching { task.setExcludeFromRecents(hide) }.isSuccess
    }

    /**
     * 在所有 AppTask 里找出本应用自己的那一个。
     *
     * `getAppTasks()` 只看得到自己应用的任务，不需要额外权限。
     * 用户在最近任务里可能开了本应用的多个实例，取第一个即可 —— 它们同属一个包，
     * 设置哪个效果都一样。
     */
    private fun findOwnTask(context: Context): ActivityManager.AppTask? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val packageName = context.packageName
        return runCatching {
            manager.appTasks.firstOrNull { task ->
                task.taskInfo?.baseIntent?.component?.packageName == packageName
            }
        }.getOrNull()
    }
}
