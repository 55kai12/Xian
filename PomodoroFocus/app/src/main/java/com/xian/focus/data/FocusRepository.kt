package com.xian.focus.data

import com.xian.focus.RepeatRule
import com.xian.focus.TaskSkipStore
import com.xian.focus.TaskViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FocusRepository(
    private val taskDao: TaskDao,
    private val recordDao: PomodoroRecordDao,
    private val subtaskDao: SubtaskDao,
    private val countdownDao: CountdownDao,
    private val habitDao: HabitDao
) {
    suspend fun getAllTasks() = withContext(Dispatchers.IO) {
        taskDao.getAllTasks()
    }

    suspend fun getTasksByListType(type: String) = withContext(Dispatchers.IO) {
        taskDao.getTasksByListType(type)
    }

    suspend fun getPendingTasks(group: String?) = withContext(Dispatchers.IO) {
        if (group.isNullOrBlank()) taskDao.getPendingTasks() else taskDao.getPendingTasksByListType(group)
    }

    suspend fun getCompletedTasks(group: String?) = withContext(Dispatchers.IO) {
        if (group.isNullOrBlank()) taskDao.getCompletedTasks() else taskDao.getCompletedTasksByListType(group)
    }

    suspend fun getListTypes() = withContext(Dispatchers.IO) {
        taskDao.getListTypes()
    }

    suspend fun insertTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.insertTask(task)
    }

    // ---- CSV 恢复：按原 id 批量写回，撞 id 的行由 DAO 跳过（不覆盖已有数据）。 ----

    suspend fun restoreTasks(tasks: List<Task>) = withContext(Dispatchers.IO) {
        if (tasks.isNotEmpty()) taskDao.insertTasksFromBackup(tasks)
    }

    suspend fun restoreSubtasks(subtasks: List<Subtask>) = withContext(Dispatchers.IO) {
        if (subtasks.isNotEmpty()) subtaskDao.insertFromBackup(subtasks)
    }

    suspend fun restoreRecords(records: List<PomodoroRecord>) = withContext(Dispatchers.IO) {
        if (records.isNotEmpty()) recordDao.insertFromBackup(records)
    }

    suspend fun restoreCountdowns(countdowns: List<Countdown>) = withContext(Dispatchers.IO) {
        if (countdowns.isNotEmpty()) countdownDao.insertFromBackup(countdowns)
    }

    suspend fun updateTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.updateTask(task.normalizeRepeatTemplate())
    }

    suspend fun updateTasks(tasks: List<Task>) = withContext(Dispatchers.IO) {
        taskDao.updateTasks(tasks.map { it.normalizeRepeatTemplate() })
    }

    /**
     * 删除任务 —— 实际是「移入回收站」：在行上打一个时间戳，30 天内可以还原。
     * 方法名保留 delete 是刻意的：调用方语义没变（用户看到的就是删掉了），
     * 只有存储层从 DELETE 换成了 UPDATE。
     */
    suspend fun deleteTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.trashTask(task.id, System.currentTimeMillis())
    }

    /** 移入回收站：整个重复系列（模板 + 全部快照）。 */
    suspend fun deleteTaskSeries(templateId: Int) = withContext(Dispatchers.IO) {
        taskDao.trashTemplateWithSnapshots(templateId, System.currentTimeMillis())
    }

    /**
     * 移入回收站：整个重复系列，但把已完成的那几天留作历史。
     *
     * 两步顺序不能换：必须先把已完成的快照摘出来（templateId 置 0），
     * 否则第二步按 templateId 删的时候会把它们一起带走。
     */
    suspend fun deleteTaskSeriesKeepCompleted(templateId: Int) = withContext(Dispatchers.IO) {
        taskDao.detachCompletedSnapshots(templateId)
        taskDao.trashTemplateAndUnfinishedSnapshots(templateId, System.currentTimeMillis())
    }

    suspend fun getTaskById(id: Int) = withContext(Dispatchers.IO) {
        taskDao.getTaskById(id)
    }

    suspend fun getCompletedSnapshot(templateId: Int, dueDate: Long) = withContext(Dispatchers.IO) {
        taskDao.getCompletedSnapshot(templateId, dueDate)
    }

    /** 某模板在某天的任意快照（含未完成的），给「移到今天」判重用。 */
    suspend fun getSnapshotOn(templateId: Int, dueDate: Long) = withContext(Dispatchers.IO) {
        taskDao.getSnapshotOn(templateId, dueDate)
    }

    suspend fun deleteCompletedSnapshot(templateId: Int, dueDate: Long) = withContext(Dispatchers.IO) {
        taskDao.deleteCompletedSnapshot(templateId, dueDate)
    }

    suspend fun insertSubtask(subtask: Subtask) = withContext(Dispatchers.IO) {
        subtaskDao.insert(subtask)
    }

    suspend fun updateSubtask(subtask: Subtask) = withContext(Dispatchers.IO) {
        subtaskDao.update(subtask)
    }

    suspend fun deleteSubtaskById(id: Int) = withContext(Dispatchers.IO) {
        subtaskDao.deleteById(id)
    }

    suspend fun deleteSubtasksByTaskId(taskId: Int) = withContext(Dispatchers.IO) {
        subtaskDao.deleteByTaskId(taskId)
    }

    suspend fun getSubtasksByTaskId(taskId: Int) = withContext(Dispatchers.IO) {
        subtaskDao.getByTaskId(taskId)
    }

    suspend fun getAllSubtasks() = withContext(Dispatchers.IO) {
        subtaskDao.getAll()
    }

    suspend fun getAllRecords() = withContext(Dispatchers.IO) {
        recordDao.getAllRecords()
    }

    suspend fun insertRecord(record: PomodoroRecord) = withContext(Dispatchers.IO) {
        recordDao.insertRecord(record)
    }

    suspend fun getFocusStats(skipped: Set<String> = emptySet()): FocusStats = withContext(Dispatchers.IO) {
        val dayStart = startOfToday()
        val dayEnd = dayStart + DAY_MILLIS
        val weekStart = dayStart - 6L * DAY_MILLIS
        // 直接勾选完成的任务也计入统计，与趋势线共用 visibleCompletions —— 口径必须一致，
        // 否则「趋势线上没有、今日贤时里却有」又是一处对不上。
        val visible = visibleCompletions(taskDao.getAllTasks(), skipped)
        val todayTaskCount = visible.count { it.dueDate!! >= dayStart && it.dueDate!! < dayEnd }
        val weekTaskCount = visible.count { it.dueDate!! >= weekStart && it.dueDate!! < dayEnd }
        FocusStats(
            todayCount = recordDao.getCompletedFocusCountBetween(dayStart, dayEnd) + todayTaskCount,
            lastSevenDaysCount = recordDao.getCompletedFocusCountBetween(weekStart, dayEnd) + weekTaskCount,
            totalCount = recordDao.getTotalCompletedFocusCount() + visible.size,
            totalMinutes = recordDao.getTotalFocusMinutes()
        )
    }

    suspend fun getLastSevenDaysCounts(skipped: Set<String> = emptySet()): List<DailyCount> {
        val dayStart = startOfToday()
        return getDailyCountsBetween(dayStart - 6L * DAY_MILLIS, dayStart + DAY_MILLIS, skipped)
    }

    /**
     * 区间内每天的「贤时」数 = 计时完成的专注次数 + 当天勾选完成的任务数。
     *
     * [skipped] 是 TaskSkipStore 里「该日不要出现」的键集合。重复任务用「只删除这一天的」
     * 把某天藏起来时，只是记了一条 skip，当天的完成快照行仍留在库里 —— 不过滤的话，
     * 用户明明删掉的那一天还会在趋势线上继续计一笔。
     */
    suspend fun getDailyCountsBetween(
        start: Long,
        end: Long,
        skipped: Set<String> = emptySet()
    ): List<DailyCount> = withContext(Dispatchers.IO) {
        val startTimes = recordDao.getFocusStartTimesBetween(start, end)
        val labelFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val counts = LinkedHashMap<String, Int>()
        var day = start
        while (day < end) {
            counts[labelFormat.format(Date(day))] = 0
            day += DAY_MILLIS
        }
        startTimes.forEach { startTime ->
            val label = labelFormat.format(Date(startTime))
            counts[label] = (counts[label] ?: 0) + 1
        }
        // 任务清单中直接勾选完成的任务，也应当反映在当周趋势线上。
        // 只认「清单里真的能看见」的那部分 —— 判据见 visibleCompletions。
        visibleCompletions(taskDao.getAllTasks(), skipped)
            .filter { it.dueDate!! >= start && it.dueDate!! < end }
            .forEach { task ->
                val label = labelFormat.format(Date(task.dueDate!!))
                counts[label] = (counts[label] ?: 0) + 1
            }
        counts.map { DailyCount(it.key, it.value) }
    }

    fun getAllCountdowns() = countdownDao.getAll()

    /** 一次性取回全部倒数日（导 CSV 等只需要当前快照的场景）。 */
    suspend fun getAllCountdownsOnce(): List<Countdown> = withContext(Dispatchers.IO) {
        countdownDao.getAll().first()
    }

    suspend fun insertCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.insert(countdown)
    }

    suspend fun updateCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.update(countdown)
    }

    /** 移入回收站（与任务的删除同义，只是不在同一张表）。 */
    suspend fun deleteCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.trash(countdown.id, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ 回收站

    /** 回收站里的任务行。同一个重复系列的模板与快照都在里面，聚合由上层做。 */
    suspend fun getTrashedTasks() = withContext(Dispatchers.IO) {
        taskDao.getTrashedTasks()
    }

    suspend fun getTrashedCountdowns() = withContext(Dispatchers.IO) {
        countdownDao.getTrashed()
    }

    /** 还原整个重复系列（单条任务就是它自己的 id）。 */
    suspend fun restoreTaskSeries(seriesId: Int) = withContext(Dispatchers.IO) {
        taskDao.restoreTaskSeries(seriesId)
    }

    suspend fun restoreCountdown(id: Int) = withContext(Dispatchers.IO) {
        countdownDao.restore(id)
    }

    /** 彻底删除整个系列；子任务由外键 CASCADE 连带清掉。 */
    suspend fun purgeTaskSeries(seriesId: Int) = withContext(Dispatchers.IO) {
        taskDao.purgeTaskSeries(seriesId)
    }

    suspend fun purgeCountdown(id: Int) = withContext(Dispatchers.IO) {
        countdownDao.purge(id)
    }

    /** 清空回收站（任务 + 倒数日 + 习惯）。便贴不在这 —— 它存在 prefs 里，见 NoteStore。 */
    suspend fun purgeAllTrash() = withContext(Dispatchers.IO) {
        taskDao.purgeAllTrashedTasks()
        countdownDao.purgeAllTrashed()
        // 日志必须先清：习惯行一删，就再也查不出「哪些日志属于已删习惯」了
        habitDao.purgeLogsOfTrashed()
        habitDao.purgeAllTrashed()
    }

    /**
     * 回收站保留期到期清理：删除时间早于 `now - keepMillis` 的行真删。
     *
     * 习惯同样是先日志后主体 —— 反过来的话，习惯行没了，它的日志就成了孤儿，
     * 库里留着、导出时也会多出一堆没人认领的行。
     */
    suspend fun purgeExpiredTrash(keepMillis: Long) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - keepMillis
        taskDao.purgeExpiredTasks(cutoff)
        countdownDao.purgeExpired(cutoff)
        habitDao.purgeLogsForExpired(cutoff)
        habitDao.purgeExpired(cutoff)
    }

    // ------------------------------------------------------------------ 小习惯打卡

    suspend fun getHabits(): List<Habit> = withContext(Dispatchers.IO) { habitDao.getAll() }

    suspend fun getHabit(id: Int): Habit? = withContext(Dispatchers.IO) { habitDao.getById(id) }

    /** 新增；返回新 id（撞 id 被跳过时返回 -1）。导入时会带上原备份里的 id。 */
    suspend fun addHabit(habit: Habit): Long = withContext(Dispatchers.IO) {
        habitDao.insert(habit)
    }

    suspend fun updateHabit(habit: Habit) = withContext(Dispatchers.IO) { habitDao.update(habit) }

    /** 删除习惯 = 移入回收站。日志原样留着，还原回来历史就还在。 */
    suspend fun deleteHabit(id: Int) = withContext(Dispatchers.IO) {
        habitDao.trash(id, System.currentTimeMillis())
    }

    /**
     * 打卡 +1，返回打卡后的次数（界面直接拿去更新那一行）。
     *
     * 先试 UPDATE：命中说明今天已经有记录，返回 0 行才是「今天第一次打」，此时插一行。
     * 不用 `INSERT OR REPLACE` —— 那会换掉主键，也绕开了 (habitId, day) 的唯一约束。
     */
    suspend fun punchHabit(habitId: Int, day: Long): Int = withContext(Dispatchers.IO) {
        if (habitDao.bumpLog(habitId, day) == 0) {
            habitDao.insertLog(HabitLog(habitId = habitId, day = day, count = 1))
            1
        } else {
            habitDao.findLog(habitId, day)?.count ?: 1
        }
    }

    /** 撤销某天的打卡（界面上的「撤销今天」）。 */
    suspend fun undoHabit(habitId: Int, day: Long) = withContext(Dispatchers.IO) {
        habitDao.clearLog(habitId, day)
    }

    /** 某天起的全部日志。一次取回算今日进度与连续天数，免得每个习惯查一次库。 */
    suspend fun getHabitLogsSince(fromDay: Long): List<HabitLog> = withContext(Dispatchers.IO) {
        habitDao.getLogsSince(fromDay)
    }

    /** 某个习惯的全部日志。 */
    suspend fun getHabitLogs(habitId: Int): List<HabitLog> = withContext(Dispatchers.IO) {
        habitDao.getLogsFor(habitId)
    }

    /** 导出用的全量日志。**含已删习惯的** —— 还原之后历史还在（见 [HabitDao] 类注释）。 */
    suspend fun getAllHabitLogs(): List<HabitLog> = withContext(Dispatchers.IO) {
        habitDao.getAllLogs()
    }

    suspend fun getTrashedHabits(): List<Habit> = withContext(Dispatchers.IO) { habitDao.getTrashed() }

    suspend fun restoreHabit(id: Int) = withContext(Dispatchers.IO) { habitDao.restore(id) }

    /** 彻底删除习惯：连同它的全部日志，否则库里会留下认不出主人的行。 */
    suspend fun purgeHabit(id: Int) = withContext(Dispatchers.IO) {
        habitDao.purgeLogsFor(id)
        habitDao.purge(id)
    }

    /**
     * 导入用：按备份里原来的 id 整批写回（撞 id 的行由 DAO 的 IGNORE 跳过，不覆盖已有数据）。
     * 带上原 id 才有意义 —— 打卡记录的「习惯编号」指的就是它。
     */
    suspend fun restoreHabits(habits: List<Habit>) = withContext(Dispatchers.IO) {
        habits.forEach { habitDao.insert(it) }
    }

    /**
     * 导入用：按备份里的次数整条写回某天的记录。
     * 已经存在的那天不动（同「不覆盖已有数据」的总口径），所以先 UPDATE、没命中才 INSERT。
     */
    suspend fun restoreHabitLog(log: HabitLog) = withContext(Dispatchers.IO) {
        if (habitDao.setLogCount(log.habitId, log.day, log.count) == 0) {
            habitDao.insertLog(log)
        }
    }

    /**
     * 重复任务的「模板」行（repeatRule != none 且 templateId == 0）永远不该带完成标记。
     *
     * 它的完成态由当天的快照行（templateId != 0）表达，模板本身只描述「这个任务每天出现」。
     * 一旦模板残留 isCompleted=true，任务清单里后面每一天都会显示成已完成、周条 7 天全满，
     * 而且取消也取消不掉 —— 删掉当天快照后拿出来的虚拟实例还是继承着那个 true。
     *
     * 写入侧兜这么一道：把已完成的普通任务在编辑对话框里改成「每日」时，
     * `task.copy(repeatRule = "daily")` 会把旧的 isCompleted 一起带过去，
     * 数据库里那条一次性清理 SQL 只在版本升级时跑过，管不了之后新写进来的值。
     */
    private fun Task.normalizeRepeatTemplate(): Task =
        if (isRepeatTemplate()) copy(isCompleted = false) else this

    /** 重复任务模板：既是重复规则、又不是某天的快照。它不参与完成统计。 */
    private fun Task.isRepeatTemplate(): Boolean = templateId == 0 && repeatRule != "none"

    /**
     * 库里所有「该计入统计」的完成记录。
     *
     * 判据是 completionIsVisible —— 它保证统计口径与任务清单里「这条记录能不能被看到」
     * 完全一致：界面上看不见的完成记录，趋势线和今日/近七日数字里都不该有一笔。
     */
    private fun visibleCompletions(allTasks: List<Task>, skipped: Set<String>): List<Task> {
        val templates = allTasks
            .filter { it.templateId == 0 && it.repeatRule != TaskViewModel.REPEAT_NONE }
            .associateBy { it.id }
        return allTasks.filter { task ->
            val day = task.dueDate
            task.isCompleted && day != null && completionIsVisible(task, day, templates, skipped)
        }
    }

    /**
     * 一条完成记录在任务清单里是否可见（= 是否计入统计）。
     *
     * 三类幽灵记录都是从这个口子漏进来的，现象都叫「任务删了，趋势线还顶着」：
     * 1. 重复任务模板：残留的 isCompleted=true 是脏值，模板本身从不代表「完成过一次」；
     * 2. 「仅删除该事件」只在 TaskSkipStore 记了一条，当天的完成快照还留在库里；
     * 3. 规则被改过（每天 → 每周）或模板已被删除，快照的日期落在规则覆盖之外 ——
     *    清单里的快照由模板带出，带不出来就永远不显示，但统计照旧给它算一笔。
     */
    private fun completionIsVisible(
        task: Task,
        day: Long,
        templates: Map<Int, Task>,
        skipped: Set<String>
    ): Boolean {
        if (task.templateId == 0) return task.repeatRule == TaskViewModel.REPEAT_NONE
        val template = templates[task.templateId] ?: return false
        if (TaskSkipStore.key(task.templateId, day) in skipped) return false
        val start = startOfDay(template.dueDate ?: template.createdAt)
        return day >= start && RepeatRule.covers(template.repeatRule, start, day)
    }

    private fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

    private fun startOfDay(timeMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * 回收站保留期。到期由冷启动时的 [purgeExpiredTrash] 真删，
         * 界面上「剩 N 天」也算自这里，两边必须同一个来源。
         */
        const val TRASH_KEEP_MILLIS = 30L * DAY_MILLIS
    }
}
