package com.xian.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xian.focus.data.Countdown
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Habit
import com.xian.focus.data.Task
import com.xian.focus.databinding.FragmentRecycleBinBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 回收站：任务、倒数日、便贴被删之后的落脚处。
 *
 * 三种东西的存储各不一样（两张 Room 表 + 一份 prefs），但在这里统一成 [TrashItem] 一个形状，
 * 列表、还原、彻底删除都只写一套逻辑。
 *
 * 两条容易踩的规则：
 * - **任务按「重复系列」合并成一条**。删「全部该事件」会把模板和每天的快照一起打上删除戳，
 *   不合并的话删一个重复任务会在回收站里冒出几十条。
 * - **倒数日自带的关联任务不单独显示**。那是实现细节（建倒数日时顺手建的任务），
 *   跟着倒数日一起进、一起出，否则删一个倒数日会看到两条。
 */
@AndroidEntryPoint
class RecycleBinFragment : Fragment() {

    private var _binding: FragmentRecycleBinBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var repository: FocusRepository

    private val taskViewModel: TaskViewModel by activityViewModels()
    private lateinit var adapter: RecycleBinAdapter

    /** 回收站里的倒数日，按 id 存一份 —— 还原时要拿整条去重排提醒。 */
    private var trashedCountdowns: Map<Long, Countdown> = emptyMap()

    /** 回收站里的习惯，同样按 id 存一份 —— 还原后要把每日提醒挂回去。 */
    private var trashedHabits: Map<Int, Habit> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecycleBinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = RecycleBinAdapter(onRestore = ::restore, onPurge = ::confirmPurge)
        binding.recycleRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recycleRecyclerView.adapter = adapter
        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.clearBinButton.setOnClickListener { confirmClearAll() }
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ 读

    private fun load() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildItems(appContext) }
            val view = _binding ?: return@launch
            adapter.submitList(items)
            view.emptyRecycleView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            // 空的回收站没什么可清，把按钮收起来，别让用户点了没反应
            view.clearBinButton.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
    }

    private suspend fun buildItems(appContext: android.content.Context): List<TrashItem> {
        val countdowns = repository.getTrashedCountdowns()
        trashedCountdowns = countdowns.associateBy { it.id.toLong() }
        val hiddenTaskIds = countdowns.mapNotNull { c -> c.linkedTaskId.takeIf { it > 0 } }.toSet()

        val taskItems = repository.getTrashedTasks()
            .filter { it.id !in hiddenTaskIds }
            .groupBy { seriesIdOf(it) }
            .map { (seriesId, rows) ->
                val head = rows.firstOrNull { it.id == seriesId } ?: rows.minByOrNull { it.createdAt }
                TrashItem(
                    kind = TrashItem.Kind.TASK,
                    id = seriesId.toLong(),
                    title = head?.title.orEmpty().ifBlank { getString(R.string.recycle_bin_untitled) },
                    deletedAt = rows.maxOf { it.deletedAt }
                )
            }

        val countdownItems = countdowns.map { countdown ->
            TrashItem(
                kind = TrashItem.Kind.COUNTDOWN,
                id = countdown.id.toLong(),
                title = countdown.title.ifBlank { getString(R.string.recycle_bin_untitled) },
                deletedAt = countdown.deletedAt
            )
        }

        val noteItems = NoteStore.loadTrash(appContext).map { note ->
            TrashItem(
                kind = TrashItem.Kind.NOTE,
                id = note.createdAt,
                title = noteHeadline(note.text),
                deletedAt = note.deletedAt
            )
        }

        val habits = repository.getTrashedHabits()
        trashedHabits = habits.associateBy { it.id }
        val habitItems = habits.map { habit ->
            TrashItem(
                kind = TrashItem.Kind.HABIT,
                id = habit.id.toLong(),
                title = habit.name.ifBlank { getString(R.string.recycle_bin_untitled) },
                deletedAt = habit.deletedAt
            )
        }

        return (taskItems + countdownItems + noteItems + habitItems).sortedByDescending { it.deletedAt }
    }

    /** 系列 id：快照归到它的模板上，普通任务就是自己。 */
    private fun seriesIdOf(task: Task): Int = if (task.templateId != 0) task.templateId else task.id

    /** 便贴的标题 = 正文第一行非空内容，太长就截断。 */
    private fun noteHeadline(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return line.take(24).ifBlank { getString(R.string.recycle_bin_blank_note) }
    }

    // ------------------------------------------------------------------ 还原

    private fun restore(item: TrashItem) {
        val appContext = requireContext().applicationContext
        val countdown = trashedCountdowns[item.id]
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (item.kind) {
                    TrashItem.Kind.TASK -> repository.restoreTaskSeries(item.id.toInt())

                    TrashItem.Kind.COUNTDOWN -> {
                        repository.restoreCountdown(item.id.toInt())
                        countdown?.let { CountdownReminderScheduler.sync(appContext, it) }
                        // 关联任务是建倒数日时顺手建的，跟着倒数日一起回来
                        countdown?.linkedTaskId?.takeIf { it > 0 }?.let {
                            repository.restoreTaskSeries(it)
                        }
                    }

                    TrashItem.Kind.NOTE -> NoteStore.restore(appContext, item.id)

                    TrashItem.Kind.HABIT -> {
                        repository.restoreHabit(item.id.toInt())
                        // 删除时把闹钟撤了，还原必须挂回去 —— 否则「还原成功但提醒没了」，
                        // 而且第 9 张备份表里还躺着 remindMinutes，看起来像功能坏了
                        trashedHabits[item.id.toInt()]?.let { HabitReminderScheduler.sync(appContext, it) }
                    }
                }
            }
            afterChange(R.string.recycle_bin_restored)
        }
    }

    // ------------------------------------------------------------------ 彻底删除

    private fun confirmPurge(item: TrashItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recycle_bin_purge)
            .setMessage(getString(R.string.recycle_bin_purge_confirm, item.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.recycle_bin_purge) { _, _ -> purge(item) }
            .show()
    }

    private fun purge(item: TrashItem) {
        val appContext = requireContext().applicationContext
        val countdown = trashedCountdowns[item.id]
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (item.kind) {
                    TrashItem.Kind.TASK -> repository.purgeTaskSeries(item.id.toInt())

                    TrashItem.Kind.COUNTDOWN -> {
                        repository.purgeCountdown(item.id.toInt())
                        CountdownReminderScheduler.cancel(appContext, item.id.toInt())
                        countdown?.linkedTaskId?.takeIf { it > 0 }?.let {
                            TaskReminderScheduler.cancel(appContext, it)
                            repository.purgeTaskSeries(it)
                        }
                    }

                    TrashItem.Kind.NOTE -> NoteStore.purge(appContext, item.id)

                    TrashItem.Kind.HABIT -> {
                        HabitReminderScheduler.cancel(appContext, item.id.toInt())
                        // 连带清掉它的全部打卡日志，否则库里留下认不出主人的行，导出也会多出来
                        repository.purgeHabit(item.id.toInt())
                    }
                }
            }
            afterChange(R.string.recycle_bin_purged)
        }
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recycle_bin_clear)
            .setMessage(R.string.recycle_bin_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.recycle_bin_clear) { _, _ ->
                val appContext = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.purgeAllTrash()
                        NoteStore.purgeAllTrash(appContext)
                    }
                    afterChange(R.string.recycle_bin_cleared)
                }
            }
            .show()
    }

    /**
     * 回收站动过之后的收尾：重读本页，并让任务页跟着更新。
     *
     * 任务页的数据是 activity 级的 StateFlow、不会自动感知数据库变化，
     * 不敲这一下，还原完返回任务页会看不到刚回来的任务。
     */
    private fun afterChange(messageRes: Int) {
        load()
        taskViewModel.refresh()
        taskViewModel.refreshGroups()
        taskViewModel.rescheduleReminders()
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 删除动作之后的统一提示。
 *
 * 删除已经改成进回收站，但界面上「删了就没了」的心智不会自己变 ——
 * 不提示的话，用户不会知道东西还在、也不知道抽屉里多了个入口。
 */
fun Fragment.toastMovedToRecycleBin() {
    Toast.makeText(requireContext(), R.string.moved_to_recycle_bin, Toast.LENGTH_SHORT).show()
}
