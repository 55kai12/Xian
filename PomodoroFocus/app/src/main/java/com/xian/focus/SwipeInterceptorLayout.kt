package com.xian.focus

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

class SwipeInterceptorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var expandAnimator: ValueAnimator? = null

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    /** 由外部决定是否需要拦截横向手势。日历页始终交给 CalendarView 翻月。 */
    var interceptHorizontal = false

    /** 展开区域的最大高度（px），由外部设置 */
    var maxExpandHeight = 1

    /** 0f=完全收起，1f=完全展开 */
    var expandState = 0f
        private set

    /** 手指拖拽过程中持续回调 */
    var onExpandProgress: ((Float) -> Unit)? = null

    /** 松手吸附动画结束后回调，true=展开 false=收起 */
    var onExpandSettled: ((Boolean) -> Unit)? = null

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        val target = if (expanded) 1f else 0f
        if (!animate) {
            updateExpand(target)
            onExpandSettled?.invoke(expanded)
        } else {
            animateTo(target)
        }
    }

    fun setExpandProgress(progress: Float) {
        expandAnimator?.cancel()
        updateExpand(progress.coerceIn(0f, 1f))
    }

    private fun updateExpand(v: Float) {
        expandState = v
        onExpandProgress?.invoke(v)
    }

    private fun animateTo(target: Float) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(expandState, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { updateExpand(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onExpandSettled?.invoke(target >= 1f)
                }
            })
            start()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                dragging = false
                expandAnimator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 纵向拖拽由底部把手自身处理；这里仅拦截收起态的横向切周。
                if (expandState <= 0.5f && interceptHorizontal && abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val duration = ev.eventTime - downTime
                if (dragging && abs(dx) > touchSlop * 2) {
                    if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
            }
        }
        return dragging || super.onTouchEvent(ev)
    }
}
