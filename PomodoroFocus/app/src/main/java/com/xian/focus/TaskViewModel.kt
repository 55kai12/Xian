package com.xian.focus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Subtask
import com.xian.focus.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskViewModel @Inject constructor(
    private val repository: FocusRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _pendingTasks = MutableStateFlow<List<Task>>(emptyList())
    val pendingTasks: StateFlow<List<Task>> = _pendingTasks.asStateFlow()

    private val _completedTasks = MutableStateFlow<List<Task>>(emptyList())
    val completedTasks: StateFlow<List<Task>> = _completedTasks.asStateFlow()

    private val _subtasks = MutableStateFlow<List<Subtask>>(emptyList())
    val subtasks: StateFlow<List<Subtask>> = _subtasks.asStateFlow()

    private val _groups = MutableStateFlow(emptyList<String>())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var selectedGroup: String? = null

    init {
        refresh()
        refreshGroups()
        refreshSubtasks()
        rescheduleReminders()
    }

    /**
     * 冷启动时按库里的任务整体重建提醒闹钟。
     * 只靠「保存任务时排闹钟」的话，升级前就存在的任务、以及从倒数日那边建的任务
     * 永远不会被提醒 —— 又是一次静默失效。
     * 幂等：requestCode 就是任务 id，重复排等于原地覆盖。
     *
     * 回收站还原任务之后也要敲一下（删的时候闹钟被撤了，还原得挂回去）。
     */
    fun rescheduleReminders() = viewModelScope.launch {
        runCatching { repository.getAllTasks() }.getOrNull()?.forEach(::syncReminder)
    }

    fun setGroupFilter(group: String?) {
        selectedGroup = group?.takeUnless { it == ALL_GROUPS }
        refresh()
    }

    fun addTask(task: Task, subtaskTitles: List<String>) = execute {
        val taskId = repository.insertTask(task).toInt()
        subtaskTitles.filter { it.isNotBlank() }.forEach {
            repository.insertSubtask(Subtask(taskId = taskId, title = it.trim()))
        }
        syncReminder(task.copy(id = taskId))
        doRefresh()
    }

    fun updateTask(task: Task) = execute {
        repository.updateTask(task)
        syncReminder(task)
        doRefresh()
    }

    fun updateTaskWithSubtasks(task: Task, subtaskTitles: List<String>) = execute {
        repository.updateTask(task)
        syncReminder(task)
        // 保留已有子任务的完成状态。
        // 旧实现是无条件「删光再重插」，而 Subtask.isCompleted 默认 false，
        // 于是用户哪怕只改了个标题，所有已勾选的子任务也会被静默重置（数据丢失、无提示）。
        // 编辑弹窗里每行只带标题、没有 id，所以按标题配对；同名标题按出现顺序逐个消费。
        val completedByTitle = HashMap<String, ArrayDeque<Boolean>>()
        repository.getSubtasksByTaskId(task.id).forEach { subtask ->
            completedByTitle.getOrPut(subtask.title) { ArrayDeque() }.addLast(subtask.isCompleted)
        }
        repository.deleteSubtasksByTaskId(task.id)
        subtaskTitles.filter { it.isNotBlank() }.forEach { raw ->
            val title = raw.trim()
            val queue = completedByTitle[title]
            val isCompleted = if (queue.isNullOrEmpty()) false else queue.removeFirst()
            repository.insertSubtask(
                Subtask(taskId = task.id, title = title, isCompleted = isCompleted)
            )
        }
        doRefresh()
    }

    fun reorderTasks(tasks: List<Task>) = execute {
        repository.updateTasks(tasks.mapIndexed { index, task -> task.copy(sortOrder = index.toLong()) })
    }

    fun toggleSubtask(subtask: Subtask) = execute {
        repository.updateSubtask(subtask.copy(isCompleted = !subtask.isCompleted))
    }

    fun deleteTask(task: Task) = execute {
        TaskReminderScheduler.cancel(appContext, task.id)
        repository.deleteTask(task)
        doRefresh()
    }

    /** 删除整个重复系列（模板 + 全部快照）。 */
    fun deleteTaskSeries(templateId: Int) = execute {
        TaskReminderScheduler.cancel(appContext, templateId)
        repository.deleteTaskSeries(templateId)
        doRefresh()
    }

    /** 删除整个重复系列，已完成的那几天留作普通历史任务。 */
    fun deleteTaskSeriesKeepCompleted(templateId: Int) = execute {
        TaskReminderScheduler.cancel(appContext, templateId)
        repository.deleteTaskSeriesKeepCompleted(templateId)
        doRefresh()
    }

    fun toggleComplete(task: Task, onFinished: (() -> Unit)? = null) = execute(onFinished) {
        val occurrenceDate = task.dueDate
        when {
            // 重复任务模板：勾选当天 = 写入"当天完成快照"，模板本身保持未完成，之后每天自动出现
            task.repeatRule != REPEAT_NONE && task.templateId == 0 && occurrenceDate != null -> {
                if (repository.getCompletedSnapshot(task.id, occurrenceDate) == null) {
                    repository.insertTask(
                        task.copy(
                            id = 0,
                            isCompleted = true,
                            completedPomodoros = task.estimatedPomodoros,
                            dueDate = occurrenceDate,
                            templateId = task.id,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            // 已完成的快照：取消勾选 = 删除当天快照，模板当天恢复未完成
            task.templateId != 0 && task.isCompleted && occurrenceDate != null -> {
                repository.deleteCompletedSnapshot(task.templateId, occurrenceDate)
            }
            // 普通任务：正常切换完成状态
            else -> {
                val updated = task.copy(isCompleted = !task.isCompleted)
                repository.updateTask(updated)
                syncReminder(updated)
            }
        }
        doRefresh()
    }

    /**
     * 任务增删改后同步到期提醒闹钟。
     *
     * 只处理有截止日期的普通任务。重复任务的模板 dueDate 会随当天推进，
     * 而闹钟是一次性的，这里不做每日重排 —— 滚动规则没人验证过，
     * 与其塞一版可能会错发/漏发的实现，不如先明确留白。
     */
    private fun syncReminder(task: Task) {
        if (task.repeatRule == REPEAT_NONE && task.dueDate != null && !task.isCompleted) {
            TaskReminderScheduler.schedule(appContext, task)
        } else {
            TaskReminderScheduler.cancel(appContext, task.id)
        }
    }

    fun refresh() = viewModelScope.launch { doRefresh() }

    private suspend fun doRefresh() {
        // 任务清单保留已完成任务，以便用户查看并恢复完成状态。
        _pendingTasks.value = if (selectedGroup.isNullOrBlank()) {
            repository.getAllTasks()
        } else {
            repository.getTasksByListType(selectedGroup!!)
        }
        _completedTasks.value = repository.getCompletedTasks(selectedGroup)
    }

    fun refreshGroups() = viewModelScope.launch {
        val storedGroups = repository.getListTypes()
        _groups.value = storedGroups.distinct().sorted().filter { it.isNotBlank() }
    }

    fun refreshSubtasks() = viewModelScope.launch {
        _subtasks.value = repository.getAllSubtasks()
    }

    private fun execute(onFinished: (() -> Unit)? = null, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                refresh().join()
                refreshGroups().join()
                refreshSubtasks().join()
                onFinished?.invoke()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    companion object {
        /** 仅作内部哨兵值，不直接展示。 */
        const val ALL_GROUPS = "全部"

        /**
         * 注意：分类名是**用户数据**，会写进 tasks.listType。
         * 所以它是中文常量而不是字符串资源 —— 翻译它会让老任务的分类凭空消失。
         * 界面上的默认分类名由各处的 getString(R.string.category_*) 提供。
         */
        const val DEFAULT_GROUP = "未分类"
        const val REPEAT_NONE = "none"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_MONTHLY = "monthly"
    }
}
