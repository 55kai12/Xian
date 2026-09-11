package com.xian.focus.data

import kotlinx.coroutines.Dispatchers
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

    suspend fun updateTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.updateTask(task)
    }

    suspend fun updateTasks(tasks: List<Task>) = withContext(Dispatchers.IO) {
        taskDao.updateTasks(tasks)
    }

    suspend fun deleteTask(task: Task) = withContext(Dispatchers.IO) {
        taskDao.deleteTask(task)
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
        // 直接勾选完成的任务也计入统计，与柱状图口径一致
        val allCompletedTasks = taskDao.getAllTasks().filter { it.isCompleted && it.dueDate != null }
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
        taskDao.getAllTasks()
            .asSequence()
            .filter { it.isCompleted && it.dueDate != null && it.dueDate >= start && it.dueDate < end }
            .forEach { task ->
                val label = labelFormat.format(Date(task.dueDate!!))
                counts[label] = (counts[label] ?: 0) + 1
            }
        counts.map { DailyCount(it.key, it.value) }
    }

    fun getAllCountdowns() = countdownDao.getAll()

    suspend fun insertCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.insert(countdown)
    }

    suspend fun updateCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.update(countdown)
    }

    suspend fun deleteCountdown(countdown: Countdown) = withContext(Dispatchers.IO) {
        countdownDao.delete(countdown)
    }

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
