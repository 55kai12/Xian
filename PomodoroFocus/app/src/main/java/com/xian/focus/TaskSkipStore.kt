package com.xian.focus

import android.content.Context

/**
 * 重复任务「仅删除该事件」的跳过记录。
 *
 * 数据模型里模板行（templateId == 0 且 repeatRule != none）代表「这个任务每天都出现」，
 * 没有任何字段能表达「唯独某一天不要出现」；而为了这一个动作去改 Room 表结构
 * （加排除表或跳过期字段）要连带迁移，代价不成比例。所以单独记一份 skip 集合：
 * 元素形如 `<模板id>@<当天 0 点时间戳>`，在构建当天实例时过滤掉。
 *
 * 只增不删：一天至多几条、每条几十字节，不值得再引入一个清理任务。
 */
object TaskSkipStore {

    private const val PREFS = "task_skip"
    private const val KEY = "skips"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读快照。构建一天的任务实例前取一次即可，别在循环里反复读。 */
    fun all(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    /** 记一条「这天不要出现」。getStringSet 返回的集合不可改，必须新建。 */
    fun skip(context: Context, templateId: Int, day: Long) {
        val p = prefs(context)
        p.edit().putStringSet(KEY, p.getStringSet(KEY, emptySet()).orEmpty() + key(templateId, day)).apply()
    }

    /** 与 buildOccurrencesForDay 里比对用的键，两侧必须一致。 */
    fun key(templateId: Int, day: Long) = "$templateId@$day"
}
