package com.littletaro.vesna.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.littletaro.vesna.core.ThemeColors
import kotlin.math.roundToInt

/**
 * 三个页面（主页 / 设置 / 选应用）共用的控件工厂。
 *
 * 全部界面都用代码构建而非 XML 布局：本应用的界面元素极少且结构简单，
 * 代码构建能让深浅色切换、实时刷新状态这类逻辑集中在一处，不必维护两套资源。
 */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun Context.palette(): ThemeColors.Palette = ThemeColors.of(this)

fun Context.roundedBackground(
    fill: Int,
    stroke: Int,
    cornerDp: Int = 10,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(cornerDp).toFloat()
    setColor(fill)
    setStroke(dp(1), stroke)
}

fun Context.screenScroll(): ScrollView = ScrollView(this).apply {
    setBackgroundColor(palette().screenBackground)
    isFillViewport = true
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
}

fun Context.screenRoot(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(24), dp(28), dp(24), dp(32))
}

fun Context.labelText(
    text: String,
    size: Float,
    color: Int,
    top: Int = 0,
): TextView = TextView(this).apply {
    setText(text)
    textSize = size
    setTextColor(color)
    setLineSpacing(0f, 1.08f)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}

fun Context.sectionLabel(text: String, top: Int = 0): TextView =
    labelText(text, 13f, palette().accent, top)

fun Context.actionButton(text: String, primary: Boolean): Button = Button(this).apply {
    setText(text)
    isAllCaps = false
    textSize = 15f
    setTextColor(if (primary) palette().buttonPrimaryText else palette().textPrimary)
    background = roundedBackground(
        if (primary) palette().accent else palette().buttonSecondaryBg,
        if (primary) palette().accent else palette().buttonSecondaryStroke,
    )
    stateListAnimator = null
    setPadding(dp(12), 0, dp(12), 0)
}

/**
 * 胶囊描边按钮：用于卡片里的「一键启动」「点击跳转」等次级操作。
 */
fun Context.capsuleButton(text: String, accent: Boolean = true): TextView = TextView(this).apply {
    setText(text)
    textSize = 13f
    setTextColor(if (accent) palette().accent else palette().textSecondary)
    maxLines = 1
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (accent) palette().accentSoft else palette().subtleBoxBg)
        setStroke(dp(1), if (accent) palette().accent else palette().subtleBoxStroke)
    }
    setPadding(dp(12), dp(6), dp(12), dp(6))
}

fun Context.badgeChip(text: String, textColor: Int, fillColor: Int): View =
    TextView(this).apply {
        setText(text)
        textSize = 12f
        setTextColor(textColor)
        maxLines = 1
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(fillColor)
            setStroke(dp(1), textColor)
        }
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }

/** 一张普通信息卡片：圆角 + 描边 + 统一内边距。 */
fun Context.cardContainer(paddingH: Int = 16, paddingV: Int = 14): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingH), dp(paddingV), dp(paddingH), dp(paddingV))
        background = roundedBackground(palette().cardBackground, palette().cardStroke)
    }

/**
 * 一行「标题 + 状态 + 右侧箭头」的可点击条目，用于权限清单。
 */
fun Context.statusRow(
    title: String,
    value: String,
    valueColor: Int,
    actionHint: String?,
    onClick: (() -> Unit)?,
): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = roundedBackground(palette().cardBackground, palette().cardStroke)
    if (onClick != null) {
        isClickable = true
        setOnClickListener { onClick() }
    }

    addView(
        labelText(title, 15f, palette().textPrimary),
        LinearLayout.LayoutParams(0, -2, 1f),
    )
    addView(
        TextView(this@statusRow).apply {
            setText(value)
            textSize = 13f
            setTextColor(valueColor)
        },
        LinearLayout.LayoutParams(-2, -2),
    )
    if (actionHint != null) {
        addView(
            TextView(this@statusRow).apply {
                setText(actionHint)
                textSize = 15f
                setTextColor(palette().textFaint)
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) },
        )
    }
}

/**
 * 一行「标题 + 说明 + 右侧勾选框」的可点击条目。整行都可点，勾选框自身不接收点击，
 * 避免出现「点了文字没反应、必须精准点小方块」这种手感问题。
 */
fun Context.toggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
): View = cardContainer().apply {
    val row = LinearLayout(this@toggleRow).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setOnClickListener { onToggle() }
    }
    row.addView(
        LinearLayout(this@toggleRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelText(title, 15f, palette().textPrimary))
            addView(labelText(description, 12f, palette().textSecondary, top = 4))
        },
        LinearLayout.LayoutParams(0, -2, 1f),
    )
    row.addView(
        CheckBox(this@toggleRow).apply {
            isChecked = checked
            isClickable = false
        },
        LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
    )
    addView(row, LinearLayout.LayoutParams(-1, -2))
}

fun Activity.applyTheme() {
    ThemeColors.applySystemBars(this)
}

/**
 * 数字滑块：标题 + 当前值 + SeekBar。
 * 拖动时实时回调，适合毫秒/秒/dp 这类连续数值配置。
 */
fun Context.numberSlider(
    title: String,
    value: Int,
    range: IntRange,
    label: (Int) -> String,
    onChanged: (Int) -> Unit,
): View = cardContainer().apply {
    val valueView = labelText(label(value), 12f, palette().textSecondary, top = 4)
    addView(labelText(title, 15f, palette().textPrimary))
    addView(valueView)
    addView(
        SeekBar(this@numberSlider).apply {
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

/**
 * 重建整页，但把滚动位置保留下来。
 *
 * 这几个页面用「整体重建」（setContentView）实现状态刷新，简单可靠，
 * 代价是 ScrollView 会弹回顶部 —— 点任意按钮、更新检查回状态、
 * 从系统设置页返回都会触发一次，用户看到的就是「页面自己跳上去了」。
 *
 * 这里在重建前记下位置、布局完成后还原，让刷新对用户不可见。
 * 必须等布局完成才能还原：ScrollView 在测量之前拿不到内容高度，过早调用会被钳成 0。
 */
fun Activity.replaceContentPreservingScroll(build: () -> View) {
    val previous = (findViewById<View>(android.R.id.content) as? ViewGroup)
        ?.getChildAt(0) as? ScrollView
    val targetY = previous?.scrollY ?: 0

    val fresh = build()
    setContentView(fresh)

    val scroll = fresh as? ScrollView ?: return
    if (targetY <= 0) return
    scroll.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.scrollTo(0, targetY)
            }
        },
    )
}
