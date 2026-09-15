package com.xian.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
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

    /**
     * 声明左右两条窄边不参与系统手势。
     *
     * Android 10 起，从屏幕边缘起手的滑动会被系统的「返回」手势优先接管：用户想在日历上
     * 右划回上一周，手指只要落得靠边一点就变成**退出应用** —— 这就是「右划返回太敏感」。
     * 系统不允许应用拦这个手势，只允许应用声明「这几块不参与」，所以只能在这里排掉。
     *
     * 系统的约束：只认列表里最前面的 200dp 高度（按矩形顺序累加），多出来的直接忽略，
     * 所以高度夹在上限内。宽度取 24dp —— 系统手势热区一般就这么宽；
     * 觉得还不够就调大 [GESTURE_EXCLUSION_EDGE_DP]，代价是这块的边缘返回手势会被让出来。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val density = resources.displayMetrics.density
        val edge = (GESTURE_EXCLUSION_EDGE_DP * density).toInt()
        val height = minOf(h, (GESTURE_EXCLUSION_MAX_DP * density).toInt())
        systemGestureExclusionRects = mutableListOf(
            Rect(0, 0, edge, height),
            Rect(w - edge, 0, w, height)
        )
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
                //
                // 判据必须是「横向位移 > 纵向位移」，不能只取纵向的一半 ——
                // 取一半等于斜着划就算横划，用户上下滚列表时手指稍微带点偏角就被判成切周，
                // 页面哗地翻到上一周（也就是用户说的「太敏感」）。
                if (expandState <= 0.5f && interceptHorizontal && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
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

    private companion object {
        /** 左右各让出多宽的边条不参与系统手势；系统手势热区一般 24dp。 */
        const val GESTURE_EXCLUSION_EDGE_DP = 24f

        /** 系统只接受最前面 200dp 的排除高度，超出部分忽略。 */
        const val GESTURE_EXCLUSION_MAX_DP = 200f
    }
}
