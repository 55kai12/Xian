package com.xian.focus

import android.content.Context
import android.graphics.Color
import kotlin.math.abs

object GroupColorStore {
    private const val PREFS_NAME = "group_color_prefs"
    private const val KEY_PREFIX = "color_"

    val palette = listOf(
        Color.parseColor("#C9A961"),
        Color.parseColor("#5B8A72"),
        Color.parseColor("#A67C52"),
        Color.parseColor("#4A5B7A"),
        Color.parseColor("#B05A4A"),
        Color.parseColor("#7A8B5A")
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun colorFor(context: Context, group: String): Int {
        val key = KEY_PREFIX + group
        val prefs = prefs(context)
        if (prefs.contains(key)) return prefs.getInt(key, palette[0])
        return palette[abs(group.hashCode()) % palette.size]
    }

    fun setColor(context: Context, group: String, color: Int) {
        prefs(context).edit().putInt(KEY_PREFIX + group, color).apply()
    }
}
