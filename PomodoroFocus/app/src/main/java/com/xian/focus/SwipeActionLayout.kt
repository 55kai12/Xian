package com.xian.focus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * 左滑露出动作按钮的行容器。
 *
 * 子 View 约定（顺序固定，见 item_task.xml）：
 *   0 —— 动作行：停靠在右侧，宽度由本容器按可用宽度算
 *   1 —— 内容层：铺满整行；横向拖动时移动它，露出压在下面的动作行
 *
 * 为什么不用 ItemTouchHelper 的 swipe 做这件事：它的模型是「滑过阈值 = 划走」，
 * 松手停在中间属于「未完成的滑动」，ACTION_UP 时会启动回弹把 item 拉回原位，
 * 露出来的那一瞬间就被收走了，根本等不到点击。所以横向手势只能自己管。
 */
class SwipeActionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 吸附到位后通知展开 / 收起，用来让列表同一时刻只留一个展开的行。 */
    var onExpandedChange: ((SwipeActionLayout, Boolean) -> Unit)? = null

    private val actionRow: View? get() = if (childCount > 0) getChildAt(0) else null
    private val contentLayer: View? get() = if (childCount > 1) getChildAt(1) else null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var actionWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStartOffset = 0f
    private var settleAnimator: ValueAnimator? = null

    val isExpanded: Boolean get() = (contentLayer?.translationX ?: 0f) < 0f

    /** ViewHolder 复用时要先归位，否则会带着上一行的展开状态出现。 */
    fun reset() {
        settleAnimator?.let {
            settleAnimator = null
            it.cancel()
        }
        dragging = false
        contentLayer?.translationX = 0f
    }

    /** 收起。已经收起就什么都不做。 */
    fun close() {
        if (isExpanded) settleTo(0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val row = actionRow
        val content = contentLayer
        if (row == null || content == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val available = MeasureSpec.getSize(widthMeasureSpec)
        actionWidth = actionRowWidth(available)
        content.measure(
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
        // 动作行的高度跟着内容走：RecyclerView 给的是 UNSPECIFIED，
        // 先量内容再按它的高度 EXACTLY 量动作行，色块才会正好铺满整行。
        val height = content.measuredHeight
        row.measure(
            MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(available, height)
    }

    /**
     * 动作行宽度按可用宽度算，不写死 dp：
     * 窄屏上四个按钮不能把内容挤没，宽屏上也没必要跟着无限拉长。
     */
    private fun actionRowWidth(available: Int): Int {
        val density = resources.displayMetrics.density
        val preferred = (available * ACTION_ROW_RATIO).toInt()
        val ceiling = (MAX_ACTION_WIDTH_DP * ACTIONS * density).toInt()
        return min(available, min(preferred, ceiling))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val row = actionRow
        val content = contentLayer
        if (row == null || content == null) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val w = right - left
        val h = bottom - top
        row.layout(w - actionWidth, 0, w, h)
        content.layout(0, 0, w, h)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                // 手指再落下时把还在跑的吸附动画停掉，否则会和拖动抢 translationX
                settleAnimator?.let {
                    settleAnimator = null
                    it.cancel()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 先动的是横向就接管；纵向留给 RecyclerView 滚动
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    dragStartOffset = contentLayer?.translationX ?: 0f
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val content = contentLayer ?: return super.onTouchEvent(ev)
        if (dragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE ->
                    content.translationX =
                        (dragStartOffset + ev.x - downX).coerceIn(-actionWidth.toFloat(), 0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 过了半个动作区就算展开，否则弹回原位
                    settleTo(
                        if (content.translationX < -actionWidth / 2f) -actionWidth.toFloat() else 0f
                    )
                    dragging = false
                }
            }
            return true
        }
        // 没在拖动：交给 View 自己的点击处理。
        // 这里不能一律 return true —— 那样 DOWN 不会进入按下状态，UP 也不会有 performClick，
        // 整行的「点击进编辑 / 点击收起」会一起失效。
        return super.onTouchEvent(ev)
    }

    private fun settleTo(target: Float) {
        val content = contentLayer ?: return
        val from = content.translationX
        settleAnimator?.let {
            settleAnimator = null
            it.cancel()
        }
        if (abs(from - target) < 0.5f) {
            content.translationX = target
            onExpandedChange?.invoke(this, isExpanded)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = SETTLE_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { content.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 被拖动打断的旧动画不能在这里回调，否则会误关刚打开的那一行
                    if (settleAnimator === animation) {
                        settleAnimator = null
                        onExpandedChange?.invoke(this@SwipeActionLayout, isExpanded)
                    }
                }
            })
            start()
        }
    }

    private companion object {
        /** 四个按钮合计占可用宽度的比例 */
        const val ACTION_ROW_RATIO = 0.6f

        /** 单块动作的宽度上限，避免大屏上按钮被拉得过宽 */
        const val MAX_ACTION_WIDTH_DP = 64f

        const val ACTIONS = 4
        const val SETTLE_MILLIS = 200L
    }
}
