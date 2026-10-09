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
 * 这条任务属于**重复系列**：模板行本身，或它带出的某天快照（`templateId != 0`）。
 *
 * 与 [isRepeatTemplate] 的区别：那个只认模板行，这个把「模板 + 它的快照」当一类。
 * 此类任务**没有「挪到某一天」的概念** —— 模板是规则（每天都成立），快照只是某天的实例。
 *
 * 判据必须与下游同源，否则又会分家：`moveTasksToDay()` 拿它跳过、
 * 「昨天未完成」横幅拿它过滤，两处用同一把尺子才不会出现
 * 「横幅说有 3 个没完成、点一下只移了 1 个」。
 */
fun Task.isRepeatSeries(): Boolean =
    repeatRule != TaskViewModel.REPEAT_NONE || templateId != 0

/**
 * 该任务实例的**子任务所有者 id**。
 *
 * 子任务的行只挂在「任务定义」上：
 * - 重复任务 ⇒ 模板行（`templateId == 0`，`repeatRule != none`）；
 * - 普通任务 ⇒ 它自己。
 *
 * 快照行（`templateId != 0`，即「当天完成」或「移到今天」写出来的那一条）只是
 * **那一天的一个实例**，它的 `id` 与模板不同 —— 拿快照自己的 id 去查子任务会一条都查不到，
 * 表现就是「移到今天 / 当天完成之后，任务的子任务凭空消失」。
 *
 * 库内行数不变式（见 `docs/验收检查标准.md` TSK-17）：模板行 + 有勾选的天各一条，
 * **不随天数复制标题列表** ⇒ 快照**绝不能**另立一份子任务行，一律换算回模板。
 */
fun Task.subtaskOwnerId(): Int = if (templateId != 0) templateId else id

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
