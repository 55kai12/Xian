package com.xian.focus

import android.content.Context

/**
 * 灵感便贴的存储：SharedPreferences 里一条便签一组 key（正文 + 是否标星）。
 *
 * 没上 Room —— 条数少、每次整份读整份写、不需要查询，
 * 为它加一张表要连带数据库迁移，代价不成比例。
 */
object NoteStore {

    /** 一条便签。createdAt（毫秒时间戳）同时当 id 用，一张一条不会撞。 */
    data class Note(val createdAt: Long, var text: String, var starred: Boolean)

    private const val PREFS = "note_prefs"
    private const val KEY_IDS = "note_ids"
    private const val TEXT_PREFIX = "note_text_"
    private const val STAR_PREFIX = "note_star_"

    fun load(context: Context): MutableList<Note> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString(KEY_IDS, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: return mutableListOf()
        return ids.mapNotNull { id ->
            val createdAt = id.toLongOrNull() ?: return@mapNotNull null
            Note(
                createdAt = createdAt,
                text = prefs.getString(TEXT_PREFIX + id, "").orEmpty(),
                starred = prefs.getBoolean(STAR_PREFIX + id, false)
            )
        }.toMutableList()
    }

    /** 整份重写：条数少，这样就不用跟踪哪些旧 key 该清。 */
    fun save(context: Context, notes: List<Note>) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        edit.putString(KEY_IDS, notes.joinToString(",") { it.createdAt.toString() })
        notes.forEach { note ->
            edit.putString(TEXT_PREFIX + note.createdAt, note.text)
            edit.putBoolean(STAR_PREFIX + note.createdAt, note.starred)
        }
        edit.apply()
    }

    /** 翻页顺序：标星的置顶，组内按写下的先后（早的在前）。 */
    fun ordered(notes: List<Note>): List<Note> =
        notes.sortedWith(compareByDescending<Note> { it.starred }.thenBy { it.createdAt })
}
