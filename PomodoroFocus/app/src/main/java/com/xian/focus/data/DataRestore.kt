package com.xian.focus.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.xian.focus.AppLimitStore
import com.xian.focus.LockMachineController
import com.xian.focus.LockMachineScheduler
import com.xian.focus.LockQuotes
import com.xian.focus.LockStats
import com.xian.focus.NoteStore
import com.xian.focus.TaskSkipStore
import com.xian.focus.ThemeStore
import com.xian.focus.WallpaperStore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 备份恢复：解析「个人页 → 导出数据」产出的文件并写回数据库。
 *
 * 接受三种文件，靠**内容**判断而不是 MIME（各家文件管理器对 zip / csv 的登记并不统一）：
 * - **新版 zip**（当前的格式，见 [DataBackup]）：包里一表一个 csv。先解出 `images/…` 与
 *   `wallpaper.jpg`，把图片列里的条目名换成本地 `file://` URI，再逐张表交给 [applySheets]；
 * - **旧版 zip**（只有一个 `data.csv`，七个小节挤在一起）：交给 [apply]，行为与以前完全一致；
 * - **裸 CSV**（更早的版本直接导出的）：同样按多小节格式解析，只是没有图片可还原。
 * 前两者靠文件头（`PK\x03\x04`）区分，绝不会把新版误当旧版读。
 *
 * 只做忠实还原，不做智能合并 —— 撞 id 的行一律跳过（见各 DAO 的 IGNORE 插入），
 * 所以把备份导进一台已经用过的手机会得到两份数据的并集，已有记录不会被覆盖。
 *
 * ⚠️ 新格式一律按**列的位置**读，不认表头文字。所以：
 * - 改动 [DataBackup] 里任何一张表的列序，必须同步改这边的 `parseSheet*`；
 * - 旧格式那些中文字面量（小节名、「是/否」）仍然是文件格式的一部分，不能翻译 ——
 *   翻一次，已经发出去的老备份就再也导不回来了。
 *
 * **唯一的例外是 `settings.csv`**：键值表按第一列的**名字**认（见 [restoreSheetSettings]）。
 * 名字驱动对键值表才是对的 —— 打错一个字只丢那一项设置，而按位置读一旦错位就是把整张表读花。
 */
object DataRestore {

    /** 恢复条数回执。 */
    data class Report(
        val tasks: Int,
        val subtasks: Int,
        val records: Int,
        val countdowns: Int,
        val habits: Int,
        val habitLogs: Int,
        val diaries: Int,
        val limits: Int,
        val notes: Int,
        val images: Int = 0,
        /** 壁纸有没有被这份备份换掉 —— 换掉了才需要重建 Activity 让窗口背景重新铺。 */
        val wallpaperRestored: Boolean = false,
        /** 认下来的设置项数（settings.csv 的行数）。 */
        val settings: Int = 0,
        /**
         * 主题有没有被改。主题是 Activity `setTheme` 时才生效的，
         * 不重建 Activity 的话界面还停在旧配色上，看起来就像这项设置没导进来。
         */
        val themeRestored: Boolean = false
    ) {
        val total: Int
            get() = tasks + subtasks + records + countdowns + habits + habitLogs +
                diaries + limits + notes + images + settings
    }

    // 小节名同时被 DataBackup 写入端引用 —— 两边共用同一份常量，改一边不会悄悄写错另一边。
    internal const val SECTION_TASKS = "任务数据"
    internal const val SECTION_SUBTASKS = "子任务"
    internal const val SECTION_RECORDS = "专注记录"
    internal const val SECTION_COUNTDOWNS = "倒数日"
    internal const val SECTION_DIARY = "每日复盘（日记）"
    internal const val SECTION_LIMITS = "应用限额"
    internal const val SECTION_NOTES = "灵感便贴"

    /** 导出时用的是 Locale.getDefault()；这里固定 US —— 模式串全是数字，两者等价，且不受导入端语言影响。 */
    private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 导出用的是中文「是/否」，这是文件格式的一部分，不随界面语言变化。 */
    private const val TRUE_MARK = "是"

    /** 与 Countdown 的默认值保持一致，用于老备份缺这一列时兜底。 */
    private val DEFAULT_COUNTDOWN_COLOR = 0xFF3B5B4E.toInt()

    /**
     * 从备份文件流还原。[input] 由本方法关闭，判断是 zip 还是裸 CSV 后分别处理。
     */
    suspend fun restore(context: Context, repository: FocusRepository, input: InputStream): Report {
        // 先落到缓存文件：zip 需要随机访问（ZipFile），而且这样能先看一眼文件头。
        val temp = File(context.cacheDir, "xian_import.tmp")
        var text = ""
        /** 新版布局（一表一文件）的表格内容；为 null 说明这是旧版单文件备份，交给 [apply]。 */
        var sheets: Map<String, String>? = null
        var images = Images()
        try {
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            if (isZip(temp)) {
                ZipFile(temp).use { zip ->
                    val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                    if (entries.any { it.name == DataBackup.TASKS_ENTRY }) {
                        // 新版：拣出认得的几张表。names 里没有的（用户手工删了某张表）直接跳过，
                        // 少一张表只少一类数据，不该让整份备份导入失败。
                        images = unpackImages(context, zip)
                        sheets = entries
                            .filter { it.name in DataBackup.SHEET_ENTRIES }
                            .associate { entry ->
                                entry.name to zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                            }
                    } else {
                        // 旧版 zip：整个备份就一个 csv（data.csv，七个小节挤在一起）
                        val csv = entries.firstOrNull { it.name.endsWith(".csv", ignoreCase = true) }
                        // 是个 zip 但里面没有备份表格（比如误选了相册压缩包）→ text 保持空串
                        if (csv != null) {
                            images = unpackImages(context, zip)
                            text = zip.getInputStream(csv).use { it.readBytes().toString(Charsets.UTF_8) }
                        }
                    }
                }
            } else {
                // 更老的版本直接导出的裸 csv，同样是多小节格式
                text = temp.readText(Charsets.UTF_8)
            }
        } finally {
            temp.delete()
        }
        val loaded = sheets
        return if (loaded != null) {
            applySheets(context, repository, loaded, images)
        } else {
            apply(context, repository, text, images)
        }
    }

    private suspend fun apply(
        context: Context,
        repository: FocusRepository,
        rawText: String,
        images: Images
    ): Report {
        // 导出端特意写了 UTF-8 BOM 给 Excel 用，而 BOM **不是空白字符** —— String.trim() 去不掉它。
        // 不在这儿摘掉的话，首行 `␁=== 任务数据 ===` 认不出小节标题，
        // 整个「任务数据」段会被静默丢弃（导入提示成功但任务一条没进来）。
        val text = rawText.removePrefix("\uFEFF")
        val sections = splitSections(text)

        val tasks = sections[SECTION_TASKS].orEmpty().mapNotNull { parseTask(it, images) }
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

        val diaries = restoreDiary(context, sections[SECTION_DIARY].orEmpty(), images)

        // 应用限额存在 prefs 里、没有 id 概念：按包名逐条写回，旧的直接覆盖
        val limits = restoreLimits(context, sections[SECTION_LIMITS].orEmpty())

        // 便贴同样只在 prefs 里，按创建时间去重合并
        val notes = restoreNotes(context, sections[SECTION_NOTES].orEmpty())

        // 壁纸备份里带了就一并还原（没带则保持用户当前的壁纸不动）
        val wallpaperRestored = images.wallpaper != null
        images.wallpaper?.let { WallpaperStore.setPath(context, it.absolutePath) }

        return Report(
            tasks.size,
            subtasks.size,
            records.size,
            countdowns.size,
            // 旧格式（单文件 data.csv，v2.0.66 之前）里没有习惯，这两项恒为 0
            0,
            0,
            diaries,
            limits,
            notes,
            images.size,
            wallpaperRestored
        )
    }

    // ------------------------------------------------------------------ 新版布局（一表一文件）

    /**
     * 新版备份：**每张表一个 csv**，逐张解析写回。
     *
     * 顺序不能换 —— 子任务、专注记录、习惯打卡里的「编号」都是指向另一张表的外键，
     * 反过来写会被外键约束整批挡掉（习惯日志会被上层按「习惯在不在」滤掉）。
     */
    private suspend fun applySheets(
        context: Context,
        repository: FocusRepository,
        sheets: Map<String, String>,
        images: Images
    ): Report {
        val tasks = sheetRows(sheets, DataBackup.TASKS_ENTRY).mapNotNull { parseSheetTask(it, images) }
        repository.restoreTasks(tasks)

        val knownTaskIds = repository.getAllTasks().mapTo(HashSet()) { it.id }
        val subtasks = sheetRows(sheets, DataBackup.SUBTASKS_ENTRY)
            .mapNotNull(::parseSheetSubtask)
            .filter { it.taskId in knownTaskIds }
        repository.restoreSubtasks(subtasks)

        val records = sheetRows(sheets, DataBackup.RECORDS_ENTRY).mapNotNull(::parseSheetRecord)
        repository.restoreRecords(records)

        // 倒数日不导出排序值，靠行序还原 —— 导出时就是按排序查出来的。
        val countdowns = sheetRows(sheets, DataBackup.COUNTDOWNS_ENTRY)
            .mapIndexedNotNull { index, row -> parseSheetCountdown(row, index) }
        repository.restoreCountdowns(countdowns)

        // 习惯先落库，再写打卡记录 —— 「习惯编号」是指向 habits 的引用，
        // 反过来写会插出一批认不出主人的日志行（同子任务、专注记录的道理）。
        val habits = sheetRows(sheets, DataBackup.HABITS_ENTRY).mapNotNull(::parseSheetHabit)
        repository.restoreHabits(habits)
        val knownHabitIds = repository.getHabits().mapTo(HashSet()) { it.id }
        val habitLogs = sheetRows(sheets, DataBackup.HABIT_LOGS_ENTRY)
            .mapNotNull(::parseSheetHabitLog)
            .filter { it.habitId in knownHabitIds }
        habitLogs.forEach { repository.restoreHabitLog(it) }

        val diaries = restoreSheetDiary(context, sheetRows(sheets, DataBackup.DIARY_ENTRY), images)
        val limits = restoreSheetLimits(context, sheetRows(sheets, DataBackup.LIMITS_ENTRY))
        val notes = restoreSheetNotes(context, sheetRows(sheets, DataBackup.NOTES_ENTRY))
        // 设置放最后：里面的「跳过标记」指向任务编号，得等任务都进库了才对得上
        val settings = restoreSheetSettings(context, sheetRows(sheets, DataBackup.SETTINGS_ENTRY))

        val wallpaperRestored = images.wallpaper != null
        images.wallpaper?.let { WallpaperStore.setPath(context, it.absolutePath) }

        return Report(
            tasks.size,
            subtasks.size,
            records.size,
            countdowns.size,
            habits.size,
            habitLogs.size,
            diaries,
            limits,
            notes,
            images.size,
            wallpaperRestored,
            settings.count,
            settings.themeChanged
        )
    }

    /**
     * 取某张表的**数据行**（丢掉头一行的表头）。
     *
     * 表头只写给人看，解析一律按**列的位置**读 —— 中文表头被打错一个字就整列读不到，
     * 而列的位置才是写入端和解析端之间真正的契约。
     */
    private fun sheetRows(sheets: Map<String, String>, entry: String): List<List<String>> {
        val text = sheets[entry] ?: return emptyList()
        // 与旧格式同理：BOM 不是空白字符，trim 不掉，会挂在第一个字段上
        return parseCsv(text.removePrefix("\uFEFF"))
            .drop(1)
            .filter { row -> row.any { it.isNotBlank() } }
    }

    /** tasks.csv：标题,备注,优先级,分类,截止日期,截止时间,预计贤时,已完成贤时,状态,创建时间,重复,插图,重复系列编号,排序,编号 */
    private fun parseSheetTask(row: List<String>, images: Images): Task? {
        if (row.size < 15) return null
        val id = row[14].trim().toIntOrNull() ?: return null
        val title = row[0]
        if (title.isBlank()) return null
        val createdAt = parseSheetTime(row[9]) ?: System.currentTimeMillis()
        return Task(
            id = id,
            title = title,
            description = row[1].ifBlank { null },
            priority = priorityOf(row[2]),
            listType = row[3].ifBlank { "未分类" },
            dueDate = parseSheetDay(row[4]),
            dueTimeMinutes = parseMinuteOfDay(row[5]),
            estimatedPomodoros = row[6].trim().toIntOrNull() ?: 1,
            completedPomodoros = row[7].trim().toIntOrNull() ?: 0,
            isCompleted = doneOf(row[8]),
            createdAt = createdAt,
            repeatRule = repeatOf(row[10]),
            imageUri = images.uri(row[11]),
            templateId = row[12].trim().toIntOrNull() ?: 0,
            // 排序丢了整个列表顺序就回默认，用户拖出来的顺序会白拖
            sortOrder = row[13].trim().toLongOrNull() ?: createdAt
        )
    }

    /** subtasks.csv：所属任务编号,标题,状态,编号 */
    private fun parseSheetSubtask(row: List<String>): Subtask? {
        if (row.size < 4) return null
        val id = row[3].trim().toIntOrNull() ?: return null
        val taskId = row[0].trim().toIntOrNull() ?: return null
        val title = row[1]
        if (title.isBlank()) return null
        return Subtask(id = id, taskId = taskId, title = title, isCompleted = doneOf(row[2]))
    }

    /** records.csv：任务编号,开始时间,结束时间,时长(分钟),类型,状态,编号 */
    private fun parseSheetRecord(row: List<String>): PomodoroRecord? {
        if (row.size < 7) return null
        val id = row[6].trim().toIntOrNull() ?: return null
        val start = parseSheetTime(row[1]) ?: return null
        val end = parseSheetTime(row[2]) ?: return null
        return PomodoroRecord(
            id = id,
            taskId = row[0].trim().toIntOrNull()?.takeIf { it > 0 },
            startTime = start,
            endTime = end,
            type = recordTypeOf(row[4]),
            isFinished = doneOf(row[5])
        )
    }

    /** countdowns.csv：标题,目标日期,每年重复,备注,颜色,创建时间,关联任务编号,编号 */
    private fun parseSheetCountdown(row: List<String>, index: Int): Countdown? {
        if (row.size < 8) return null
        val id = row[7].trim().toIntOrNull() ?: return null
        val title = row[0]
        if (title.isBlank()) return null
        val targetDate = parseSheetDay(row[1]) ?: return null
        return Countdown(
            id = id,
            title = title,
            targetDate = targetDate,
            repeatYearly = yesOf(row[2]),
            note = row[3],
            color = colorOf(row[4]),
            createdAt = parseSheetTime(row[5]) ?: System.currentTimeMillis(),
            linkedTaskId = row[6].trim().toIntOrNull() ?: 0,
            // 文件里没有排序列，用行序还原（导出就是按排序查出来的）
            sortOrder = index
        )
    }

    /**
     * habits.csv：名称,频率,每周目标,指定星期,每日目标,提醒时间,颜色,开始日期,排序,编号
     *
     * 「频率」只认「每天 / 每周 / 指定星期」这类人话，也容忍旧写法
     * （`daily` / `每周 3 次`）—— 文件被 Excel 编辑过之后不至于整列失效。
     * 「指定星期」那一格写的是 `周一、周三、周五`，认不出来就当空掩码。
     */
    private fun parseSheetHabit(row: List<String>): Habit? {
        if (row.size < 10) return null
        val id = row[9].trim().toIntOrNull() ?: return null
        val name = row[0]
        if (name.isBlank()) return null
        val freqCell = row[1]
        val weekDaysMaskCell = row[3]
        val freq = when {
            freqCell.contains("指定") || freqCell.trim().equals("custom", ignoreCase = true) -> Habit.FREQ_CUSTOM
            freqCell.contains("周") || freqCell.trim().equals("weekly", ignoreCase = true) -> Habit.FREQ_WEEKLY
            else -> Habit.FREQ_DAILY
        }
        // 「每周目标」优先；那一格空着（比如被人手工删了列）就从「每周 3 次」里捞数字
        val weeklyTarget = row[2].trim().toIntOrNull()?.takeIf { it > 0 }
            ?: Regex("\\d+").find(freqCell)?.value?.toIntOrNull()
            ?: 0
        return Habit(
            id = id,
            name = name,
            freqType = freq,
            weeklyTarget = if (freq == Habit.FREQ_WEEKLY) weeklyTarget else 0,
            weekDaysMask = if (freq == Habit.FREQ_CUSTOM) weekDayMask(weekDaysMaskCell) else 0,
            targetPerDay = row[4].trim().toIntOrNull()?.coerceAtLeast(1) ?: 1,
            remindMinutes = parseMinuteOfDay(row[5]) ?: Habit.NO_REMIND,
            color = colorOf(row[6]),
            startDate = parseSheetDay(row[7]) ?: 0L,
            sortOrder = row[8].trim().toIntOrNull() ?: 0
        )
    }

    /** habit_logs.csv：习惯编号,打卡日期,次数。「习惯编号」指向 habits.csv 的「编号」。 */
    private fun parseSheetHabitLog(row: List<String>): HabitLog? {
        if (row.size < 3) return null
        val habitId = row[0].trim().toIntOrNull() ?: return null
        val day = parseSheetDay(row[1]) ?: return null
        val count = row[2].trim().toIntOrNull() ?: 1
        if (count <= 0) return null
        return HabitLog(habitId = habitId, day = day, count = count)
    }

    /**
     * `周一、周三、周五` → 星期掩码。分隔符认顿号/逗号/斜杠/空格，
     * 也认单字（`一、三、五`）和英文短名 —— 这张表是给人看的，写法放开一点不吃亏。
     * 一个都没认出来就返回 0（该习惯变成「哪一天都不打卡」，比乱猜一个更诚实）。
     */
    private fun weekDayMask(text: String): Int {
        val value = text.trim()
        if (value.isEmpty()) return 0
        var mask = 0
        DataBackup.WEEK_DAY_NAMES.forEachIndexed { index, full ->
            val short = full.removePrefix("周")
            val hit = value.contains(full) ||
                Regex("(^|[、,，/\\s])${Regex.escape(short)}([、,，/\\s]|$)").containsMatchIn(value)
            if (hit) mask = mask or (1 shl index)
        }
        return mask
    }

    /** diary.csv：日期,评分,内容,图片 */
    private fun restoreSheetDiary(context: Context, rows: List<List<String>>, images: Images): Int {
        val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var count = 0
        rows.forEach { row ->
            val date = row.getOrNull(0)?.trim().orEmpty()
            if (date.isEmpty()) return@forEach
            editor.putString("note_$date", row.getOrNull(2).orEmpty())
            editor.putInt("rating_$date", row.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            // 与旧格式同理：只有这份备份真的带图时才写图片列表，
            // 无条件写空串会把用户现有的配图抹掉。
            val restored = images.uriList(row.getOrNull(3))
            if (restored.isNotEmpty()) editor.putString("images_$date", restored)
            count++
        }
        editor.apply()
        return count
    }

    /** limits.csv：应用,包名,每日限额(分钟)。认的是包名，「应用」列纯给人看。 */
    private fun restoreSheetLimits(context: Context, rows: List<List<String>>): Int {
        var count = 0
        rows.forEach { row ->
            val packageName = row.getOrNull(1)?.trim().orEmpty()
            val minutes = row.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            if (packageName.isBlank() || minutes <= 0) return@forEach
            AppLimitStore.setLimit(context, packageName, minutes)
            count++
        }
        return count
    }

    /** notes.csv：内容,标星,时间,编号。「编号」是毫秒原值同时也是便签 id，去重只能靠它。 */
    private fun restoreSheetNotes(context: Context, rows: List<List<String>>): Int {
        val existing = NoteStore.load(context)
        val knownIds = existing.mapTo(HashSet()) { it.createdAt }
        val imported = rows.mapNotNull { row ->
            val createdAt = row.getOrNull(3)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            if (createdAt in knownIds) return@mapNotNull null
            NoteStore.Note(
                createdAt = createdAt,
                text = row.getOrNull(0).orEmpty(),
                starred = yesOf(row.getOrNull(1).orEmpty())
            )
        }
        if (imported.isEmpty()) return 0
        NoteStore.save(context, existing + imported)
        return imported.size
    }

    // ------------------------------------------------------------------ 设置表

    /** settings.csv 的还原结果：认下来的项数 + 主题有没有变（变了调用方要重建 Activity）。 */
    private data class SettingsRestore(val count: Int, val themeChanged: Boolean)

    /**
     * settings.csv：设置,值,说明。**唯一按名字认、不按列位置认的一张表。**
     *
     * 键值表的名字就是契约：打错一个字只丢那一项设置；按位置读一旦错位却是把整张表读花。
     * 两端的名字一一对应，漏认一个会被 `_tmp_cmp/format_check.py` 抓出来。
     *
     * 空值与数字 0 一律跳过：「这份备份里没有这一项」跟「用户新机上已经设好了」不该互相覆盖，
     * 与日记图片「只有备份真的带图才写」是同一条原则。
     *
     * 刻意**不认**的三样：
     * - **两个 PIN 的哈希**（锁机退出密码 / 打开「贤」的密码）—— 4 位数字的 SHA-256 十秒就能爆破，
     *   备份文件流到谁手里就等于把密码给了谁；`LockPin` 的注释本来就写明这份数据不参与导出；
     * - **退出锁机的月度额度**（`LockExitQuota`）—— 导回来等于白送两次提前退出，
     *   跟它「扣减点唯一」的设计正面冲突；
     * - **应用限额的当日用量**（`AppLimitStore`）—— 本来就当天有效，导了也没意义。
     */
    private fun restoreSheetSettings(context: Context, rows: List<List<String>>): SettingsRestore {
        val timer = TimerSettingsPreferences(context)
        var count = 0
        var themeChanged = false
        // 三个时长是一次性写回的（saveDurations 收的是整份），先攒着，循环外统一落盘
        var durations: TimerDurations? = null

        rows.forEach { row ->
            val name = row.getOrNull(0)?.trim().orEmpty()
            // 格言那一项的值里带换行，所以只去首尾空白，别把中间的行也拆了
            val value = row.getOrNull(1).orEmpty().trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach

            when (name) {
                "主题" -> {
                    val index = DataBackup.THEME_NAMES.indexOf(value)
                    if (index >= 0) {
                        if (ThemeStore.selectedTheme(context) != index) {
                            ThemeStore.setSelectedTheme(context, index)
                            themeChanged = true
                        }
                        count++
                    }
                }

                "每日目标(贤时)" -> positive(value)?.let {
                    DailyGoalPreferences(context).setDailyGoal(it)
                    count++
                }

                "专注时长(分钟)" -> positive(value)?.let {
                    durations = (durations ?: timer.getDurations()).copy(focusMinutes = it)
                    count++
                }

                "短休时长(分钟)" -> positive(value)?.let {
                    durations = (durations ?: timer.getDurations()).copy(shortBreakMinutes = it)
                    count++
                }

                "长休时长(分钟)" -> positive(value)?.let {
                    durations = (durations ?: timer.getDurations()).copy(longBreakMinutes = it)
                    count++
                }

                "锁机开关" -> switchOf(value)?.let {
                    timer.saveLockEnabled(it)
                    count++
                }

                "定时锁机时段" -> {
                    // 一段都认不出来就当这份备份没带这项，不能把用户现有设好的时段清空
                    val slots = value.split(DataBackup.ITEM_SEPARATOR).mapNotNull(::parseSlot)
                    if (slots.isNotEmpty()) {
                        // schedule 内部已经包含「重排闹钟 + 拉起守护服务」两步
                        LockMachineScheduler.schedule(context, slots)
                        count++
                    }
                }

                "锁机白名单" -> {
                    val packages = packageSet(value)
                    if (packages.isNotEmpty()) {
                        LockMachineController.saveWhitelist(context, packages)
                        count++
                    }
                }

                "锁机自定义时长(分钟)" -> value.trim().toIntOrNull()?.takeIf { it > 0 }?.let {
                    LockMachineController.saveCustomMinutes(context, it)
                    count++
                }

                "锁机格言" -> {
                    LockQuotes.save(context, value)
                    count++
                }

                // 统计那四项各自从当前值 copy 一项写回：与行序无关，文件被手工调换顺序也不会写串
                "锁机统计月份" -> monthOf(value)?.let {
                    LockStats.restore(context, LockStats.stored(context).copy(month = it))
                    count++
                }

                "锁机次数" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let {
                    LockStats.restore(context, LockStats.stored(context).copy(sessions = it))
                    count++
                }

                "锁机时长(分钟)" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let {
                    LockStats.restore(context, LockStats.stored(context).copy(minutes = it))
                    count++
                }

                "锁机坚持次数" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let {
                    LockStats.restore(context, LockStats.stored(context).copy(held = it))
                    count++
                }

                "祈福次数" -> positive(value)?.let {
                    fortune(context).edit().putInt(DataBackup.FORTUNE_COUNT, it).apply()
                    count++
                }

                "最近抽签日期" -> {
                    fortune(context).edit().putString(DataBackup.FORTUNE_DATE, value).apply()
                    count++
                }

                "最近签" -> {
                    fortune(context).edit().putString(DataBackup.FORTUNE_NAME, value).apply()
                    count++
                }

                "最近签释义" -> {
                    fortune(context).edit().putString(DataBackup.FORTUNE_MEANING, value).apply()
                    count++
                }

                "跳过标记" -> {
                    var restored = 0
                    value.split(DataBackup.ITEM_SEPARATOR).forEach entry@{ entry ->
                        val templateId = entry.substringBefore('@').trim().toIntOrNull()
                            ?: return@entry
                        val day = parseSheetDay(entry.substringAfter('@', "")) ?: return@entry
                        // 只增不删：重复任务模板的「本次跳过」，日期与 TasksFragment 的当天基准一致
                        TaskSkipStore.skip(context, templateId, day)
                        restored++
                    }
                    if (restored > 0) count++
                }

                "显示已完成任务" -> if (putSwitch(events(context), "show_completed", value)) count++
                "完成划横线" -> if (putSwitch(events(context), "show_strikethrough", value)) count++
                "显示任务序号" -> if (putSwitch(events(context), "show_order_number", value)) count++
                "显示备注" -> if (putSwitch(events(context), "show_note", value)) count++
                "显示节假日标记" -> if (putSwitch(events(context), "show_holiday", value)) count++
                "倒数日自动建任务" -> if (putSwitch(events(context), "auto_link_task", value)) count++
                "倒数日提醒" -> if (putSwitch(events(context), "reminder_enabled", value)) count++

                "倒数日排序" -> DataBackup.SORT_BY_KEYS
                    .getOrNull(DataBackup.SORT_BY_TEXTS.indexOf(value))?.let {
                        events(context).edit().putString("sort_by", it).apply()
                        count++
                    }

                "提前提醒天数" -> positive(value)?.let {
                    events(context).edit().putInt("reminder_days", it).apply()
                    count++
                }
            }
        }

        durations?.let { timer.saveDurations(it) }
        return SettingsRestore(count, themeChanged)
    }

    /** 设置表里的开关：开/是/true/1 都算开；认不出来返回 null，这一项直接跳过。 */
    private fun switchOf(text: String): Boolean? = when (text.trim().lowercase(Locale.US)) {
        "开", "是", "true", "1" -> true
        "关", "否", "false", "0" -> false
        else -> null
    }

    /** 只有正整数才算数：0 与负数一律当「没有」。 */
    private fun positive(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

    /** 写入一个开关型设置；值认不出来返回 false，调用方据此决定要不要计数。 */
    private fun putSwitch(prefs: SharedPreferences, key: String, value: String): Boolean {
        val on = switchOf(value) ?: return false
        prefs.edit().putBoolean(key, on).apply()
        return true
    }

    /** 白名单的值：`包名|包名` → 集合（去空、去重）。 */
    private fun packageSet(value: String): Set<String> =
        value.split(DataBackup.ITEM_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** `23:00-07:00` → 时段。结束早于开始表示跨日，交给 `Slot.valid` 自己判定。 */
    private fun parseSlot(text: String): LockMachineScheduler.Slot? {
        val parts = text.trim().split('-')
        if (parts.size != 2) return null
        val start = parseMinuteOfDay(parts[0]) ?: return null
        val end = parseMinuteOfDay(parts[1]) ?: return null
        return LockMachineScheduler.Slot(start, end).takeIf { it.valid }
    }

    /** `2026-09` → `LockStats` 那个月份戳（`年*100 + Calendar.MONTH`，月份 0 起算，所以要减 1）。 */
    private fun monthOf(text: String): Int? {
        val parts = text.trim().split('-')
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        return if (month in 1..12) year * 100 + month - 1 else null
    }

    /** 每日祈福那几个键没有 store，全项目只有 TasksFragment 在写 —— 名字共用 [DataBackup]。 */
    private fun fortune(context: Context) =
        context.getSharedPreferences(DataBackup.FORTUNE_PREFS, Context.MODE_PRIVATE)

    /** 显示选项与倒数日设置的 prefs（写入方是 `EventSettingsFragment`）。 */
    private fun events(context: Context) =
        context.getSharedPreferences(DataBackup.EVENT_PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 取值（新旧写法都认）

    /**
     * 下面这些一律同时认**新版的人话写法**和**旧版的机器写法**。
     *
     * 直接好处是旧备份文件仍然能导回来；附带好处是文件被人拿 Excel 编辑过之后，
     * 不至于因为把「高」改成「1」就整列静默失效。
     */
    private fun priorityOf(text: String): Int = when (text.trim()) {
        "高", "1" -> 1
        "低", "3" -> 3
        else -> 2
    }

    private fun doneOf(text: String): Boolean = when (text.trim().lowercase(Locale.US)) {
        "已完成", "是", "true", "1" -> true
        else -> false
    }

    private fun yesOf(text: String): Boolean = doneOf(text)

    private fun repeatOf(text: String): String = when (text.trim().lowercase(Locale.US)) {
        "每天", "daily" -> "daily"
        "每周", "weekly" -> "weekly"
        "每月", "monthly" -> "monthly"
        else -> "none"
    }

    private fun recordTypeOf(text: String): String = when (text.trim()) {
        "专注", "focus" -> "focus"
        "休息", "break", "rest" -> "break"
        "" -> "focus"
        else -> text.trim()
    }

    /** 与 [DataBackup] 的预设色名一一对应，改一边记得改另一边。 */
    private val PRESET_COLORS = mapOf(
        "墨绿" to 0xFF3B5B4E.toInt(),
        "古铜金" to 0xFFC9A961.toInt(),
        "赭石" to 0xFF8B4513.toInt(),
        "黛蓝" to 0xFF4A6FA5.toInt(),
        "朱砂" to 0xFFA0522D.toInt(),
        "玄墨" to 0xFF2F4F4F.toInt()
    )

    /** 颜色：认预设色名、`#AARRGGBB`，也认旧版写的十进制负数。 */
    private fun colorOf(text: String): Int {
        val value = text.trim()
        PRESET_COLORS[value]?.let { return it }
        if (value.startsWith("#")) {
            val parsed = value.removePrefix("#").toLongOrNull(16)
            if (parsed != null) return parsed.toInt()
        }
        return value.toIntOrNull() ?: DEFAULT_COUNTDOWN_COLOR
    }

    /** 只看日期的列（截止日期／目标日期）。与 TIME_FORMAT 同理固定 US，免得泰语设备把它当佛历。 */
    private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun parseSheetDay(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return runCatching { DAY_FORMAT.parse(value)?.time }.getOrNull()
    }

    /** 时间列：新版写 `yyyy-MM-dd HH:mm`，也容忍只剩日期（手工把时间删了）。 */
    private fun parseSheetTime(text: String): Long? = parseTime(text) ?: parseSheetDay(text)

    // ------------------------------------------------------------------ 备份包

    /** 文件头是不是 zip（`PK` + 本地文件头/中央目录/数据描述符三种签名之一）。 */
    private fun isZip(file: File): Boolean {
        if (file.length() < 4) return false
        val head = ByteArray(4)
        val read = FileInputStream(file).use { it.read(head) }
        return read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())
    }

    /**
     * 把 zip 里的图片解到应用私有目录，返回「条目名 → 本地文件」的映射。
     *
     * 只取基名落盘（`images/task_1.jpg` → `backup_images/task_1.jpg`），
     * zip 里塞 `../` 之类也穿不出去；同名文件直接覆盖，反复导入不会越堆越多。
     */
    private fun unpackImages(context: Context, zip: ZipFile): Images {
        val result = Images()
        val dir = File(context.filesDir, DataBackup.RESTORED_IMAGE_DIR)
        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            val name = entry.name
            val isWallpaper = name == DataBackup.WALLPAPER_ENTRY
            if (!isWallpaper && !name.startsWith(DataBackup.IMAGE_DIR)) return@forEach
            val base = File(name).name
            if (base.isEmpty() || base == "." || base == "..") return@forEach
            val target = if (isWallpaper) File(context.filesDir, "wallpaper.jpg") else File(dir, base)
            val copied = runCatching {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (!copied) return@forEach
            if (isWallpaper) result.wallpaper = target else result.entries[name] = target
        }
        return result
    }

    /** 备份包内的图片清单。数据行里存的是条目名，这里换成应用本地可读的 `file://` URI。 */
    private class Images {
        val entries = HashMap<String, File>()
        var wallpaper: File? = null

        val size: Int get() = entries.size + if (wallpaper != null) 1 else 0

        fun uri(name: String?): String? {
            val key = name?.trim().orEmpty()
            if (key.isEmpty()) return null
            // 老备份直接存了 content:// 原文 —— 原样保留（多半已失效，但不会比之前更糟）
            if (key.contains("://")) return key
            return entries[key]?.let { Uri.fromFile(it).toString() }
        }

        /** 日记的图片是一列逗号分隔的条目名。 */
        fun uriList(cell: String?): String = cell.orEmpty()
            .split(',')
            .mapNotNull { uri(it) }
            .joinToString(",")
    }

    // ------------------------------------------------------------------ 各段

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

    /**
     * 恢复「灵感便贴」段：创建时间(毫秒) + 内容 + 是否标星。返回新写入条数。
     *
     * 便签的 createdAt 同时当 id 用，所以这里**必须**用毫秒原值、不能格式化成分钟，
     * 否则同一分钟内写的两张便签导回来会撞成一个。
     * 与其它段一致：已存在的 id 跳过，不做覆盖。
     */
    private fun restoreNotes(context: Context, rows: List<List<String>>): Int {
        val existing = NoteStore.load(context)
        val knownIds = existing.mapTo(HashSet()) { it.createdAt }
        val imported = rows.mapNotNull { row ->
            val createdAt = row.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            if (createdAt in knownIds) return@mapNotNull null
            NoteStore.Note(
                createdAt = createdAt,
                text = row.getOrNull(1).orEmpty(),
                starred = row.getOrNull(2)?.trim() == TRUE_MARK
            )
        }
        if (imported.isEmpty()) return 0
        NoteStore.save(context, existing + imported)
        return imported.size
    }

    /** 日记存在 SharedPreferences 里：正文、评分、配图。 */
    private fun restoreDiary(context: Context, rows: List<List<String>>, images: Images): Int {
        val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var count = 0
        rows.forEach { row ->
            val date = row.getOrNull(0)?.trim().orEmpty()
            if (date.isEmpty()) return@forEach
            editor.putString("note_$date", row.getOrNull(3).orEmpty())
            editor.putInt("rating_$date", row.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            // 只有这份备份真的带图时才写图片列表 —— 老备份没有这一列，
            // 无条件写空串会把用户现有的配图抹掉。
            val restored = images.uriList(row.getOrNull(4))
            if (restored.isNotEmpty()) editor.putString("images_$date", restored)
            count++
        }
        editor.apply()
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

    /**
     * 任务行。**前 12 列是 v2.0.66 之前的格式**，排序／模板ID／插图这三列是后加的 ——
     * 一律按可选读取，所以旧备份仍然能完整导入。
     */
    private fun parseTask(row: List<String>, images: Images): Task? {
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
            // 老备份没有排序列，按「与原顺序一致」还原。
            sortOrder = row.getOrNull(12)?.trim()?.toLongOrNull() ?: createdAt,
            imageUri = images.uri(row.getOrNull(14)),
            listType = row[10].ifBlank { "未分类" },
            repeatRule = row[11].ifBlank { "none" },
            // 重复任务靠模板ID 串起「模板 + 每日快照」，丢了它快照会被当成新的模板行。
            templateId = row.getOrNull(13)?.trim()?.toIntOrNull() ?: 0
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

    /** 倒数日行。颜色／排序／关联任务同样是后加的三列，旧备份缺列时走默认值。 */
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
            color = row.getOrNull(6)?.trim()?.toIntOrNull() ?: DEFAULT_COUNTDOWN_COLOR,
            createdAt = parseTime(row[5]) ?: System.currentTimeMillis(),
            sortOrder = row.getOrNull(7)?.trim()?.toIntOrNull() ?: 0,
            linkedTaskId = row.getOrNull(8)?.trim()?.toIntOrNull() ?: 0,
            note = row[4]
        )
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
