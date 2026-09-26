package com.littletaro.vesna

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.littletaro.vesna.core.ForegroundAppTracker
import com.littletaro.vesna.core.LaunchableApp
import com.littletaro.vesna.core.OperationLog
import com.littletaro.vesna.core.OverlayPrefs
import com.littletaro.vesna.core.ThemeColors
import com.littletaro.vesna.overlay.OverlayService
import com.littletaro.vesna.ui.dp
import com.littletaro.vesna.ui.labelText
import com.littletaro.vesna.ui.palette

/**
 * 挑选「哪些应用里显示悬浮按钮」。
 *
 * 勾选即时写入配置并热更新运行中的悬浮服务，不需要点保存。
 */
class AppPickerActivity : Activity() {

    private lateinit var apps: List<LaunchableApp>
    private val selected = mutableSetOf<String>()

    /** 标题下方那行动态文案，勾选后就地刷新，避免整页重建把滚动位置丢掉。 */
    private var countLabel: TextView? = null

    private val nightAtCreate by lazy { ThemeColors.isNight(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeColors.applySystemBars(this)
        selected.addAll(OverlayPrefs.load(this).targetPackages)
        apps = ForegroundAppTracker.installedLaunchableApps(this)
        setContentView(buildScreen())
    }

    override fun onPause() {
        persist()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemeColors.isNightConfig(newConfig) != nightAtCreate) recreate()
    }

    private fun persist() {
        val config = OverlayPrefs.load(this)
        OverlayPrefs.save(this, config.copy(targetPackages = selected.toSet()))
        OverlayService.reloadConfig()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette().screenBackground)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(6))
        }
        header.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelText("选择应用", 22f, palette().textPrimary))
                addView(
                    labelText(selectedSummary(), 12f, palette().textSecondary, top = 4).also {
                        countLabel = it
                    },
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            labelText("完成", 15f, palette().accent).apply {
                isClickable = true
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        if (apps.isEmpty()) {
            root.addView(
                labelText("没有读取到可启动的应用。", 14f, palette().textSecondary).apply {
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            return root
        }

        val listView = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(1)
            setBackgroundColor(Color.TRANSPARENT)
            adapter = AppAdapter()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            if (!selected.remove(app.packageName)) selected.add(app.packageName)
            OperationLog.record(
                this,
                if (selected.contains(app.packageName)) "勾选目标应用" else "取消勾选目标应用",
                app.packageName,
            )
            persist()
            countLabel?.text = selectedSummary()
            (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun selectedSummary(): String =
        if (selected.isEmpty()) "尚未选择，悬浮按钮暂时不会在任何应用里出现" else "已选 ${selected.size} 个应用"

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = apps[position]
            val context: Context = this@AppPickerActivity
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }

            runCatching {
                context.packageManager.getApplicationIcon(app.packageName)
            }.getOrNull()?.let { icon ->
                row.addView(
                    ImageView(context).apply {
                        setImageDrawable(icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    LinearLayout.LayoutParams(dp(32), dp(32)),
                )
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelText(app.label, 15f, palette().textPrimary))
                addView(
                    labelText(app.packageName, 11f, palette().textFaint, top = 3).apply {
                        maxLines = 1
                    },
                )
            }
            row.addView(
                textColumn,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (row.childCount > 0) leftMargin = dp(12)
                },
            )

            row.addView(
                CheckBox(context).apply {
                    isChecked = selected.contains(app.packageName)
                    isClickable = false
                    isFocusable = false
                },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
            )
            return row
        }
    }
}
