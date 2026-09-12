package com.xian.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.cos

/**
 * 翻牌倒计时。
 *
 * 传 "MM:SS" 或 "HH:MM:SS" 这类字符串：数字位逐位翻页，':' 画两个点。
 * 逐位独立 —— 只有真正变了的那一位会翻，所以 25:30 -> 25:29 只有最后一位动。
 *
 * 翻转用「绕中轴压扁」模拟：卡片绕中间那条折线转 θ 角，投影高度就是 h·cosθ。
 * 上下两半页各转 90°，合起来正好是一张翻过去的卡。省掉 Camera 的透视矩阵，
 * 少一半代码，视觉上同样是翻牌 —— 只是没有近大远小的透视。
 *
 * 把时间格式化留在调用方（LockMachineController.remainingText），这里只认字符串，
 * 免得「几小时才显示小时位」这类规则在 View 里再实现一遍。
 */
class FlipClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Slot(var ch: Char) {
        /** 动画起点值；静止时等于 ch */
        var from: Char = ch

        /** 1 = 静止，0..1 = 正在翻 */
        var progress: Float = 1f
    }

    private val slots = ArrayList<Slot>()
    private var display = ""
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // 这一层永远叠在品牌色底上，所以取固定色，不跟昼夜切换
    private val cardTopColor = ContextCompat.getColor(context, R.color.flip_card_top)
    private val cardBottomColor = ContextCompat.getColor(context, R.color.flip_card_bottom)
    private val dividerColor = ContextCompat.getColor(context, R.color.flip_card_divider)
    private val digitColor = ContextCompat.getColor(context, R.color.flip_card_text)
    private val colonColor = ContextCompat.getColor(context, R.color.on_primary)

    private val cornerRadius = 9f * density
    private val colonRadius = 3.5f * density
    private val dividerHeight = 1.5f * density

    /** 换成 "MM:SS" / "HH:MM:SS" 这类字符串；内容没变则什么都不做 */
    fun setDisplay(text: String) {
        if (text == display) return

        if (text.length != slots.size) {
            // 位数变了（比如跨过 1 小时多出小时位）—— 整体重建，这一帧不翻
            animator?.cancel()
            slots.clear()
            text.forEach { slots.add(Slot(it)) }
            display = text
            requestLayout()
            invalidate()
            return
        }

        var flipped = false
        text.forEachIndexed { index, ch ->
            val slot = slots[index]
            if (slot.ch != ch) {
                slot.from = slot.ch
                slot.ch = ch
                slot.progress = 0f
                flipped = true
            }
        }
        display = text
        if (flipped) startFlip()
    }

    private fun startFlip() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLIP_DURATION_MILLIS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                slots.forEach { slot ->
                    if (slot.progress < 1f) slot.progress = progress
                }
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> (DEFAULT_HEIGHT_DP * density).toInt()
        }
        val cardWidth = height * CARD_HEIGHT_RATIO * CARD_WIDTH_RATIO
        var content = 0f
        slots.forEachIndexed { index, slot ->
            if (index > 0) content += cardWidth * GAP_RATIO
            content += if (slot.ch == ':') cardWidth * COLON_WIDTH_RATIO else cardWidth
        }
        setMeasuredDimension(
            resolveSize(ceil(content).toInt(), widthMeasureSpec),
            height
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) return

        val viewHeight = height.toFloat()
        val cardHeight = viewHeight * CARD_HEIGHT_RATIO
        val cardWidth = cardHeight * CARD_WIDTH_RATIO
        val gap = cardWidth * GAP_RATIO
        val top = (viewHeight - cardHeight) / 2f
        textPaint.textSize = cardHeight * TEXT_RATIO

        var x = (width - contentWidth(cardWidth, gap)) / 2f
        slots.forEach { slot ->
            if (slot.ch == ':') {
                val colonWidth = cardWidth * COLON_WIDTH_RATIO
                drawColon(canvas, x, top, colonWidth, cardHeight)
                x += colonWidth + gap
            } else {
                drawSlot(canvas, slot, x, top, cardWidth, cardHeight)
                x += cardWidth + gap
            }
        }
    }

    private fun contentWidth(cardWidth: Float, gap: Float): Float {
        var content = 0f
        slots.forEachIndexed { index, slot ->
            if (index > 0) content += gap
            content += if (slot.ch == ':') cardWidth * COLON_WIDTH_RATIO else cardWidth
        }
        return content
    }

    /**
     * 造出一张卡的分量：翻页时上下半页分别画在不同时刻、来自不同数值，
     * 所以底色/数值都得能单独指定。静止时上下半页同值，拼起来就是一张完整的牌。
     */
    private fun drawSlot(canvas: Canvas, slot: Slot, left: Float, top: Float, w: Float, h: Float) {
        val half = h / 2f
        val progress = slot.progress

        if (progress >= 1f) {
            drawFace(canvas, left, top, w, h, slot.ch, true, cardTopColor)
            drawFace(canvas, left, top, w, h, slot.ch, false, cardBottomColor)
        } else {
            // 底面：上半露出新值，下半还留着旧值（等新页翻下来才盖住）
            drawFace(canvas, left, top, w, h, slot.ch, true, cardTopColor)
            drawFace(canvas, left, top, w, h, slot.from, false, cardBottomColor)

            val save = canvas.save()
            if (progress < 0.5f) {
                // 旧值的上半页绕中轴倒下去
                val angle = progress * 2f * 90f
                canvas.clipRect(left, top, left + w, top + half)
                canvas.scale(1f, cos(Math.toRadians(angle.toDouble())).toFloat().coerceAtLeast(0.02f), left, top + half)
                drawFace(canvas, left, top, w, h, slot.from, true, cardTopColor)
            } else {
                // 新值的下半页绕中轴翻上来
                val angle = (1f - progress) * 2f * 90f
                canvas.clipRect(left, top + half, left + w, top + h)
                canvas.scale(1f, cos(Math.toRadians(angle.toDouble())).toFloat().coerceAtLeast(0.02f), left, top + half)
                drawFace(canvas, left, top, w, h, slot.ch, false, cardBottomColor)
            }
            canvas.restoreToCount(save)
        }

        // 折线：整张牌的中轴，让上下半页看得出是两张
        dividerPaint.color = dividerColor
        canvas.drawRect(left, top + half - dividerHeight / 2f, left + w, top + half + dividerHeight / 2f, dividerPaint)
    }

    /**
     * 画一张完整卡牌的其中一半。
     * 先按整张牌的尺寸画背景和居中数字，再靠 clip 只留一半 ——
     * 这样上下两半拼起来绝对严丝合缝，不用分别算各自的基线。
     */
    private fun drawFace(
        canvas: Canvas,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        ch: Char,
        topHalf: Boolean,
        color: Int
    ) {
        val half = h / 2f
        val save = canvas.save()
        if (topHalf) canvas.clipRect(left, top, left + w, top + half)
        else canvas.clipRect(left, top + half, left + w, top + h)

        cardPaint.color = color
        canvas.drawRoundRect(left, top, left + w, top + h, cornerRadius, cornerRadius, cardPaint)

        textPaint.color = digitColor
        val baseline = top + half - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(ch.toString(), left + w / 2f, baseline, textPaint)
        canvas.restoreToCount(save)
    }

    private fun drawColon(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        cardPaint.color = colonColor
        val cx = left + w / 2f
        canvas.drawCircle(cx, top + h * 0.35f, colonRadius, cardPaint)
        canvas.drawCircle(cx, top + h * 0.65f, colonRadius, cardPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP = 92f
        const val CARD_HEIGHT_RATIO = 0.94f
        const val CARD_WIDTH_RATIO = 0.66f
        const val GAP_RATIO = 0.11f
        const val COLON_WIDTH_RATIO = 0.34f
        const val TEXT_RATIO = 0.62f
        const val FLIP_DURATION_MILLIS = 340L
    }
}
