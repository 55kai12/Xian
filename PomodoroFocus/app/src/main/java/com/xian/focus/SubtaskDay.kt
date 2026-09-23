package com.xian.focus

import com.xian.focus.data.Subtask
import com.xian.focus.data.Task
import java.util.Calendar

/**
 * 重复任务的**模板行**：重复规则挂在它身上，它本身不代表任何一天。
 * 快照（某一天的真实任务行）的 `templateId` 指向它。
 */
fun Task.isRepeatTemplate(): Boolean =
    repeatRule != TaskViewModel.REPEAT_NONE && templateId == 0

/**
 * 「某个任务在某一天的子任务长什么样」的**唯一出处**。
 *
 * 两种形态：
 * - 非重复任务（或没选日期）：子任务不分天，`dueDate == null` 的行就是全部，行为与
 *   v2.0.93 及以前完全一致。
 * - 重复任务的模板：`dueDate == null` 的行只当**标题模板**（定顺序），那天的勾选状态取自
 *   `dueDate == 当天` 的行；对不上就现造一条 `id = 0` 的未完成行 —— 这样「后加的子任务」
 *   在过去的日期也看得见，不会因为那天没记录就凭空少一行。
 *
 * 按**标题**配对（同名标题按出现顺序逐个消费），与编辑保存时的配对口径一致：
 * `Subtask` 没有「模板行 → 当天行」的稳定外键（用户随时能改标题、调顺序），
 * 标题是唯一还能对上的线索。
 *
 * `day` 一律归一化到当天零点。周条点击、周切换、日历选日三处传进来的毫秒未必都是
 * 零点口径，而落库统一按零点存 —— 不归一就会「存进去查不出来」，点一下没反应。
 */
fun subtasksForDay(
    all: List<Subtask>,
    taskId: Int,
    day: Long?,
    isRepeating: Boolean
): List<Subtask> {
    if (!isRepeating || day == null) return all.filter { it.dueDate == null }
    val target = startOfDayMillis(day)
    val byTitle = HashMap<String, ArrayDeque<Subtask>>()
    all.filter { it.dueDate == target }
        .forEach { byTitle.getOrPut(it.title) { ArrayDeque() }.addLast(it) }
    return all.filter { it.dueDate == null }.map { template ->
        val queue = byTitle[template.title]
        val match = if (queue.isNullOrEmpty()) null else queue.removeFirst()
        match ?: Subtask(
            id = 0,
            taskId = taskId,
            title = template.title,
            isCompleted = false,
            dueDate = target
        )
    }
}

/** 当天零点毫秒。子任务落库与比对统一走这里，别各处自己算。 */
fun startOfDayMillis(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
