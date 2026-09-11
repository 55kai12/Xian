package com.xian.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.Countdown
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        viewModelScope.launch {
            repository.getAllCountdowns().collect { list ->
                _countdowns.value = when (getSortBy()) {
                    "target_date" -> list.sortedBy { getEffectiveTargetDate(it) }
                    "created_at" -> list.sortedByDescending { it.createdAt }
                    else -> list.sortedWith(compareBy(
                        { getDaysRemaining(it) < 0 },
                        { kotlin.math.abs(getDaysRemaining(it)) }
                    ))
                }
            }
        }
    }

    fun addCountdown(countdown: Countdown) = execute {
        val countdownId = repository.insertCountdown(countdown).toInt()
        var linkedTaskId = 0
        if (isAutoLinkTaskEnabled()) {
            val effectiveDate = startOfDay(getEffectiveTargetDate(countdown))
            linkedTaskId = repository.insertTask(
            Task(
                title = countdown.title,
                description = "倒数日关联任务",
                priority = 0,
                estimatedPomodoros = 0,
                completedPomodoros = 0,
                isCompleted = false,
                dueDate = effectiveDate,
                dueTimeMinutes = null,
                createdAt = System.currentTimeMillis(),
                sortOrder = System.currentTimeMillis(),
                imageUri = null,
                listType = "倒数日",
                repeatRule = if (countdown.repeatYearly) "yearly" else "none"
            )
            ).toInt()
        }
        repository.updateCountdown(countdown.copy(id = countdownId, linkedTaskId = linkedTaskId))
    }

    fun updateCountdown(countdown: Countdown) = execute {
        repository.updateCountdown(countdown)
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
        repository.deleteCountdown(countdown)
        // 同步删除关联任务
        if (countdown.linkedTaskId > 0) {
            val task = repository.getTaskById(countdown.linkedTaskId)
            if (task != null) {
                repository.deleteTask(task)
            }
        }
    }

    /** 计算倒数日的有效目标日期（处理每年重复） */
    fun getEffectiveTargetDate(countdown: Countdown): Long {
        if (!countdown.repeatYearly) return countdown.targetDate
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = countdown.targetDate }
        target.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
        if (target.timeInMillis < now.timeInMillis) {
            target.add(java.util.Calendar.YEAR, 1)
        }
        return target.timeInMillis
    }

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
