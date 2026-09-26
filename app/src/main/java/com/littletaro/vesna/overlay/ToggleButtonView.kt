package com.littletaro.vesna.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮按钮本体：一个圆形深色底盘 + 与启动图标同源的「卡片堆叠」符号。
 *
 * 交互沿用同一套手感：
 * - 短按（未移动、按下时长 < [MAX_CLICK_MS]）→ 触发切后台；
 * - 长按 [LONG_PRESS_MS] 进入拖动，松手落位；
 * - 拖动过程中手指位移超过系统 [ViewConfiguration.getScaledTouchSlop] 即视为拖动意图，
 *   不会误触发切后台。
 *
 * 用自绘而不用 ImageView，是为了让图标在任何按钮尺寸下都按比例缩放，
 * 且在游戏画面上保持边缘清晰的矢量观感。
 */
@SuppressLint("ViewConstructor")
class ToggleButtonView(
    context: Context,
    private val onTrigger: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = Color.argb(96, 255, 255, 255)
    }
    private val backCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
    }
    private val frontCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val cardRect = RectF()
    private val countdownTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val countdownTextBounds = Rect()

    private var pressed = false
    private var dragging = false
    private var moved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L

    /** 倒计时秒数；>=0 时显示数字，<0 时显示默认卡片图标。 */
    private var _countdownSeconds = -1
    var countdownSeconds: Int
        get() = _countdownSeconds
        set(value) {
            _countdownSeconds = value
            invalidate()
        }

    private val longPressRunnable = Runnable {
        if (pressed) {
            dragging = true
            onDragStart()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animate().cancel()
            animate().scaleX(0.94f).scaleY(0.94f).setDuration(120L).start()
        }
    }

    init {
        isClickable = true
        isFocusable = false
        isLongClickable = false
        isHapticFeedbackEnabled = true
        contentDescription = "Vesna 悬浮按钮：点一下把当前游戏切到后台，长按可拖动"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        // 圆形底盘：拖动时略亮，给出"已抓住"的反馈。
        val inset = density * 1.5f
        val radius = min(w, h) / 2f - inset
        discPaint.color = when {
            dragging -> Color.argb(235, 52, 60, 78)
            pressed -> Color.argb(225, 44, 50, 64)
            else -> Color.argb(198, 24, 27, 34)
        }
        canvas.drawCircle(cx, cy, radius, discPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (countdownSeconds >= 0) {
            // 倒计时期间用大号数字替换卡片符号，方便用户扫一眼就知道还剩几秒。
            countdownTextPaint.textSize = min(w, h) * 0.55f
            val text = countdownSeconds.toString()
            countdownTextPaint.getTextBounds(text, 0, text.length, countdownTextBounds)
            val textY = cy - countdownTextBounds.exactCenterY()
            canvas.drawText(text, cx, textY, countdownTextPaint)
        } else {
            // 卡片堆叠符号：后层半透明、前层实心，和启动图标同一套比例。
            val cardW = w * 0.44f
            val cardH = h * 0.26f
            val cardR = cardW * 0.20f
            val dx = w * 0.02f
            val dy = h * 0.11f
            backCardPaint.color = if (pressed) {
                Color.argb(180, 255, 255, 255)
            } else {
                Color.argb(140, 255, 255, 255)
            }
            drawCard(canvas, cx - dx, cy - dy, cardW, cardH, cardR, backCardPaint)
            drawCard(canvas, cx + dx, cy + dy, cardW, cardH, cardR, frontCardPaint)
        }
    }

    private fun drawCard(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        cardWidth: Float,
        cardHeight: Float,
        corner: Float,
        paint: Paint,
    ) {
        cardRect.set(
            centerX - cardWidth / 2f,
            centerY - cardHeight / 2f,
            centerX + cardWidth / 2f,
            centerY + cardHeight / 2f,
        )
        canvas.drawRoundRect(cardRect, corner, corner, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                moved = false
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTime = SystemClock.uptimeMillis()
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val movedX = abs(event.rawX - downRawX)
                val movedY = abs(event.rawY - downRawY)
                if (!dragging && (movedX > touchSlop || movedY > touchSlop)) {
                    moved = true
                }
                if (dragging) {
                    onDrag(event.rawX - downRawX, event.rawY - downRawY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val duration = SystemClock.uptimeMillis() - downTime
                if (dragging) {
                    onDragEnd()
                } else if (!moved && duration < MAX_CLICK_MS) {
                    performClick()
                    onTrigger()
                }
                pressed = false
                dragging = false
                animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) onDragEnd()
                pressed = false
                dragging = false
                animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 450L

        /** 超过这个时长的按压不再算作点击，避免"按住发呆后松手"被误触发。 */
        private const val MAX_CLICK_MS = 800L
    }
}
