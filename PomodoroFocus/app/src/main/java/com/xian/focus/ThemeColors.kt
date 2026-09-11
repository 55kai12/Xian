package com.xian.focus

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/**
 * 在代码里取当前主题的属性色。
 *
 * 为什么要绕这一圈：本模块开了非传递 R 类，appcompat 的 `?attr/colorSecondary` 之类
 * 在 Kotlin 里引用不到，所以自定义主题属性（见 attrs.xml 的 colorBrand*）只能在布局里
 * 用 `?attr/...` 解析。代码里要拿同一个值，只能走 resolveAttribute。
 *
 * @param attrRes     主题属性，如 R.attr.colorBrandAccent
 * @param fallbackRes 属性缺失时的兜底颜色资源
 */
fun Context.themedColor(@AttrRes attrRes: Int, @ColorRes fallbackRes: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attrRes, value, true)) {
        value.data
    } else {
        ContextCompat.getColor(this, fallbackRes)
    }
}
