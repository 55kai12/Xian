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
        //
        // ⚠️ v2.0.94：重复任务的**各天勾选记录不能删**。它只删「模板行」
        // （dueDate IS NULL），各天（dueDate=某天）的行原样留着 —— 改个标题不该把
        // 这周勾过的全清掉。代价是标题改了之后，历史那些天的记录还挂着旧标题；
        // 但那些天已经过去了、不会再展示，所以不处理。
        // ⚠️ 子任务只挂在**所有者**（重复任务的模板行）上，快照（当天完成 / 移到今天写出的
        // 那一行）只是那天的实例、id 与模板不同 —— 拿快照自己的 id 去读写会另立一份行，
        // 模板那份还留在原地，表现就是「编辑完子任务，清单上没有任何变化」。
        val ownerId = task.subtaskOwnerId()
        // 「按天存」的判据也一律看**所有者**：快照会继承模板的 repeatRule，拿快照自己判
        // 会得出同样的结论，但 id 一旦用错就可能把模板的各天勾选记录整片删掉。
        val owner = if (ownerId == task.id) task else repository.getTaskById(ownerId)
        val isRepeating = (owner ?: task).repeatRule != REPEAT_NONE
        val completedByTitle = HashMap<String, ArrayDeque<Boolean>>()
        if (isRepeating) {
            repository.getSubtasksByTaskId(ownerId)
                .filter { it.dueDate == null }
                .forEach { subtask ->
                    completedByTitle.getOrPut(subtask.title) { ArrayDeque() }.addLast(subtask.isCompleted)
                }
            repository.deleteSubtaskTemplates(ownerId)
        } else {
            repository.getSubtasksByTaskId(ownerId).forEach { subtask ->
                completedByTitle.getOrPut(subtask.title) { ArrayDeque() }.addLast(subtask.isCompleted)
            }
            repository.deleteSubtasksByTaskId(ownerId)
        }
        subtaskTitles.filter { it.isNotBlank() }.forEach { raw ->
            val title = raw.trim()
            val queue = completedByTitle[title]
            val isCompleted = if (queue.isNullOrEmpty()) false else queue.removeFirst()
            repository.insertSubtask(
                Subtask(taskId = ownerId, title = title, isCompleted = isCompleted)
            )
        }
        doRefresh()
    }

    /**
     * 按显示顺序写 `sortOrder`。
     *
     * ⚠️ 传进来的多半是**显示对象**（适配器 `currentList`），其中重复任务的未完成实例是
     * 「模板行的当天副本」—— `id` 就是模板 id、`dueDate` 被改成了当前查看的那一天。
     * 所以这里**只认 id**，落库也只改 `sortOrder` 一列：把这种副本整行写回会把模板的
     * `dueDate` 一起覆盖（锚点被推到今天 ⇒ 早于那天的重复实例全部消失，v2.1.10 修）。
     */
    fun reorderTasks(tasks: List<Task>) = execute {
        repository.updateTaskSortOrders(tasks.map { it.id })
    }

    /**
     * 勾选 / 取消勾选一个子任务。
     *
     * [day] 非空表示「这是重复任务的某一天」，勾选状态只写进那一天，别的日期不受影响
     * （v2.0.94 之前所有日期共用同一行，于是「昨天勾了今天也跟着勾上」）。
     *
     * 写法的选择：**重复任务不复制整套子任务**，只在那天还没有对应行时才插一条。
     * 因此 [Subtask.id] 为 0（适配器现造的「那天还没人勾过」的占位行）时按插入处理，
     * 其余按更新 —— 更新只动 `isCompleted`，标题以模板为准。
     */
    fun toggleSubtask(subtask: Subtask, day: Long? = null) = execute {
        if (day == null) {
            // 普通任务 / 不分日期的老路径：一行一子任务，直接改。
            repository.updateSubtask(subtask.copy(isCompleted = !subtask.isCompleted))
            autoCompleteParent(subtask.taskId, null)
            return@execute
        }
        if (subtask.id == 0) {
            // 这天还没为这个子任务建过行 ⇒ 插一条已完成的。
            repository.insertSubtask(
                Subtask(
                    taskId = subtask.taskId,
                    title = subtask.title,
                    isCompleted = true,
                    dueDate = day
                )
            )
        } else {
            repository.updateSubtask(subtask.copy(isCompleted = !subtask.isCompleted))
        }
        autoCompleteParent(subtask.taskId, day)
    }

    /**
     * 「子任务全勾完 ⇒ 顺便把任务也勾上」（设置里可关，**默认关**）。
     *
     * ⚠️ 只做**单向**：取消某个子任务**不会**把任务退回未完成。任务可能是用户自己
     * 手勾的、可能是这条规则刚才替他勾的，两种来源分不出来 —— 分不出来就不猜，
     * 免得把他手动完成的记录悄悄抹掉。
     *
     * [day] 的语义与 [toggleSubtask] 一致：重复任务的模板必须指明是哪一天，
     * 否则「昨天全勾完了」会被算成「今天也全勾完了」。
     */
    private suspend fun autoCompleteParent(taskId: Int, day: Long?) {
        val prefs = appContext.getSharedPreferences("event_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SUBTASK_AUTO_COMPLETE, false)) return
        val task = repository.getTaskById(taskId) ?: return
        if (task.isCompleted) return
        // 只有模板行才有「按天」的概念；快照是独立的一行任务，它的子任务不分天。
        val isTemplate = task.isRepeatTemplate()
        val visible = subtasksForDay(
            repository.getSubtasksByTaskId(taskId), taskId, day, isTemplate
        )
        if (visible.isEmpty() || visible.any { !it.isCompleted }) return
        // 重复模板要把「哪一天」写进 dueDate —— 模板自己的 dueDate 是起始日，不是今天。
        markCompleted(
            if (isTemplate && day != null) task.copy(dueDate = startOfDayMillis(day)) else task
        )
    }

    /**
     * 把一个任务标记为已完成。重复模板 ⇒ 写那天（`task.dueDate`）的完成快照，
     * 模板本身保持未完成、明天照常出现。
     *
     * 调用方负责把目标日期写进 `task.dueDate`（见 [toggleComplete] / [autoCompleteParent]）。
     */
    private suspend fun markCompleted(task: Task) {
        val occurrenceDate = task.dueDate
        if (task.isRepeatTemplate() && occurrenceDate != null) {
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
            return
        }
        val updated = task.copy(isCompleted = true)
        repository.updateTask(updated)
        syncReminder(updated)
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
            task.isRepeatTemplate() && occurrenceDate != null -> markCompleted(task)
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

    /**
     * 把一批任务挪到指定那一天（「昨天未完成 → 全部移到今天」用）。
     *
     * ⚠️ **重复任务一律跳过**（v2.1.8）—— 模板（`repeatRule != none && templateId == 0`）
     * 和它的快照（`templateId != 0`）都不动。理由：模板是**规则**，它每天本来就把实例
     * 带出来一份，「今天」那条已经在了，压根没有可挪的东西。硬挪只有两种干法，都错：
     *
     *  · 在目标日写快照 —— 把「虚拟实例」钉成一条实体行，用户看到的就是
     *    「重复任务也被移了」这个多余动作，还会在「已移 N 条」里虚报一条；
     *  · 改模板的 `dueDate` —— 锚点被污染，之后的重复日期整体漂移。
     *
     * 只处理普通任务：直接改 `dueDate`。
     *
     * 返回真的动过手的条数：调用方拿它做提示，比传进来的总数诚实
     * （昨天有 3 条、其中 2 条是重复任务 ⇒ 只说「已移 1 条」）。
     */
    suspend fun moveTasksToDay(tasks: List<Task>, targetDay: Long): Int {
        var moved = 0
        tasks.forEach { task ->
            // 重复系列（模板 + 它的快照）没有「挪」的概念，跳过。
            // 判据与「昨天未完成」横幅同源（Task.isRepeatSeries），别在这另写一份。
            if (task.isRepeatSeries()) return@forEach
            runCatching {
                repository.updateTask(task.copy(dueDate = targetDay))
                moved++
            }
        }
        if (moved > 0) doRefresh()
        return moved
    }

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

        /**
         * 「子任务全部勾完 ⇒ 顺手把任务也勾上」的开关，存在 `event_settings` 里
         * （和「显示已完成」那批同类设置放一起，见 EventSettingsFragment）。
         * **默认关**：新行为不主动改老用户的既有习惯。
         */
        const val KEY_SUBTASK_AUTO_COMPLETE = "subtask_auto_complete"
    }
}
