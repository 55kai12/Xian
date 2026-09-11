package com.xian.focus

import android.content.Context

object WallpaperStore {
    private const val PREFS_NAME = "wallpaper_prefs"
    private const val KEY_PATH = "path"

    fun getPath(context: Context): String? =
        prefs(context).getString(KEY_PATH, null)

    fun setPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_PATH, path).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PATH).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
