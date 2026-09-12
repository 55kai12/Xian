package com.xian.focus

import android.content.Context
import android.view.ContextThemeWrapper

object ThemeStore {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_SELECTED_THEME = "selected_theme"

    const val THEME_QINGLV = 0
    const val THEME_ZHUSHA = 1
    const val THEME_DAILAN = 2
    const val THEME_XUANMO = 3

    fun selectedTheme(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SELECTED_THEME, THEME_QINGLV)

    fun setSelectedTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SELECTED_THEME, theme).apply()
    }

    fun themeResId(theme: Int): Int = when (theme) {
        THEME_ZHUSHA -> R.style.Theme_Xian_Zhusha
        THEME_DAILAN -> R.style.Theme_Xian_Dailan
        THEME_XUANMO -> R.style.Theme_Xian_Xuanmo
        else -> R.style.Theme_Xian_Qinglv
    }

    /**
     * 给非 Activity 的 context 套上用户当前选的主题。
     * 悬浮窗（WindowManager overlay）只能拿 application context 渲染，而色相是 MainActivity
     * 里 setTheme 出来的 —— 不套这一层，锁机覆盖层会永远停在默认的青绿，
     * 跟用户在设置里选的色相对不上。
     */
    fun wrap(context: Context): Context =
        ContextThemeWrapper(context, themeResId(selectedTheme(context)))
}
