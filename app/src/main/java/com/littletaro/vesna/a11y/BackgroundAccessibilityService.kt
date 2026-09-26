package com.littletaro.vesna.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.littletaro.vesna.core.OperationLog

/** 一次「切到后台」请求的结果。 */
enum class SendToBackgroundOutcome {
    /** 系统已接受，当前应用应当已经退到后台 */
    SUCCESS,

    /** 无障碍服务当前不在线：可能未开启，也可能被厂商系统回收了 */
    NOT_CONNECTED,

    /** 服务在线，但系统拒绝了这次动作 */
    REJECTED,
    ;

    val isSuccess: Boolean get() = this == SUCCESS
}

/**
 * Vesna 的全部「权力」都在这里，而且只有一件事：把当前应用切到后台。
 *
 * 实现方式是调用系统公开接口 [AccessibilityService.performGlobalAction]
 * 并传入 [GLOBAL_ACTION_RECENTS]。这个动作由 system_server 的
 * SystemActionPerformer 处理，它内部会以系统身份（Binder.clearCallingIdentity）
 * 调用 StatusBarManager.expandSettingsPanel 同级的 toggleRecentApps，
 * 因此本应用不需要 root、不需要 Shizuku、也不需要模拟触摸手势。
 *
 * 服务刻意保持「盲」和「哑」：
 * - 不申请 canRetrieveWindowContent，读不到任何界面内容；
 * - 不申请 canPerformGestures，无法模拟任何点击或滑动；
 * - onAccessibilityEvent 为空实现，不处理、不缓存、不上报任何事件。
 */
class BackgroundAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OperationLog.record(this, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 故意留空：Vesna 不需要观察界面，任何事件都不读取、不保存、不上报。
    }

    override fun onInterrupt() {
        // 系统要求实现，无需处理：Vesna 没有正在进行的反馈。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        OperationLog.record(this, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        OperationLog.record(this, "无障碍服务已销毁")
        super.onDestroy()
    }

    private fun fireRecents(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }
            .onFailure { OperationLog.record(this, "执行切后台动作异常", it.javaClass.simpleName) }
            .getOrDefault(false)

    companion object {

        @Volatile
        private var instance: BackgroundAccessibilityService? = null

        /** 服务进程是否在线。注意这与「系统设置里的开关」是两件事。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 把当前前台应用切到后台，并进入最近任务界面。
         * 可从任意线程调用（performGlobalAction 是跨进程调用）。
         */
        fun sendCurrentAppToBackground(): SendToBackgroundOutcome {
            val service = instance ?: return SendToBackgroundOutcome.NOT_CONNECTED
            return if (service.fireRecents()) {
                SendToBackgroundOutcome.SUCCESS
            } else {
                SendToBackgroundOutcome.REJECTED
            }
        }
    }
}
