package com.littletaro.vesna.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行事件日志：只记录「什么时候发生了什么」，用于用户自查和问题排查导出。
 *
 * 采用固定容量的环形缓冲（保留最近 [MAX_ENTRIES] 条），落盘在 SharedPreferences。
 * 不记录截图或触摸内容；自动返回相关事件可能包含目标应用包名。
 */
object OperationLog {
    private const val PREFS_NAME = "vesna_operation_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300
    private const val SEPARATOR = "\n"

    @Volatile
    private var lineFormat: SimpleDateFormat? = null

    private fun formatter(): SimpleDateFormat =
        lineFormat ?: SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).also { lineFormat = it }

    @Synchronized
    fun record(context: Context, event: String, detail: String? = null) {
        val line = buildString {
            append(formatter().format(Date()))
            append("  ")
            append(event)
            if (!detail.isNullOrBlank()) {
                append("  · ")
                append(detail)
            }
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ENTRIES, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val updated = (existing + line).takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, updated.joinToString(SEPARATOR)).apply()
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 60): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ENTRIES, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.takeLast(limit)
            ?: emptyList()
    }

    @Synchronized
    fun exportText(context: Context): String {
        val lines = recent(context, MAX_ENTRIES)
        if (lines.isEmpty()) return "（暂无运行记录）"
        return lines.joinToString("\n")
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }
}
