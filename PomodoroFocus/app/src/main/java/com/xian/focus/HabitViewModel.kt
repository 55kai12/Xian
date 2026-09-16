package com.xian.focus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Habit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 习惯打卡页的状态。
 *
 * 每次动作之后整表重算（`refresh()`）：习惯数量是「十几个」这个量级，
 * 重算比维护增量更新便宜得多，也不会出现「打了卡但进度条没动」这类不一致。
 */
@HiltViewModel
class HabitViewModel @Inject constructor(
    private val repository: FocusRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _rows = MutableStateFlow<List<HabitRow>>(emptyList())
    val rows: StateFlow<List<HabitRow>> = _rows.asStateFlow()

    /** 今天该打卡的习惯里，已经达标了几个。 */
    private val _todayDone = MutableStateFlow(0)
    val todayDone: StateFlow<Int> = _todayDone.asStateFlow()

    private val _todayTotal = MutableStateFlow(0)
    val todayTotal: StateFlow<Int> = _todayTotal.asStateFlow()

    private val _hasHabits = MutableStateFlow(false)
    val hasHabits: StateFlow<Boolean> = _hasHabits.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val today = HabitStreak.startOfDay(now)
        val loaded = withContext(Dispatchers.IO) {
            repository.getHabits().map { habit ->
                HabitRow(
                    habit = habit,
                    status = HabitStreak.status(habit, repository.getHabitLogs(habit.id), now),
                    scheduledToday = habit.coversDay(today)
                )
            }
        }
        _rows.value = loaded
        _hasHabits.value = loaded.isNotEmpty()
        // 进度只算「今天该打卡」的 —— 「指定星期」里今天不出现的习惯不该拖着分母
        val scheduled = loaded.filter { it.scheduledToday }
        _todayTotal.value = scheduled.size
        _todayDone.value = scheduled.count { it.status.done }
        // 顺手按库里的习惯重建一遍提醒闹钟。只靠「保存时才排」的话，
        // 导入进来的、以及升级前就存在的习惯永远不会响（TaskViewModel 早就踩过这个坑）。
        // 放在 refresh 里是因为它是这一页唯一的入口：打开页面、打卡、编辑都会经过。
        // sync 是幂等的（requestCode 就是习惯 id，重复排等于原地覆盖），也不读数据库。
        loaded.forEach { HabitReminderScheduler.sync(appContext, it.habit) }
    }

    /** 打卡 +1。 */
    fun punch(habit: Habit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            repository.punchHabit(habit.id, HabitStreak.startOfDay(System.currentTimeMillis()))
        }
        refresh()
    }

    /** 撤销今天的打卡。 */
    fun undo(habit: Habit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            repository.undoHabit(habit.id, HabitStreak.startOfDay(System.currentTimeMillis()))
        }
        refresh()
    }

    /**
     * 新增或更新。`id == 0` 走新增。
     *
     * 保存后必须重排提醒：改了提醒时刻、或者刚把提醒打开，闹钟都得跟着变
     * —— 存了不排等于开关是假的（项目里这类「开关只写 prefs 没人读」的坑已经踩过）。
     */
    fun save(habit: Habit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            if (habit.id == 0) repository.addHabit(habit) else repository.updateHabit(habit)
        }
        HabitReminderScheduler.sync(appContext, habit)
        refresh()
    }

    /** 删除 = 移入回收站；顺手把提醒撤掉，不然习惯进了回收站还会天天响。 */
    fun delete(habit: Habit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repository.deleteHabit(habit.id) }
        HabitReminderScheduler.cancel(appContext, habit.id)
        refresh()
    }
}
