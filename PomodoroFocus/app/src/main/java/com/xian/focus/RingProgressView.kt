package com.xian.focus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * 专注进度圆环：底环 + 进度弧，取值 0f..1f。
 *
 * 只负责画，不持有任何业务状态 —— 谁调谁决定进度含义。
 * 颜色跟随当前主题（brand accent + divider），昼夜与 8 套色相都自动生效。
 *
 * 两种更新方式：
 * - [setProgress] 立刻跳到位，用于每秒 tick（动画进行中会被忽略，避免打断 [animateProgress]）
 * - [animateProgress] 平滑过渡，用于状态切换这种大跳变
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringStroke = 13f * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ringStroke
        color = ContextCompat.getColor(context, R.color.divider)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ringStroke
        color = context.themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
    }

    private val arcBounds = RectF()
    private var current = 0f
    private var animating = false
    private var animator: ValueAnimator? = null

    /** 立刻设置进度，不带动画。动画进行中直接忽略，让动画跑完。 */
    fun setProgress(progress: Float) {
        if (animating) return
        val value = progress.coerceIn(0f, 1f)
        if (value == current) return
        current = value
        invalidate()
    }

    /** 平滑过渡到目标进度（大跳变用）。 */
    fun animateProgress(target: Float) {
        val to = target.coerceIn(0f, 1f)
        if (!animating && to == current) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(current, to).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                current = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    animating = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animating = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    animating = false
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (196f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = ringStroke / 2f + 1f
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, -90f, 360f, false, trackPaint)
        if (current > 0f) {
            canvas.drawArc(arcBounds, -90f, 360f * current, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
