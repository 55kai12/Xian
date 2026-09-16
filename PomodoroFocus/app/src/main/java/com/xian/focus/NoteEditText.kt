package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat

/**
 * 带横格线的便签输入框。
 *
 * 格线不是「固定间距平铺」的 —— 位置由 Layout 里每一行的基线算出来，
 * 所以空便贴、三行、或者写满一屏开始滚动，线永远贴着字的下面走。
 * 换字号、改 lineSpacingExtra 都不用回来重算间距，这是选它而不是选平铺 drawable 的原因。
 *
 * 只画文字占的那几行会像「贴了几条线」，所以从第一行往上、最后一行往下各延展到纸边。
 */
class NoteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.note_rule)
    }

    /** 线落在基线下面多少。贴着基线画会被下伸的笔画（g、y、撇）压住。 */
    private val ruleGap = 3f * density

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && layout.lineCount > 0) {
            val lineHeight = (layout.getLineBottom(0) - layout.getLineTop(0)).toFloat()
            // lineHeight < 1 说明还没量过，这时候硬画会变成死循环
            if (lineHeight > 1f) {
                // 先画线再交给 super 画文字：线在下面，笔画压在上面才不会被割断
                val voffset = verticalOffset(layout.height)
                val save = canvas.save()
                canvas.translate(compoundPaddingLeft.toFloat(), voffset.toFloat())
                val right = layout.width.toFloat()
                val firstBaseline = layout.getLineBaseline(0) + ruleGap
                var y = firstBaseline
                while (y < height - voffset) {
                    canvas.drawLine(0f, y, right, y, rulePaint)
                    y += lineHeight
                }
                y = firstBaseline - lineHeight
                while (y > -voffset) {
                    canvas.drawLine(0f, y, right, y, rulePaint)
                    y -= lineHeight
                }
                canvas.restoreToCount(save)
            }
        }
        super.onDraw(canvas)
    }

    /**
     * 文字块顶边相对控件顶部的偏移，等价于 TextView 内部那套 gravity 水平（只有靠中/靠底才不为 0）。
     * 算错了格线会整体上下错位 —— 不报错，只是看着别扭，所以这里跟 AOSP 的 getVerticalOffset 保持一致。
     */
    private fun verticalOffset(textHeight: Int): Int {
        val box = height - paddingTop - paddingBottom
        if (box <= textHeight) return paddingTop
        val extra = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> box - textHeight
            Gravity.CENTER_VERTICAL -> (box - textHeight) / 2
            else -> 0
        }
        return paddingTop + extra
    }
}
