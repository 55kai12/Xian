package com.xian.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.Countdown
import com.xian.focus.data.CountdownCalendar
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

@HiltViewModel
class CountdownViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FocusRepository
) : ViewModel() {

    private val _countdowns = MutableStateFlow<List<Countdown>>(emptyList())
    val countdowns: StateFlow<List<Countdown>> = _countdowns.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 排序方式的「手动重发」信号。
     * 「按剩余天数」这类排序依赖的是 prefs，而 Room 的流只在数据变动时才发 ——
     * 用户在设置页换了排序方式回来，列表顺序不会自己变，得靠这个信号敲一下。
     */
    private val sortTick = MutableStateFlow(0)

    init {
        // 冷启动重建提醒闹钟：升级前就有的事件、以及重启后丢失的闹钟都靠这一步补回来
        // （幂等，requestCode 固定，重复排等于原地覆盖）。放在 collect 外面，避免每次
        // 数据变动都全量重排一遍。
        viewModelScope.launch {
            runCatching { repository.getAllCountdownsOnce() }
                .getOrNull()
                ?.forEach { CountdownReminderScheduler.sync(context, it) }
        }
        viewModelScope.launch {
            combine(repository.getAllCountdowns(), sortTick) { list, _ -> list }
                .collect { list -> _countdowns.value = sortCountdowns(list) }
        }
    }

    /** 排序方式变了就调一下，让列表按新规则重排。 */
    fun refreshSort() {
        sortTick.value++
    }

    private fun sortCountdowns(list: List<Countdown>): List<Countdown> = when (getSortBy()) {
        "target_date" -> list.sortedBy { getEffectiveTargetDate(it) }
        "created_at" -> list.sortedByDescending { it.createdAt }
        else -> list.sortedWith(compareBy(
            { getDaysRemaining(it) < 0 },
            { kotlin.math.abs(getDaysRemaining(it)) }
        ))
    }

    fun addCountdown(raw: Countdown) = execute {
        val countdown = raw
        val countdownId = repository.insertCountdown(countdown).toInt()
        var linkedTaskId = 0
        if (isAutoLinkTaskEnabled()) {
            val effectiveDate = startOfDay(getEffectiveTargetDate(countdown))
            linkedTaskId = repository.insertTask(
            Task(
                title = countdown.title,
                description = context.getString(R.string.countdown_linked_task),
                priority = 0,
                estimatedPomodoros = 0,
                completedPomodoros = 0,
                isCompleted = false,
                dueDate = effectiveDate,
                dueTimeMinutes = null,
                createdAt = System.currentTimeMillis(),
                sortOrder = System.currentTimeMillis(),
                imageUri = null,
                // 分类名是用户数据（写进 tasks.listType），刻意保持中文常量，不随界面语言变。
                listType = "倒数日",
                repeatRule = if (countdown.repeatYearly) "yearly" else "none"
            )
            ).toInt()
        }
        val saved = countdown.copy(id = countdownId, linkedTaskId = linkedTaskId)
        repository.updateCountdown(saved)
        CountdownReminderScheduler.sync(context, saved)
    }

    fun updateCountdown(raw: Countdown) = execute {
        val countdown = raw
        repository.updateCountdown(countdown)
        CountdownReminderScheduler.sync(context, countdown)
        // 同步更新关联任务
        if (countdown.linkedTaskId > 0) {
            val task = repository.getTaskById(countdown.linkedTaskId)
            if (task != null) {
                val effectiveDate = startOfDay(getEffectiveTargetDate(countdown))
                repository.updateTask(
                    task.copy(
                        title = countdown.title,
                        dueDate = effectiveDate,
                        repeatRule = if (countdown.repeatYearly) "yearly" else "none"
                    )
                )
            }
        }
    }

    fun deleteCountdown(countdown: Countdown) = execute {
        CountdownReminderScheduler.cancel(context, countdown.id)
        repository.deleteCountdown(countdown)
        // 同步删除关联任务
        if (countdown.linkedTaskId > 0) {
            val task = repository.getTaskById(countdown.linkedTaskId)
            if (task != null) {
                repository.deleteTask(task)
            }
        }
    }

    /** 计算倒数日的有效目标日期（处理每年重复）。口径收在 [CountdownReminderScheduler] 里，提醒与列表共用。 */
    fun getEffectiveTargetDate(countdown: Countdown): Long =
        CountdownReminderScheduler.effectiveTargetDate(countdown)

    /** 计算剩余天数（正数=还有几天，负数=已过几天） */
    fun getDaysRemaining(countdown: Countdown): Int {
        val effective = getEffectiveTargetDate(countdown)
        val today = startOfToday()
        return ((effective - today) / (24L * 60 * 60 * 1000)).toInt()
    }

    private fun isAutoLinkTaskEnabled(): Boolean =
        context.getSharedPreferences("event_settings", 0).getBoolean("auto_link_task", true)

    private fun getSortBy(): String? =
        context.getSharedPreferences("event_settings", 0).getString("sort_by", "days_remaining")

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(timeMillis: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = timeMillis
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun execute(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }
}
