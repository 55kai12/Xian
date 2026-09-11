package com.xian.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Subtask
import com.xian.focus.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class TaskViewModel @Inject constructor(
    private val repository: FocusRepository
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
        doRefresh()
    }

    fun updateTask(task: Task) = execute {
        repository.updateTask(task)
        doRefresh()
    }

    fun updateTaskWithSubtasks(task: Task, subtaskTitles: List<String>) = execute {
        repository.updateTask(task)
        repository.deleteSubtasksByTaskId(task.id)
        subtaskTitles.filter { it.isNotBlank() }.forEach {
            repository.insertSubtask(Subtask(taskId = task.id, title = it.trim()))
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
        repository.deleteTask(task)
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
            else -> repository.updateTask(task.copy(isCompleted = !task.isCompleted))
        }
        doRefresh()
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

    private fun nextDueDate(current: Long, rule: String): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = current }
        when (rule) {
            REPEAT_DAILY -> calendar.add(Calendar.DAY_OF_MONTH, 1)
            REPEAT_WEEKLY -> calendar.add(Calendar.DAY_OF_MONTH, 7)
            REPEAT_MONTHLY -> calendar.add(Calendar.MONTH, 1)
        }
        return calendar.timeInMillis
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
        const val ALL_GROUPS = "全部"
        const val DEFAULT_GROUP = "未分类"
        const val REPEAT_NONE = "none"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_MONTHLY = "monthly"
    }
}
