package com.xian.focus.data

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
    private val countdownDao: CountdownDao
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

    suspend fun deleteTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.deleteTask(task)
    }

    /** 删除整个重复系列：模板 + 全部快照。 */
    suspend fun deleteTaskSeries(templateId: Int) = withContext(Dispatchers.IO) {
        taskDao.deleteTemplateWithSnapshots(templateId)
    }

    /**
     * 删除整个重复系列，但把已完成的那几天留作历史。
     *
     * 两步顺序不能换：必须先把已完成的快照摘出来（templateId 置 0），
     * 否则第二步按 templateId 删的时候会把它们一起带走。
     */
    suspend fun deleteTaskSeriesKeepCompleted(templateId: Int) = withContext(Dispatchers.IO) {
        taskDao.detachCompletedSnapshots(templateId)
        taskDao.deleteTemplateAndUnfinishedSnapshots(templateId)
    }

    suspend fun getTaskById(id: Int) = withContext(Dispatchers.IO) {
        taskDao.getTaskById(id)
    }

    suspend fun getCompletedSnapshot(templateId: Int, dueDate: Long) = withContext(Dispatchers.IO) {
        taskDao.getCompletedSnapshot(templateId, dueDate)
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

    suspend fun getFocusStats(): FocusStats = withContext(Dispatchers.IO) {
        val dayStart = startOfToday()
        val dayEnd = dayStart + DAY_MILLIS
        val weekStart = dayStart - 6L * DAY_MILLIS
        // 直接勾选完成的任务也计入统计，与柱状图口径一致。
        // 排除重复任务模板：它残留的 isCompleted=true 是历史脏值，不是真的完成过一次。
        val allCompletedTasks = taskDao.getAllTasks()
            .filter { it.isCompleted && it.dueDate != null && !it.isRepeatTemplate() }
        val todayTaskCount = allCompletedTasks.count { it.dueDate!! >= dayStart && it.dueDate!! < dayEnd }
        val weekTaskCount = allCompletedTasks.count { it.dueDate!! >= weekStart && it.dueDate!! < dayEnd }
        val totalTaskCount = allCompletedTasks.size
        FocusStats(
            todayCount = recordDao.getCompletedFocusCountBetween(dayStart, dayEnd) + todayTaskCount,
            lastSevenDaysCount = recordDao.getCompletedFocusCountBetween(weekStart, dayEnd) + weekTaskCount,
            totalCount = recordDao.getTotalCompletedFocusCount() + totalTaskCount,
            totalMinutes = recordDao.getTotalFocusMinutes()
        )
    }

    suspend fun getLastSevenDaysCounts(): List<DailyCount> {
        val dayStart = startOfToday()
        return getDailyCountsBetween(dayStart - 6L * DAY_MILLIS, dayStart + DAY_MILLIS)
    }

    suspend fun getDailyCountsBetween(start: Long, end: Long): List<DailyCount> = withContext(Dispatchers.IO) {
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
        // 同样排除重复任务模板（完成态由当天的快照行代表，避免同一天被算两次）。
        taskDao.getAllTasks()
            .asSequence()
            .filter {
                it.isCompleted && it.dueDate != null && !it.isRepeatTemplate() &&
                    it.dueDate >= start && it.dueDate < end
            }
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

    suspend fun deleteCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.delete(countdown)
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

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
