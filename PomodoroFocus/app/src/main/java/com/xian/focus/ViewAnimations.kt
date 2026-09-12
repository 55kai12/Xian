package com.xian.focus

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 让页面内容"浮入"：按顺序给直接子 View 施加 淡入 + 上移，每项错开一点。
 *
 * 只在 Fragment 首次建视图时调用一次 —— 转场动画负责页面级位移，
 * 这里负责内容级层次，两者叠加才有"页面打开"的感觉。
 */
fun ViewGroup.staggerIn(
    stepMillis: Long = 55L,
    durationMillis: Long = 320L,
    translateDp: Float = 16f
) {
    val offset = translateDp * resources.displayMetrics.density
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child.visibility != View.VISIBLE) continue
        child.animate().cancel()
        child.alpha = 0f
        child.translationY = offset
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * stepMillis)
            .setDuration(durationMillis)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

/**
 * 自动找"该动起来的那一层"：
 * - 单子 View 的容器（ScrollView）→ 下钻一层，对滚动内容做浮入
 * - 多子 View 的容器（LinearLayout 根）→ 直接对自身子项做浮入
 */
fun View.staggerScrollContent() {
    val root = this as? ViewGroup ?: return
    val target = if (root.childCount == 1 && root.getChildAt(0) is ViewGroup) {
        root.getChildAt(0) as ViewGroup
    } else {
        root
    }
    target.staggerIn()
}
