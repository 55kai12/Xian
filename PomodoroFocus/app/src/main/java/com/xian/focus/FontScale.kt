package com.xian.focus

import android.content.Context
import android.content.res.Configuration

/**
 * 系统字体缩放（fontScale）的闸门 —— **跟随，但封顶**。
 *
 * 应用默认跟随系统字体（`sp` 会按 fontScale 放大），这对视力不好的用户是好事。
 * 但超大字号放大的是「排版」：底部导航、按钮、日历周条、各种写死高度的行都是按
 * 固定尺寸摆好的，放大到某个点之后只是把界面挤烂 —— 字反而更难认。
 * 所以跟到 [MAX] 为止：用户调到 2 倍，我们按 1.5 倍渲染，字确实变大了，版面还站得住。
 *
 * ⚠️ **两个入口都要包，少一个就漏一块**：
 * · [FocusApplication] —— 三套悬浮层（锁机 / 限额 / 通用锁）都用 `applicationContext`
 *   inflate，靠这一处兜住；
 * · [MainActivity] —— 界面本身。**Application 换掉的 base context 管不到 Activity**
 *   （Activity 的 base context 由 ActivityThread 单独造），必须各自覆盖。
 *   全项目只有这一个 Activity，覆盖它等于覆盖所有界面（Fragment / Dialog 都从它派生）。
 *
 * 只动 `fontScale`，不动 `densityDpi`：「显示大小」那一档是整体尺寸问题，
 * 换掉 dpi 会让所有 dp 重新换算、等于凭空缩放布局，不在这一层的职责里。
 */
object FontScale {

    /** 跟随系统字体，但最多到这个倍数。（Android 原生最大档是 1.3，国产 ROM 能开到 2 倍以上。） */
    const val MAX = 1.5f

    /** 把 [base] 的字体缩放钳到 [MAX] 以内；没超就原样返回，省一次 Context 构造。 */
    fun cap(base: Context): Context {
        if (base.resources.configuration.fontScale <= MAX) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = MAX
        return base.createConfigurationContext(config)
    }
}
