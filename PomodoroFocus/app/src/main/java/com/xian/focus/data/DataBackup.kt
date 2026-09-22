package com.xian.focus.data

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导出：打成一个 zip，里面**每张表一个独立的 CSV**，外加所有图片。
 *
 * ```
 * 贤备份_20260916_0900.zip
 * ├── README.txt       这个包怎么看、怎么导回去
 * ├── tasks.csv        任务          subtasks.csv    子任务
 * ├── records.csv      专注记录      countdowns.csv  倒数日
 * ├── habits.csv       小习惯        habit_logs.csv  打卡记录
 * ├── diary.csv        每日复盘      limits.csv      应用限额
 * ├── notes.csv        灵感便贴
 * ├── settings.csv     偏好设置（主题 / 时长 / 锁机 / 祈福）
 * ├── images/…         任务插图、日记配图
 * └── wallpaper.jpg    壁纸
 * ```
 *
 * 拆成一表一文件是为了**能当表格用**：早先所有小节挤在一个 `data.csv` 里、列数还各不相同，
 * Excel 打开后第一列混着表头和小节名，筛选、排序、透视表全部作废。
 *
 * 列里的值一律写成**人能直接读懂的形态**（`高` 而不是 `1`、`墨绿` 而不是 `-12887218`、
 * `2026-09-16 08:00` 而不是 `1757980000000`）。内部主键／外键统一叫「编号」，一律押在每张表
 * 最后一列 —— 阅读时忽略即可，但整列删掉的话导入时关联会断。
 *
 * 文件**名**用纯 ASCII，内容仍是中文：各家解压工具对中文条目名的编码处理并不一致，
 * 名字乱码事小、导入端按名字找不着文件事大。中文名对应关系写在 README 里。
 *
 * 旧版导出（单文件 `data.csv`、多小节）的解析仍然保留在 [DataRestore] ——
 * 已经发出去的备份文件必须永远能导回来。
 */
object DataBackup {

    // 十个数据表的条目名。导入端靠「包里有没有 tasks.csv」区分新旧两种布局。
    const val TASKS_ENTRY = "tasks.csv"
    const val SUBTASKS_ENTRY = "subtasks.csv"
    const val RECORDS_ENTRY = "records.csv"
    const val COUNTDOWNS_ENTRY = "countdowns.csv"
    const val HABITS_ENTRY = "habits.csv"
    const val HABIT_LOGS_ENTRY = "habit_logs.csv"
    const val DIARY_ENTRY = "diary.csv"
    const val LIMITS_ENTRY = "limits.csv"
    const val NOTES_ENTRY = "notes.csv"
    const val SETTINGS_ENTRY = "settings.csv"
    const val README_ENTRY = "README.txt"

    /** 十张表的条目名，导入端按这个名单拣表，与下面 zipInto 的写入顺序一致。 */
    val SHEET_ENTRIES = listOf(
        TASKS_ENTRY, SUBTASKS_ENTRY, RECORDS_ENTRY, COUNTDOWNS_ENTRY,
        HABITS_ENTRY, HABIT_LOGS_ENTRY,
        DIARY_ENTRY, LIMITS_ENTRY, NOTES_ENTRY, SETTINGS_ENTRY
    )

    /** 一格里塞多项时的分隔符（定时时段、锁机白名单、跳过标记）。格言例外：一行一条。 */
    internal const val ITEM_SEPARATOR = "|"

    /** 主题下标（[com.xian.focus.ThemeStore] 的 `THEME_*`）↔ 人话名，顺序必须与那些常量一致。 */
    internal val THEME_NAMES = listOf("青绿", "朱砂", "黛蓝", "玄墨")

    /**
     * 星期名。**下标 = `Calendar.DAY_OF_WEEK - 1`**（0 = 周日），与 [Habit.maskOf] 的位序一致 ——
     * 所以习惯的星期掩码可以直接按位取这里的名字。两端共用同一份，免得各写一份数组写岔。
     */
    internal val WEEK_DAY_NAMES = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 写星期时的排列顺序（掩码位下标）：周一 … 周六、周日。
     * 掩码本身的位序是周日开头，但人读「周一、周三、周五」才顺 —— 只影响写法，不影响位序。
     */
    internal val WEEK_DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

    /**
     * 倒数日排序：`event_settings.sort_by` 的键 ↔ 人话名。
     * 键的顺序必须与 `EventSettingsFragment.sortValues` 一致。
     */
    internal val SORT_BY_KEYS = listOf("days_remaining", "target_date", "created_at")
    internal val SORT_BY_TEXTS = listOf("按剩余天数", "按目标日期", "按创建时间")

    /**
     * 每日祈福那几个键**直接写在 TasksFragment 里、没有 store**（全项目就那一处写）。
     * 导出与导入共用这里的名字，免得两边各写一遍字面量写岔了。
     */
    internal const val FORTUNE_PREFS = "fortune_data"
    internal const val FORTUNE_DATE = "date"
    internal const val FORTUNE_NAME = "fortune"
    internal const val FORTUNE_MEANING = "meaning"
    internal const val FORTUNE_COUNT = "bless_count"

    /** 显示选项与倒数日设置在 event_settings 里（写入方是 `EventSettingsFragment`）。 */
    internal const val EVENT_PREFS = "event_settings"

    /** 旧版把七个小节挤在这一个文件里，导入端还认得它（老备份的兼容路径）。 */
    const val LEGACY_CSV_ENTRY = "data.csv"

    /** zip 内图片所在的目录（末尾带 `/`）。 */
    const val IMAGE_DIR = "images/"

    /** zip 内壁纸的文件名。 */
    const val WALLPAPER_ENTRY = "wallpaper.jpg"

    /** 还原时图片落在应用私有目录下的子目录名。 */
    const val RESTORED_IMAGE_DIR = "backup_images"

    /** 日记（每日复盘）在 review_prefs 中的键前缀 —— 用来从 prefs 里捞出「有内容的日期」。 */
    private val DIARY_KEY_PREFIXES = listOf("note_", "rating_", "images_")

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** 默认文件名：SAF「保存到文件」的建议名，也是分享时的临时文件名。 */
    fun fileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "贤备份_$ts.zip"
    }

    /**
     * 把整份备份写进 [out]（调用方负责关闭）。写失败返回 false ——
     * 单张图片读不出来不该毁掉整份备份，那类错误在内部已经被吞掉了。
     */
    suspend fun write(context: Context, repository: FocusRepository, out: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { zipInto(context, repository, out) }.isSuccess
        }

    // ------------------------------------------------------------------ 打包

    private suspend fun zipInto(context: Context, repository: FocusRepository, out: OutputStream) {
        val tasks = repository.getAllTasks()
        // 子任务只导出「父任务还在」的那些。回收站里的任务不进备份，
        // 它们的子任务导出去就成了没有父行的孤儿（导入端虽然会滤掉，但没理由让它先写进文件）。
        val exportedTaskIds = tasks.mapTo(HashSet()) { it.id }
        val subtasks = repository.getAllSubtasks().filter { it.taskId in exportedTaskIds }
        val records = repository.getAllRecords()
        val countdowns = repository.getAllCountdownsOnce()
        val habits = repository.getHabits()
        // 与子任务同理：已删习惯（在回收站里）不进备份，它们的打卡记录导出去就成了孤儿行。
        val exportedHabitIds = habits.mapTo(HashSet()) { it.id }
        val habitLogs = repository.getAllHabitLogs().filter { it.habitId in exportedHabitIds }

        /** 待打包图片：zip 内条目名 → 源（`content://` URI，或壁纸那样的绝对路径）。 */
        val images = LinkedHashMap<String, String>()

        val sheets = linkedMapOf(
            TASKS_ENTRY to tasksSheet(context, tasks, images),
            SUBTASKS_ENTRY to subtasksSheet(subtasks),
            RECORDS_ENTRY to recordsSheet(records),
            COUNTDOWNS_ENTRY to countdownsSheet(countdowns),
            HABITS_ENTRY to habitsSheet(habits),
            HABIT_LOGS_ENTRY to habitLogsSheet(habitLogs),
            DIARY_ENTRY to diarySheet(context, images),
            LIMITS_ENTRY to limitsSheet(context),
            NOTES_ENTRY to notesSheet(context),
            SETTINGS_ENTRY to settingsSheet(context)
        )

        WallpaperStore.getPath(context)?.let { path ->
            if (File(path).exists()) images[WALLPAPER_ENTRY] = path
        }

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(README_ENTRY))
            zip.write(README_TEXT.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sheets.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            images.forEach { (entryName, source) -> writeImage(zip, context, entryName, source) }
        }
    }

    // ------------------------------------------------------------------ 各表

    /**
     * tasks.csv。最常被人翻的一张表，所以人看的列在前、内部编号押最后。
     *
     * 「排序」是用户拖拽任务拖出来的顺序，不是可有可无的实现细节 —— 丢了列表顺序就回到默认。
     * 「重复系列编号」串起重复任务的模板和每日快照，丢了快照会被当成新的模板行。
     *
     * 列序就是导入端按下标读取的顺序，改列序要连 [DataRestore] 一起改。
     */
    private fun tasksSheet(context: Context, tasks: List<Task>, images: MutableMap<String, String>): String {
        val sheet = Sheet(
            "标题", "备注", "优先级", "分类", "截止日期", "截止时间", "预计贤时", "已完成贤时",
            "状态", "创建时间", "重复", "插图", "重复系列编号", "排序", "编号"
        )
        tasks.forEach { t ->
            val imageName = t.imageUri?.takeIf { it.isNotBlank() }?.let { uri ->
                val name = IMAGE_DIR + "task_${t.id}." + extensionOf(context, uri)
                images[name] = uri
                name
            }.orEmpty()
            sheet.row(
                t.title,
                t.description.orEmpty(),
                priorityText(t.priority),
                t.listType,
                t.dueDate?.let(::date).orEmpty(),
                t.dueTimeMinutes?.let { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) }.orEmpty(),
                t.estimatedPomodoros,
                t.completedPomodoros,
                doneText(t.isCompleted),
                dateTime(t.createdAt),
                repeatText(t.repeatRule),
                imageName,
                t.templateId,
                t.sortOrder,
                t.id
            )
        }
        return sheet.toString()
    }

    /**
     * subtasks.csv。「所属任务编号」指向 tasks.csv 的「编号」。
     *
     * ⚠️ 「所属日期」是 v2.0.94 **追加在表尾**的列，不插到「状态」后面：
     * 导入端是按列位置读的，插在中间会让 v2.0.93 及更早导出的备份整列错位且不报错。
     * 空 = 不分日期（普通任务 / 重复任务的标题模板行），与旧备份的行为一致。
     */
    private fun subtasksSheet(subtasks: List<Subtask>): String {
        val sheet = Sheet("所属任务编号", "标题", "状态", "编号", "所属日期")
        subtasks.forEach { s ->
            sheet.row(s.taskId, s.title, doneText(s.isCompleted), s.id, s.dueDate?.let(::date).orEmpty())
        }
        return sheet.toString()
    }

    /** records.csv。「时长」保留 —— 它虽然是算得出来的，但也是这张表里最常被看的一列。 */
    private fun recordsSheet(records: List<PomodoroRecord>): String {
        val sheet = Sheet("任务编号", "开始时间", "结束时间", "时长(分钟)", "类型", "状态", "编号")
        records.forEach { r ->
            sheet.row(
                r.taskId ?: 0,
                dateTime(r.startTime),
                dateTime(r.endTime),
                ((r.endTime - r.startTime) / 60000).toInt(),
                recordTypeText(r.type),
                doneText(r.isFinished),
                r.id
            )
        }
        return sheet.toString()
    }

    /** countdowns.csv。排序值不导出 —— 行序本身就是排序，导入时按行序重新编号即可。 */
    private fun countdownsSheet(countdowns: List<Countdown>): String {
        // 农历那四列**追加在表尾**，不插到「每年重复」后面：导入端是按列位置读的，
        // 插在中间会让 v2.0.86 及更早导出的备份整列错位（「备注」被当成「历法」）。
        // 押尾之后旧备份只是少了这四列，parseSheetCountdown 走默认值，公历条目行为不变。
        val sheet = Sheet(
            "标题", "目标日期", "每年重复", "备注", "颜色", "创建时间", "关联任务编号", "编号",
            "历法", "农历月", "农历日", "闰月"
        )
        countdowns.forEach { c ->
            // 公历条目的农历三格留空：填 0 或 1 都只是内部默认值，写进给用户看的表反而费解
            val lunar = c.calendarType == CountdownCalendar.LUNAR
            sheet.row(
                c.title,
                date(c.targetDate),
                yesNo(c.repeatYearly),
                c.note,
                colorText(c.color),
                dateTime(c.createdAt),
                c.linkedTaskId,
                c.id,
                calendarText(c.calendarType),
                if (lunar) c.lunarMonth else "",
                if (lunar) c.lunarDay else "",
                if (lunar) yesNo(c.lunarLeap) else ""
            )
        }
        return sheet.toString()
    }

    /**
     * habits.csv。习惯本身的设置，打卡历史在 `habit_logs.csv`。
     *
     * 频率写成「每天 / 每周 / 指定星期」三种人话，具体参数各占一列：
     * 「每周」看「每周目标」，「指定星期」看「指定星期」列（`周一、周三、周五`）。
     * 拆成三列而不是写成一句「每周 3 次」，是为了让这张表在 Excel 里能筛选能排序 ——
     * 一句话里夹着数字，筛选和求和都用不上。
     *
     * 「排序」与任务表同理：那是用户拖出来的顺序，丢了列表就回默认。
     */
    private fun habitsSheet(habits: List<Habit>): String {
        val sheet = Sheet(
            "名称", "频率", "每周目标", "指定星期", "每日目标", "提醒时间", "颜色", "开始日期", "排序", "编号"
        )
        habits.forEach { h ->
            sheet.row(
                h.name,
                freqText(h.freqType),
                h.weeklyTarget,
                weekDayText(h.weekDaysMask),
                h.targetPerDay,
                if (h.remindMinutes >= 0) clock(h.remindMinutes) else "",
                colorText(h.color),
                if (h.startDate > 0) date(h.startDate) else "",
                h.sortOrder,
                h.id
            )
        }
        return sheet.toString()
    }

    /**
     * habit_logs.csv。**一天一行**（与库里的 [HabitLog] 一致），不是打一次一行 ——
     * 后者一年下来能堆出几千行重复日期，反而看不出「哪天打了几次」。
     *
     * 「习惯编号」指向 habits.csv 的「编号」。
     */
    private fun habitLogsSheet(logs: List<HabitLog>): String {
        val sheet = Sheet("习惯编号", "打卡日期", "次数")
        logs.forEach { log -> sheet.row(log.habitId, date(log.day), log.count) }
        return sheet.toString()
    }

    /**
     * diary.csv。日记存在 SharedPreferences 里，之前既不在导出范围、也逃过了「清除数据」，
     * 等于没有任何备份出口 —— 这里一并导出。
     */
    private fun diarySheet(context: Context, images: MutableMap<String, String>): String {
        val sheet = Sheet("日期", "评分", "内容", "图片")
        val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)
        val dates = prefs.all.keys
            .mapNotNull { key -> DIARY_KEY_PREFIXES.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } }
            .distinct()
            .sorted()
        dates.forEach { date ->
            val names = mutableListOf<String>()
            prefs.getString("images_$date", "").orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .forEachIndexed { index, uri ->
                    val name = IMAGE_DIR + "diary_${safeName(date)}_$index." + extensionOf(context, uri)
                    images[name] = uri
                    names.add(name)
                }
            sheet.row(date, prefs.getInt("rating_$date", 0), prefs.getString("note_$date", "").orEmpty(), names.joinToString(","))
        }
        return sheet.toString()
    }

    /**
     * limits.csv。应用限额只在 SharedPreferences 里，不导出等于换台手机重设一遍。
     * 「应用」列是查出来的应用名，只为让人看懂 —— 导入端认的是「包名」列。
     */
    private fun limitsSheet(context: Context): String {
        val sheet = Sheet("应用", "包名", "每日限额(分钟)")
        AppLimitStore.limitedPackages(context).forEach { packageName ->
            sheet.row(appLabel(context, packageName), packageName, AppLimitStore.limitMinutes(context, packageName))
        }
        return sheet.toString()
    }

    /**
     * notes.csv。便贴也只在 SharedPreferences 里，不导出等于换台手机全丢。
     *
     * 「编号」存毫秒原值：它同时是便签的 id，格式化成分钟会让同一分钟内写的两张便签撞成一个，
     * 所以除了给人看的「时间」，还得原样留一列。
     */
    private fun notesSheet(context: Context): String {
        val sheet = Sheet("内容", "标星", "时间", "编号")
        NoteStore.load(context).forEach { note ->
            sheet.row(note.text, yesNo(note.starred), dateTime(note.createdAt), note.createdAt)
        }
        return sheet.toString()
    }

    /**
     * settings.csv。偏好设置与锁机里可搬运的那部分 —— 三列：**设置 / 值 / 说明**。
     *
     * 这张表与别的表不同：它按**第一列的名字**读回，不按列的位置。键值表天然是名字驱动的 ——
     * 名字打错一个字只丢那一项设置，不会像列错位那样把整张表读花。两端的名字一一对应，
     * 少认一个会被 `_tmp_cmp/format_check.py` 抓出来（那正是一处「导入成功但这项设置没回来」的静默坑）。
     *
     * 值一律写人话：开关是「开/关」、时段是 `23:00-07:00`、主题是「青绿」、排序是「按剩余天数」。
     * 只有白名单例外 —— 它只认包名（应用可能没装、也可能改名），应用名放进「说明」列供人对照。
     *
     * 刻意**不导**的三样，理由写在 [DataRestore.restoreSheetSettings] 的注释里：
     * 两个 PIN 的哈希、退出锁机的月度额度、应用限额的当日用量。
     */
    private fun settingsSheet(context: Context): String {
        val sheet = Sheet("设置", "值", "说明")
        val timer = TimerSettingsPreferences(context)
        val durations = timer.getDurations()

        sheet.row(
            "主题",
            THEME_NAMES.getOrElse(ThemeStore.selectedTheme(context)) { THEME_NAMES.first() },
            "可选：" + THEME_NAMES.joinToString(" / ")
        )
        sheet.row("每日目标(贤时)", DailyGoalPreferences(context).getDailyGoal(), "每天计划完成的贤时数")
        sheet.row("专注时长(分钟)", durations.focusMinutes, "")
        sheet.row("短休时长(分钟)", durations.shortBreakMinutes, "")
        sheet.row("长休时长(分钟)", durations.longBreakMinutes, "")
        sheet.row("锁机开关", onOff(timer.isLockEnabled()), "「锁机专注」的总开关")

        val slots = LockMachineScheduler.slots(context)
        sheet.row(
            "定时锁机时段",
            slots.joinToString(ITEM_SEPARATOR) { "${clock(it.startMinute)}-${clock(it.endMinute)}" },
            if (slots.isEmpty()) "（没设置定时时段）"
            else "结束时刻早于开始表示跨日，多个时段用 $ITEM_SEPARATOR 隔开"
        )

        val whitelist = LockMachineController.whitelist(context).sorted()
        sheet.row(
            "锁机白名单",
            whitelist.joinToString(ITEM_SEPARATOR),
            if (whitelist.isEmpty()) "（空）" else whitelist.joinToString("、") { appLabel(context, it) }
        )
        sheet.row(
            "锁机自定义时长(分钟)",
            LockMachineController.customMinutes(context),
            "上次敲过的自定义锁机分钟数；0 表示还没用过"
        )

        val quotes = LockQuotes.list(context)
        sheet.row(
            "锁机格言",
            quotes.joinToString("\n"),
            if (quotes.isEmpty()) "（空）" else "锁机时随机显示，一行一条"
        )

        val stats = LockStats.stored(context)
        sheet.row("锁机统计月份", monthText(stats.month), "跨月后这份统计会自动重新开始")
        sheet.row("锁机次数", stats.sessions, "本月完成的锁机次数")
        sheet.row("锁机时长(分钟)", stats.minutes, "本月累计锁机时长")
        sheet.row("锁机坚持次数", stats.held, "本月锁满到点、没有提前退出的次数")

        val fortune = context.getSharedPreferences(FORTUNE_PREFS, Context.MODE_PRIVATE)
        sheet.row("祈福次数", fortune.getInt(FORTUNE_COUNT, 0), "累计抽签次数")
        sheet.row("最近抽签日期", fortune.getString(FORTUNE_DATE, "").orEmpty(), "当天抽过就不再重复抽")
        sheet.row("最近签", fortune.getString(FORTUNE_NAME, "").orEmpty(), "")
        sheet.row("最近签释义", fortune.getString(FORTUNE_MEANING, "").orEmpty(), "")

        val skips = TaskSkipStore.all(context)
        sheet.row(
            "跳过标记",
            skips.joinToString(ITEM_SEPARATOR) { skipText(it) },
            if (skips.isEmpty()) "（没有跳过记录）" else "重复任务「本次跳过」，格式：任务编号@日期"
        )

        val events = context.getSharedPreferences(EVENT_PREFS, Context.MODE_PRIVATE)
        sheet.row("显示已完成任务", onOff(events.getBoolean("show_completed", true)), "")
        sheet.row("完成划横线", onOff(events.getBoolean("show_strikethrough", true)), "")
        sheet.row("显示任务序号", onOff(events.getBoolean("show_order_number", false)), "")
        sheet.row("显示备注", onOff(events.getBoolean("show_note", false)), "")
        sheet.row("显示节假日标记", onOff(events.getBoolean("show_holiday", true)), "")
        val sortIndex = SORT_BY_KEYS.indexOf(events.getString("sort_by", SORT_BY_KEYS[0])).coerceAtLeast(0)
        sheet.row("倒数日排序", SORT_BY_TEXTS[sortIndex], "可选：" + SORT_BY_TEXTS.joinToString(" / "))
        sheet.row("倒数日自动建任务", onOff(events.getBoolean("auto_link_task", true)), "新建倒数日时顺带建一条同名任务")
        sheet.row("倒数日提醒", onOff(events.getBoolean("reminder_enabled", true)), "")
        sheet.row("提前提醒天数", events.getInt("reminder_days", 1), "目标日往前几天提醒")
        return sheet.toString()
    }

    /** 跳过标记存的是 `<模板id>@<当天 0 点的毫秒>`，导出时把毫秒换成人能读的日期。 */
    private fun skipText(raw: String): String {
        val id = raw.substringBefore('@')
        val day = raw.substringAfter('@', "").toLongOrNull() ?: return raw
        return "$id@${date(day)}"
    }

    // ------------------------------------------------------------------ 取值

    /** 优先级：库里 1/2/3 分别对应界面上的高/中/低。 */
    private fun priorityText(level: Int): String = when (level) {
        1 -> "高"
        3 -> "低"
        else -> "中"
    }

    /** 重复规则：库里存 none/daily/weekly/monthly。 */
    private fun repeatText(rule: String): String = when (rule) {
        "daily" -> "每天"
        "weekly" -> "每周"
        "monthly" -> "每月"
        else -> "不重复"
    }

    /** 专注记录类型：目前只有 focus，留个兜底免得将来加了新类型被写成空白。 */
    private fun recordTypeText(type: String): String = when (type) {
        "focus" -> "专注"
        "break", "rest" -> "休息"
        else -> type
    }

    /** 习惯频率：库里存 daily/weekly/custom，参数在「每周目标」与「指定星期」两列里。 */
    private fun freqText(type: String): String = when (type) {
        Habit.FREQ_WEEKLY -> "每周"
        Habit.FREQ_CUSTOM -> "指定星期"
        Habit.FREQ_DAILY -> "每天"
        // 认不出来就原样写出来：与其悄悄写成「每天」，不如让文件里留个看得见的怪值
        else -> type
    }

    /** 星期掩码 → `周一、周三、周五`。**周一排在最前**，与打卡页的星期条同一阅读顺序。 */
    private fun weekDayText(mask: Int): String = WEEK_DAY_ORDER
        .filter { (mask shr it) and 1 == 1 }
        .joinToString("、") { WEEK_DAY_NAMES[it] }

    /** 倒数日的六个预设色各有名字；用户自己调出来的颜色退化成 `#AARRGGBB`。 */
    private val PRESET_COLORS = linkedMapOf(
        0xFF3B5B4E.toInt() to "墨绿",
        0xFFC9A961.toInt() to "古铜金",
        0xFF8B4513.toInt() to "赭石",
        0xFF4A6FA5.toInt() to "黛蓝",
        0xFFA0522D.toInt() to "朱砂",
        0xFF2F4F4F.toInt() to "玄墨"
    )

    private fun colorText(color: Int): String =
        PRESET_COLORS[color] ?: String.format(Locale.US, "#%08X", color)

    private fun doneText(done: Boolean): String = if (done) "已完成" else "未完成"

    private fun yesNo(yes: Boolean): String = if (yes) "是" else "否"

    private fun calendarText(calendarType: Int): String =
        if (calendarType == CountdownCalendar.LUNAR) "农历" else "公历"

    /** 设置表里的开关。与 [yesNo] 分开：那张表读的是「是不是」，这张读的是「开没开」。 */
    private fun onOff(on: Boolean): String = if (on) "开" else "关"

    /** 一天中的第几分钟 → `HH:mm`（[LockMachineScheduler.Slot] 用的就是这个口径）。 */
    private fun clock(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /**
     * 月份戳（`年*100+月`，月是 0 起算的 `Calendar.MONTH`）→ `2026-09`；没记过（<= 0）给空串。
     * 那个 `+ 1` 不是笔误：`LockStats` 存的是 `Calendar.MONTH`（0 起），所以 9 月存成 202608。
     */
    private fun monthText(month: Int): String =
        if (month <= 0) "" else String.format(Locale.US, "%04d-%02d", month / 100, month % 100 + 1)

    /**
     * 日期：`2026-09-20`。Locale 固定 US —— 用默认 Locale 的话，
     * 泰语设备的 `yyyy` 会输出佛历年份（2569），导回来直接错 543 年。
     */
    private fun date(millis: Long): String = fmt("yyyy-MM-dd", millis)

    /** 日期时间：`2026-09-16 08:00`。 */
    private fun dateTime(millis: Long): String = fmt("yyyy-MM-dd HH:mm", millis)

    /** 每次新建实例：SimpleDateFormat 不是线程安全的，而导出可能被并发触发。 */
    private fun fmt(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(millis))

    /**
     * 一张表 = 表头 + 若干行。逗号/引号/换行的转义规则与导入端 [DataRestore] 的解析严格对称。
     */
    private class Sheet(vararg header: String) {
        private val sb = StringBuilder()

        init {
            row(*header)
        }

        fun row(vararg cells: Any?) {
            sb.append(cells.joinToString(",") { escape(it?.toString().orEmpty()) }).append('\n')
        }

        /** UTF-8 BOM：不写它的话 Windows 版 Excel 会按系统本地编码解析，中文一打开全是乱码。 */
        override fun toString(): String = "\uFEFF" + sb
    }

    private fun escape(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }

    // ------------------------------------------------------------------ 图片

    /**
     * 流式拷贝一张图 —— 不整块进内存，几百张配图也不会把堆顶爆。
     * 单张失败（URI 权限被系统回收、原图已被删）就跳过，不影响其余条目。
     */
    private fun writeImage(zip: ZipOutputStream, context: Context, entryName: String, source: String) {
        runCatching {
            val input = openImage(context, source) ?: return
            input.use {
                zip.putNextEntry(ZipEntry(entryName))
                it.copyTo(zip)
                zip.closeEntry()
            }
        }
    }

    private fun openImage(context: Context, source: String): InputStream? {
        val uri = Uri.parse(source)
        return if (uri.scheme == null || uri.scheme == "file") {
            runCatching { FileInputStream(File(uri.path ?: source)) }.getOrNull()
        } else {
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        }
    }

    /** 图片类型只影响 zip 内的后缀名（解码端按内容嗅探），认不出来一律给 `.jpg`。 */
    private fun extensionOf(context: Context, source: String): String {
        val uri = Uri.parse(source)
        if (uri.scheme == null || uri.scheme == "file") {
            val ext = (uri.path ?: source).substringAfterLast('.', "").lowercase(Locale.US)
            return ext.takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when (mime?.lowercase(Locale.US)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }

    /** 包名换成应用名 —— `com.tencent.mm` 对人没有任何意义。已卸载的应用查不到，退回包名。 */
    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** 日期（`2026-09-16`）本来就能当文件名，但别赌它 —— 备份里的字段可能被手工编辑过。 */
    private fun safeName(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private val README_TEXT = """
        「贤」数据备份
        ==============================

        解压后每张表一个文件，用 Excel / WPS / 记事本都能直接打开。
        手机上不装「贤」也能查看里面的内容。

        tasks.csv       任务（含重复任务）
        subtasks.csv    子任务
        records.csv     专注记录
        countdowns.csv  倒数日
        habits.csv      小习惯
        habit_logs.csv  习惯打卡记录（一天一行）
        diary.csv       每日复盘（日记）
        limits.csv      应用限额
        notes.csv       灵感便贴
        settings.csv    偏好设置（主题、时长、锁机、统计、每日祈福…）
        images/         任务插图、日记配图
        wallpaper.jpg   壁纸

        表里的日期写成 2026-09-16 这样，优先级写的是「高 / 中 / 低」，
        倒数日的颜色写的是「墨绿 / 古铜金」这类名字 —— 都是给人看的。
        倒数日表末尾的「历法」写「公历」或「农历」；写农历时后面三列
        （农历月、农历日、闰月）才有意义，农历那一列写的就是「八月十五」里的月和日。
        习惯表里的频率写的是「每天 / 每周 / 指定星期」，指定星期那一格写成
        「周一、周三、周五」；提醒时间写成 08:00，不提醒就留空。

        设置表（settings.csv）是「设置 / 值 / 说明」三列，一行一项，比如
        「定时锁机时段」的值写的是 23:00-07:00、「主题」写的是 青绿。
        锁机白名单那一行的值是包名（导入只认包名），应用名写在「说明」列里。

        每张表最后一列叫「编号」，是「贤」内部用来互相引用的序号
        （比如子任务的「所属任务编号」指向任务表里的「编号」）。
        阅读时可以忽略它，但请不要整列删掉，否则导回「贤」时这些数据会接不上。

        有两样东西**不**在这个包里：锁机密码（与打开「贤」的密码）和每月退出锁机的次数额度。
        密码只导哈希没有意义、留着反而是隐患；额度导回来等于把锁机额度重置。
        换台手机这两样要重设一次。

        怎么导回去：打开「贤」→ 底部「我的」→ 导出与导入 → 导入数据，
        选中这个 zip 就行（只解压出里面某一个 csv 也能导，只是没有图片）。
        已经存在的记录不会被覆盖，只会把缺的补上。
    """.trimIndent()
}
