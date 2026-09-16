package com.xian.focus

import android.content.Context

/**
 * 灵感便贴的存储：SharedPreferences 里一条便签一组 key（正文 + 是否标星 + 删除时间）。
 *
 * 没上 Room —— 条数少、每次整份读整份写、不需要查询，
 * 为它加一张表要连带数据库迁移，代价不成比例。回收站同理，加一个 `deletedAt` 键即可。
 *
 * ⚠️ [load] 只返回**没删**的便贴（便贴页不该看见回收站里的东西），
 * 而 [save] 是"整份重写"，所以它必须自己把回收站里的那些捞回来一起写 ——
 * 否则便贴页每存一次盘，回收站就被清空了。
 */
object NoteStore {

    /** 一条便签。createdAt（毫秒时间戳）同时当 id 用，一张一条不会撞。 */
    data class Note(
        val createdAt: Long,
        var text: String,
        var starred: Boolean,
        /** 删除时间戳，0 表示没删。 */
        val deletedAt: Long = 0
    )

    private const val PREFS = "note_prefs"
    private const val KEY_IDS = "note_ids"
    private const val TEXT_PREFIX = "note_text_"
    private const val STAR_PREFIX = "note_star_"
    private const val DELETED_PREFIX = "note_deleted_"

    /** 便贴页用：不含回收站里的。 */
    fun load(context: Context): MutableList<Note> =
        loadAll(context).filter { it.deletedAt == 0L }.toMutableList()

    /** 回收站用：含已删的，按删除时间从新到旧。 */
    fun loadTrash(context: Context): List<Note> =
        loadAll(context).filter { it.deletedAt > 0L }.sortedByDescending { it.deletedAt }

    fun loadAll(context: Context): MutableList<Note> {
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
                starred = prefs.getBoolean(STAR_PREFIX + id, false),
                deletedAt = prefs.getLong(DELETED_PREFIX + id, 0L)
            )
        }.toMutableList()
    }

    /**
     * 整份重写：条数少，这样就不用跟踪哪些旧 key 该清。
     * 调用方（便贴页）传的是它手里的在册便贴，回收站里的那些在这里补回来。
     */
    fun save(context: Context, notes: List<Note>) {
        val trashed = loadAll(context).filter { it.deletedAt > 0L }
        writeAll(context, notes.filter { it.deletedAt == 0L } + trashed)
    }

    // ------------------------------------------------------------------ 回收站

    /** 把一张便贴移进回收站。 */
    fun moveToTrash(context: Context, createdAt: Long) {
        val all = loadAll(context)
        if (all.none { it.createdAt == createdAt && it.deletedAt == 0L }) return
        writeAll(
            context,
            all.map {
                if (it.createdAt == createdAt) it.copy(deletedAt = System.currentTimeMillis()) else it
            }
        )
    }

    fun restore(context: Context, createdAt: Long) {
        val all = loadAll(context)
        writeAll(context, all.map { if (it.createdAt == createdAt) it.copy(deletedAt = 0L) else it })
    }

    /** 彻底删除一张便贴。 */
    fun purge(context: Context, createdAt: Long) {
        writeAll(context, loadAll(context).filter { it.createdAt != createdAt })
    }

    /** 清空回收站里的便贴。 */
    fun purgeAllTrash(context: Context) {
        writeAll(context, loadAll(context).filter { it.deletedAt == 0L })
    }

    /** 回收站保留期到期清理。 */
    fun purgeExpired(context: Context, cutoff: Long) {
        val all = loadAll(context)
        val kept = all.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
        if (kept.size != all.size) writeAll(context, kept)
    }

    // ------------------------------------------------------------------ 内部

    private fun writeAll(context: Context, notes: List<Note>) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        edit.putString(KEY_IDS, notes.joinToString(",") { it.createdAt.toString() })
        notes.forEach { note ->
            edit.putString(TEXT_PREFIX + note.createdAt, note.text)
            edit.putBoolean(STAR_PREFIX + note.createdAt, note.starred)
            // 未删的就不写这个键，prefs 里少一堆 "=0"；读的时候按 0 兜底。
            if (note.deletedAt > 0L) {
                edit.putLong(DELETED_PREFIX + note.createdAt, note.deletedAt)
            }
        }
        edit.apply()
    }

    /** 翻页顺序：标星的置顶，组内按写下的先后（早的在前）。 */
    fun ordered(notes: List<Note>): List<Note> =
        notes.sortedWith(compareByDescending<Note> { it.starred }.thenBy { it.createdAt })
}
