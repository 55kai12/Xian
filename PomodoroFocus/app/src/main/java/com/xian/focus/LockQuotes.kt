package com.xian.focus

import android.content.Context
import android.view.View
import android.widget.TextView

/**
 * 锁机时显示在时间下方的励志文案。
 *
 * 存成**一整段多行文本**（`\n` 分隔）而不是 StringSet：StringSet 不保序，
 * 而用户在编辑框里看到的顺序就是他排的顺序 —— 存成无序集合，编辑一次顺序就乱一次。
 *
 * 挂在 `lock_machine_prefs`（锁机设置那一份）上，同 [LockPin]：少一个 prefs 文件，
 * 少一处清数据/漏备份的地方；这份数据同样不参与备份导出。
 */
object LockQuotes {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_TEXT = "quotes_text"

    /** 首次的示例文案。用户没动过编辑框时就摆这一份，不至于一进去是空白。 */
    private val DEFAULTS = listOf(
        "此刻的克制，是明天的自由。",
        "你不需要很厉害才能开始，你需要开始才会很厉害。",
        "专注做一件事，比同时抓十件更接近终点。",
        "再撑一会儿，这一关就过去了。"
    )

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 存着的那段原文（保留用户自己排的换行）。没写过就是示例。 */
    private fun rawText(context: Context): String =
        prefs(context).getString(KEY_TEXT, null) ?: defaultsText()

    private fun defaultsText(): String = DEFAULTS.joinToString("\n")

    /** 「恢复默认」用：把示例一条条摆回编辑框里（只是摆回去，落盘要等用户点保存）。 */
    fun defaultList(): List<String> = DEFAULTS

    fun save(context: Context, text: String) {
        prefs(context).edit().putString(KEY_TEXT, text).apply()
    }

    /** 展示用：去掉空行与首尾空白 —— 用户随手多敲几个回车，锁机时不该留一片白。 */
    fun list(context: Context): List<String> =
        rawText(context).split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 随机取一条铺到锁机层的文案位上；一条都没有就把这一行收起来。
     * 两个锁机层（定时 / 番茄钟专注）共用，靠的就是它们布局里同名的 `lockQuoteText`。
     *
     * 只在新盖上那一层时调一次：放进每秒 tick 或每次事件刷新里，文案会一秒一跳。
     */
    fun applyTo(view: View, context: Context) {
        val quoteView = view.findViewById<TextView>(R.id.lockQuoteText)
        val quote = list(context).randomOrNull()
        quoteView.text = quote.orEmpty()
        quoteView.visibility = if (quote == null) View.GONE else View.VISIBLE
    }
}
