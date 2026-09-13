package com.xian.focus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 便签堆的手势层：上下滑动翻页（跟手）+ 长按拖到垃圾桶删除。
 *
 * 只负责手势与动画，业务结果通过回调交回 [NoteFragment]：
 * - [onFlip] 传翻页方向（-1 更早 / +1 更新），返回 false 表示已经到头、把纸弹回来
 * - [onDelete] 在便签被吸进垃圾桶之后回调
 *
 * 布局约定：必须有一个 id 为 `noteSheet` 的子 View（整叠纸，动画都作用在它身上），
 * 以及一个 id 为 `noteTrashTarget` 的垃圾桶（平时不可见）。
 */
class NoteSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onFlip: ((Int) -> Boolean)? = null
    var onDelete: (() -> Unit)? = null
    var onDragStateChange: ((Boolean) -> Unit)? = null

    var isDragging = false
        private set

    private val sheetView: View by lazy { findViewById(R.id.noteSheet) }
    private val trashView: View by lazy { findViewById(R.id.noteTrashTarget) }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = v * density

    private val trashRestColor = ContextCompat.getColor(context, R.color.text_tertiary)
    private val trashActiveColor = ContextCompat.getColor(context, R.color.danger)

    private var sheetAnim: ValueAnimator? = null
    private var downY = 0f
    private var swiping = false
    private var moved = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var overTrash = false

    // ---------- 上下滑动翻页 ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 拖拽中事件由 EditText 转给我们，这时候别抢
        if (isDragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                swiping = false
                sheetAnim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.y - downY) > touchSlop) {
                    swiping = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                swiping = true
                moved = false
                sheetAnim?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swiping) {
                    moved = true
                    applySwipe(ev.y - downY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 只是点一下（没滑动）就别放动画
                if (swiping && moved) settleSwipe(ev.y - downY)
                swiping = false
                moved = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 手指拖动时纸跟着走：越拖越透、稍微缩一点，像被从这一叠里抽出来。 */
    private fun applySwipe(dy: Float) {
        val p = (abs(dy) / sheetView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        sheetView.translationY = dy
        sheetView.translationX = 0f
        sheetView.alpha = 1f - p * 0.55f
        val s = 1f - p * 0.05f
        sheetView.scaleX = s
        sheetView.scaleY = s
    }

    /** 松手：过了阈值就翻过去（滑出 → 换内容 → 从另一侧滑回），否则弹回原位。 */
    private fun settleSwipe(dy: Float) {
        val height = sheetView.height.coerceAtLeast(1)
        val threshold = height * 0.26f
        val step = when {
            dy > threshold -> -1
            dy < -threshold -> 1
            else -> 0
        }
        if (step == 0) {
            animateSheet(
                fromY = sheetView.translationY, toY = 0f,
                fromAlpha = sheetView.alpha, toAlpha = 1f,
                fromScale = sheetView.scaleX, toScale = 1f,
                duration = 210, interp = OvershootInterpolator(1.1f)
            )
            return
        }
        val exit = if (step < 0) height + dp(56) else -(height + dp(56))
        animateSheet(
            fromY = sheetView.translationY, toY = exit,
            fromAlpha = sheetView.alpha, toAlpha = 0f,
            fromScale = sheetView.scaleX, toScale = 0.94f,
            duration = 160, interp = AccelerateInterpolator()
        ) {
            val flipped = onFlip?.invoke(step) ?: false
            // 到头了就把同一张从原方向滑回来，翻过去了就从对面进来
            val enter = if (flipped) -exit else exit
            animateSheet(
                fromY = enter, toY = 0f,
                fromAlpha = 0f, toAlpha = 1f,
                fromScale = 0.96f, toScale = 1f,
                duration = 240, interp = DecelerateInterpolator()
            )
        }
    }

    // ---------- 长按拖到垃圾桶 ----------

    fun startDrag(rawX: Float, rawY: Float) {
        if (isDragging) return
        sheetAnim?.cancel()
        isDragging = true
        overTrash = false
        dragStartX = rawX
        dragStartY = rawY
        sheetView.translationX = 0f
        sheetView.translationY = 0f
        sheetView.alpha = 1f
        sheetView.scaleX = 1f
        sheetView.scaleY = 1f

        val trash = trashView
        trash.animate().cancel()
        trash.visibility = View.VISIBLE
        trash.alpha = 0f
        trash.scaleX = 1f
        trash.scaleY = 1f
        trash.translationY = dp(14)
        (trash as? ImageView)?.setColorFilter(trashRestColor)
        trash.animate().alpha(1f).translationY(0f).setDuration(170).start()

        // 「被拿起来」：稍微放大
        animateSheet(
            fromY = 0f, toY = 0f, fromAlpha = 1f, toAlpha = 1f,
            fromScale = 1f, toScale = 1.04f,
            duration = 150, interp = DecelerateInterpolator()
        )
        onDragStateChange?.invoke(true)
    }

    fun updateDrag(rawX: Float, rawY: Float) {
        if (!isDragging) return
        sheetView.translationX = rawX - dragStartX
        sheetView.translationY = rawY - dragStartY
        refreshTrashHighlight()
    }

    fun endDrag() {
        if (!isDragging) return
        isDragging = false
        val dropping = overTrash
        overTrash = false
        hideTrash()
        if (dropping) {
            suckIntoTrash()
        } else {
            springBackToOrigin()
        }
        onDragStateChange?.invoke(false)
    }

    private fun refreshTrashHighlight() {
        val hit = isOverTrash()
        if (hit == overTrash) return
        overTrash = hit
        val scale = if (hit) 1.22f else 1f
        trashView.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        (trashView as? ImageView)?.setColorFilter(if (hit) trashActiveColor else trashRestColor)
    }

    private fun isOverTrash(): Boolean {
        if (trashView.visibility != View.VISIBLE) return false
        val sheetPos = IntArray(2).also { sheetView.getLocationOnScreen(it) }
        val trashPos = IntArray(2).also { trashView.getLocationOnScreen(it) }
        return Rect.intersects(
            Rect(sheetPos[0], sheetPos[1], sheetPos[0] + sheetView.width, sheetPos[1] + sheetView.height),
            Rect(trashPos[0], trashPos[1], trashPos[0] + trashView.width, trashPos[1] + trashView.height)
        )
    }

    private fun springBackToOrigin() {
        val startX = sheetView.translationX
        val startY = sheetView.translationY
        val startScale = sheetView.scaleX
        sheetAnim?.cancel()
        sheetAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.05f)
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                sheetView.translationX = startX * (1f - f)
                sheetView.translationY = startY * (1f - f)
                val s = startScale + (1f - startScale) * f
                sheetView.scaleX = s
                sheetView.scaleY = s
                sheetView.alpha = 1f
            }
            start()
        }
    }

    /** 被垃圾桶吸走：朝向垃圾桶位移 + 缩小 + 淡出，然后换内容再浮回来。 */
    private fun suckIntoTrash() {
        val trashPos = IntArray(2).also { trashView.getLocationOnScreen(it) }
        val sheetPos = IntArray(2).also { sheetView.getLocationOnScreen(it) }
        val dx = (trashPos[0] + trashView.width / 2f) - (sheetPos[0] + sheetView.width / 2f)
        val dy = (trashPos[1] + trashView.height / 2f) - (sheetPos[1] + sheetView.height / 2f)
        val startX = sheetView.translationX
        val startY = sheetView.translationY
        sheetAnim?.cancel()
        sheetAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = AccelerateInterpolator()
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                sheetView.translationX = startX + dx * f
                sheetView.translationY = startY + dy * f
                val s = 1f - 0.88f * f
                sheetView.scaleX = s
                sheetView.scaleY = s
                sheetView.alpha = 1f - f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheetView.translationX = 0f
                    sheetView.scaleX = 1f
                    sheetView.scaleY = 1f
                    onDelete?.invoke()
                    animateSheet(
                        fromY = dp(34), toY = 0f,
                        fromAlpha = 0f, toAlpha = 1f,
                        fromScale = 0.98f, toScale = 1f,
                        duration = 250, interp = DecelerateInterpolator()
                    )
                }
            })
            start()
        }
    }

    private fun hideTrash() {
        val trash = trashView
        trash.animate().cancel()
        trash.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(150)
            .withEndAction { trash.visibility = View.INVISIBLE }
            .start()
    }

    /** 手写补间而不是 ViewPropertyAnimator：cancel 时要能干净地丢掉回调，不然会串页。 */
    private fun animateSheet(
        fromY: Float,
        toY: Float,
        fromAlpha: Float,
        toAlpha: Float,
        fromScale: Float,
        toScale: Float,
        duration: Long,
        interp: Interpolator,
        onEnd: (() -> Unit)? = null
    ) {
        sheetAnim?.cancel()
        sheetAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                sheetView.translationY = fromY + (toY - fromY) * f
                sheetView.alpha = fromAlpha + (toAlpha - fromAlpha) * f
                val s = fromScale + (toScale - fromScale) * f
                sheetView.scaleX = s
                sheetView.scaleY = s
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }
}
