package com.xian.focus.data

import android.content.Context
import com.xian.focus.AppLimitStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * CSV 恢复：解析「个人页 → 导出数据」产出的备份文件并写回数据库。
 *
 * 只做忠实还原，不做智能合并 —— 撞 id 的行一律跳过（见各 DAO 的 IGNORE 插入），
 * 所以把备份导进一台已经用过的手机会得到两份数据的并集，已有记录不会被覆盖。
 *
 * 注意：下面这些中文字面量是**备份文件格式的一部分**（小节名、列名、「是/否」标记），
 * 不随界面语言变化，也不该翻译 —— 一旦翻译，旧备份就再也导不回来了。
 */
object DataRestore {

    /** 恢复条数回执。 */
    data class Report(
        val tasks: Int,
        val subtasks: Int,
        val records: Int,
        val countdowns: Int,
        val diaries: Int,
        val limits: Int
    ) {
        val total: Int get() = tasks + subtasks + records + countdowns + diaries + limits
    }

    private const val SECTION_TASKS = "任务数据"
    private const val SECTION_SUBTASKS = "子任务"
    private const val SECTION_RECORDS = "专注记录"
    private const val SECTION_COUNTDOWNS = "倒数日"
    private const val SECTION_DIARY = "每日复盘（日记）"
    private const val SECTION_LIMITS = "应用限额"

    /** 导出时用的是 Locale.getDefault()；这里固定 US —— 模式串全是数字，两者等价，且不受导入端语言影响。 */
    private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 导出用的是中文「是/否」，这是文件格式的一部分，不随界面语言变化。 */
    private const val TRUE_MARK = "是"

    suspend fun restore(context: Context, repository: FocusRepository, text: String): Report {
        val sections = splitSections(text)

        val tasks = sections[SECTION_TASKS].orEmpty().mapNotNull(::parseTask)
        repository.restoreTasks(tasks)

        // 子任务对 tasks.id 有外键约束：父任务不在库里就跳过，
        // 否则 Room 会因外键失败整批报错（备份被手工删过行时容易踩到）。
        val knownTaskIds = repository.getAllTasks().mapTo(HashSet()) { it.id }
        val subtasks = sections[SECTION_SUBTASKS].orEmpty()
            .mapNotNull(::parseSubtask)
            .filter { it.taskId in knownTaskIds }
        repository.restoreSubtasks(subtasks)

        val records = sections[SECTION_RECORDS].orEmpty().mapNotNull(::parseRecord)
        repository.restoreRecords(records)

        val countdowns = sections[SECTION_COUNTDOWNS].orEmpty().mapNotNull(::parseCountdown)
        repository.restoreCountdowns(countdowns)

        val diaries = restoreDiary(context, sections[SECTION_DIARY].orEmpty())

        // 应用限额存在 prefs 里、没有 id 概念：按包名逐条写回，旧的直接覆盖
        val limits = restoreLimits(context, sections[SECTION_LIMITS].orEmpty())

        return Report(tasks.size, subtasks.size, records.size, countdowns.size, diaries, limits)
    }

    /** 恢复「应用限额」段：包名 + 每日分钟数。返回写回条数。 */
    private fun restoreLimits(context: Context, rows: List<List<String>>): Int {
        var count = 0
        rows.forEach { row ->
            if (row.size < 2) return@forEach
            val packageName = row[0].trim()
            val minutes = row[1].trim().toIntOrNull() ?: return@forEach
            if (packageName.isBlank() || minutes <= 0) return@forEach
            AppLimitStore.setLimit(context, packageName, minutes)
            count++
        }
        return count
    }

    // ------------------------------------------------------------------ 分段

    /** 按 `=== 小节名 ===` 切出各段数据行（跳过每段紧随的列名行）。 */
    private fun splitSections(text: String): Map<String, List<List<String>>> {
        val sections = LinkedHashMap<String, MutableList<List<String>>>()
        var current: MutableList<List<String>>? = null
        var expectingHeader = false
        parseCsv(text).forEach { row ->
            val head = row.singleOrNull()?.trim()
            if (head != null && head.startsWith("===")) {
                current = sections.getOrPut(head.trim('=').trim()) { mutableListOf() }
                expectingHeader = true
                return@forEach
            }
            val target = current ?: return@forEach
            if (expectingHeader) {
                expectingHeader = false
                return@forEach
            }
            if (row.any { it.isNotBlank() }) target.add(row)
        }
        return sections
    }

    /**
     * 最小 CSV 解析：支持引号包裹、字段内逗号/换行、`""` 转义。
     * 与导出端的转义规则严格对称。
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    row.add(cell.toString())
                    cell.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (cell.isNotEmpty() || row.isNotEmpty()) {
                        row.add(cell.toString())
                        rows.add(row)
                        row = mutableListOf()
                        cell.clear()
                    }
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    // ------------------------------------------------------------------ 各表

    private fun parseTask(row: List<String>): Task? {
        if (row.size < 12) return null
        val id = row[0].trim().toIntOrNull() ?: return null
        val title = row[1]
        if (title.isBlank()) return null
        val createdAt = parseTime(row[9]) ?: System.currentTimeMillis()
        return Task(
            id = id,
            title = title,
            description = row[2].ifBlank { null },
            priority = row[3].trim().toIntOrNull() ?: 2,
            estimatedPomodoros = row[4].trim().toIntOrNull() ?: 1,
            completedPomodoros = row[5].trim().toIntOrNull() ?: 0,
            isCompleted = row[6].trim() == TRUE_MARK,
            dueDate = parseTime(row[7]),
            dueTimeMinutes = parseMinuteOfDay(row[8]),
            createdAt = createdAt,
            // 导出没有这两列，按「与原顺序一致」还原。
            sortOrder = createdAt,
            imageUri = null,
            listType = row[10].ifBlank { "未分类" },
            repeatRule = row[11].ifBlank { "none" }
        )
    }

    private fun parseSubtask(row: List<String>): Subtask? {
        if (row.size < 4) return null
        val id = row[0].trim().toIntOrNull() ?: return null
        val taskId = row[1].trim().toIntOrNull() ?: return null
        val title = row[2]
        if (title.isBlank()) return null
        return Subtask(
            id = id,
            taskId = taskId,
            title = title,
            isCompleted = row[3].trim() == TRUE_MARK
        )
    }

    private fun parseRecord(row: List<String>): PomodoroRecord? {
        if (row.size < 7) return null
        val id = row[0].trim().toIntOrNull() ?: return null
        val start = parseTime(row[2]) ?: return null
        val end = parseTime(row[3]) ?: return null
        return PomodoroRecord(
            id = id,
            taskId = row[1].trim().toIntOrNull()?.takeIf { it > 0 },
            startTime = start,
            endTime = end,
            type = row[4].ifBlank { "focus" },
            isFinished = row[5].trim() == TRUE_MARK
        )
    }

    private fun parseCountdown(row: List<String>): Countdown? {
        if (row.size < 6) return null
        val id = row[0].trim().toIntOrNull() ?: return null
        val title = row[1]
        if (title.isBlank()) return null
        val targetDate = parseTime(row[2]) ?: return null
        return Countdown(
            id = id,
            title = title,
            targetDate = targetDate,
            repeatYearly = row[3].trim() == TRUE_MARK,
            note = row[4],
            createdAt = parseTime(row[5]) ?: System.currentTimeMillis()
        )
    }

    /** 日记存在 SharedPreferences 里；导出只有图片数量没有路径，所以图片无法还原。 */
    private fun restoreDiary(context: Context, rows: List<List<String>>): Int {
        val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var count = 0
        rows.forEach { row ->
            val date = row.getOrNull(0)?.trim().orEmpty()
            if (date.isEmpty()) return@forEach
            editor.putString("note_$date", row.getOrNull(3).orEmpty())
            editor.putInt("rating_$date", row.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            count++
        }
        editor.apply()
        return count
    }

    // ------------------------------------------------------------------ 字段

    private fun parseTime(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return runCatching { TIME_FORMAT.parse(value)?.time }.getOrNull()
    }

    /** 导出格式为 `时:分`（如 `9:0`），转成当天分钟数。 */
    private fun parseMinuteOfDay(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return (hour * 60 + minute).takeIf { it in 0 until 24 * 60 }
    }
}
