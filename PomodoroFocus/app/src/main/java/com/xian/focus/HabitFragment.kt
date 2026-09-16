package com.xian.focus

import android.app.TimePickerDialog
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xian.focus.data.Habit
import com.xian.focus.databinding.DialogHabitEditBinding
import com.xian.focus.databinding.FragmentHabitBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 小习惯打卡。结构与其他二级页一致（返回 · 标题 · 右上角新建）。
 *
 * 三个交互约定：
 * - **点圆钮 = +1**；长按圆钮 = 撤销今天（弹确认）——长按不好发现，所以每次「刚达标」弹一次提示；
 * - **点整行 = 编辑**。本期没有详情页，编辑对话框就是全部信息；
 * - 打卡与撤销都**只写所属的那一天**，页面重算整表（习惯只有十几个，重算比增量更新便宜）。
 */
@AndroidEntryPoint
class HabitFragment : Fragment() {

    private var _binding: FragmentHabitBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HabitViewModel by viewModels()

    private val adapter = HabitAdapter(
        onPunch = { row -> punch(row) },
        onUndo = { row -> confirmUndo(row) },
        onEdit = { row -> showEditDialog(row.habit) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHabitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.habitRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.habitRecyclerView.adapter = adapter

        binding.todayDateText.text = getString(
            R.string.habit_today_label,
            SimpleDateFormat("M月d日 EEE", Locale.getDefault()).format(Date())
        )

        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.addHabitButton.setOnClickListener { showEditDialog(null) }

        observeData()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.habitRecyclerView.adapter = null
        _binding = null
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rows.collect { rows ->
                        val view = _binding ?: return@collect
                        adapter.submitList(rows)
                        view.emptyHabitText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch { viewModel.todayDone.collect { renderProgress() } }
                launch { viewModel.todayTotal.collect { renderProgress() } }
            }
        }
    }

    private fun renderProgress() {
        val view = _binding ?: return
        val done = viewModel.todayDone.value
        val total = viewModel.todayTotal.value
        view.todayProgressText.text = getString(R.string.habit_today_progress, done, total)
        view.todayProgressBar.progress = if (total == 0) 0 else done * 100 / total
    }

    // ------------------------------------------------------------------ 打卡

    private fun punch(row: HabitRow) {
        if (row.status.done) {
            toast(getString(R.string.habit_done_toast, row.habit.name))
            return
        }
        // 这一下正好打满时才提示长按撤销 —— 每次都弹会很吵
        val justFinished = row.status.todayCount + 1 >= row.status.target
        viewModel.punch(row.habit)
        if (justFinished) toast(getString(R.string.habit_punch_hint))
    }

    private fun confirmUndo(row: HabitRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.habit_undo_title)
            .setMessage(getString(R.string.habit_undo_message, row.habit.name))
            .setPositiveButton(R.string.habit_undo_title) { _, _ ->
                viewModel.undo(row.habit)
                toast(getString(R.string.habit_undo_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 新建 / 编辑

    private fun showEditDialog(existing: Habit?) {
        val dialogBinding = DialogHabitEditBinding.inflate(layoutInflater)

        var color = existing?.color ?: COLOR_PALETTE.first()
        var freq = existing?.freqType ?: Habit.FREQ_DAILY
        var weekMask = existing?.weekDaysMask ?: 0
        var weeklyTarget = existing?.weeklyTarget?.takeIf { it > 0 } ?: DEFAULT_WEEKLY_TARGET
        var targetPerDay = existing?.targetPerDay?.coerceAtLeast(1) ?: 1
        var remindMinutes = existing?.remindMinutes ?: Habit.NO_REMIND

        dialogBinding.habitNameInput.setText(existing?.name.orEmpty())

        // 圆点先摆好再挂监听：监听里要用 render()，而 render() 是后面才定义的局部函数。
        // 颜色记在 tag 上，重绘时拿回来。
        val dots = COLOR_PALETTE.map { value ->
            View(requireContext()).also { dot ->
                val size = dp(30)
                dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
                dot.tag = value
                dialogBinding.habitColorRow.addView(dot)
            }
        }

        val dayChips = listOf(
            dialogBinding.dayChip1, dialogBinding.dayChip2, dialogBinding.dayChip3,
            dialogBinding.dayChip4, dialogBinding.dayChip5, dialogBinding.dayChip6,
            dialogBinding.dayChip7
        )
        // 界面上从「一」排到「日」，掩码里 bit0 是周日 —— 这里的换算必须与 Habit.maskOf 对齐
        val chipMasks = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        ).map { 1 shl (it - 1) }

        fun render() {
            COLOR_PALETTE.forEachIndexed { index, value -> paintDot(dots[index], value == color) }

            dialogBinding.freqDailyButton.setBackgroundResource(freqBg(freq == Habit.FREQ_DAILY))
            dialogBinding.freqWeeklyButton.setBackgroundResource(freqBg(freq == Habit.FREQ_WEEKLY))
            dialogBinding.freqCustomButton.setBackgroundResource(freqBg(freq == Habit.FREQ_CUSTOM))

            dialogBinding.habitWeekDayRow.visibility =
                if (freq == Habit.FREQ_CUSTOM) View.VISIBLE else View.GONE
            dialogBinding.habitWeeklyRow.visibility =
                if (freq == Habit.FREQ_WEEKLY) View.VISIBLE else View.GONE
            dayChips.forEachIndexed { index, chip ->
                chip.setBackgroundResource(freqBg(weekMask and chipMasks[index] != 0))
            }

            dialogBinding.habitWeeklyValue.text = weeklyTarget.toString()
            dialogBinding.habitTargetValue.text = targetPerDay.toString()
            dialogBinding.habitRemindValue.text = if (remindMinutes < 0) {
                getString(R.string.habit_remind_off)
            } else {
                String.format(Locale.US, "%02d:%02d", remindMinutes / 60, remindMinutes % 60)
            }
        }

        dots.forEachIndexed { index, dot ->
            dot.setOnClickListener {
                color = COLOR_PALETTE[index]
                render()
            }
        }
        dayChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                weekMask = weekMask xor chipMasks[index]
                render()
            }
        }

        dialogBinding.freqDailyButton.setOnClickListener { freq = Habit.FREQ_DAILY; render() }
        dialogBinding.freqWeeklyButton.setOnClickListener { freq = Habit.FREQ_WEEKLY; render() }
        dialogBinding.freqCustomButton.setOnClickListener { freq = Habit.FREQ_CUSTOM; render() }

        dialogBinding.weeklyMinusButton.setOnClickListener {
            weeklyTarget = (weeklyTarget - 1).coerceAtLeast(1); render()
        }
        dialogBinding.weeklyPlusButton.setOnClickListener {
            weeklyTarget = (weeklyTarget + 1).coerceAtMost(7); render()
        }
        dialogBinding.targetMinusButton.setOnClickListener {
            targetPerDay = (targetPerDay - 1).coerceAtLeast(1); render()
        }
        dialogBinding.targetPlusButton.setOnClickListener {
            targetPerDay = (targetPerDay + 1).coerceAtMost(99); render()
        }
        dialogBinding.habitRemindValue.setOnClickListener {
            val start = if (remindMinutes < 0) DEFAULT_REMIND_MINUTES else remindMinutes
            val picker = TimePickerDialog(
                requireContext(),
                { _, hour, minute -> remindMinutes = hour * 60 + minute; render() },
                start / 60,
                start % 60,
                true
            )
            // 「不提醒」挂在中间按钮上：不留这个口，用户设过提醒之后就再也没法取消
            picker.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.habit_remind_off)) { _, _ ->
                remindMinutes = Habit.NO_REMIND
                render()
            }
            picker.show()
        }

        render()

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.habit_add else R.string.habit_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.habitNameInput.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.habit_name_required))
                    return@setPositiveButton
                }
                if (freq == Habit.FREQ_CUSTOM && weekMask == 0) {
                    toast(getString(R.string.habit_custom_empty))
                    return@setPositiveButton
                }
                val base = existing ?: Habit(
                    name = name,
                    startDate = HabitStreak.startOfDay(System.currentTimeMillis())
                )
                viewModel.save(
                    base.copy(
                        name = name,
                        color = color,
                        freqType = freq,
                        weekDaysMask = if (freq == Habit.FREQ_CUSTOM) weekMask else 0,
                        weeklyTarget = if (freq == Habit.FREQ_WEEKLY) weeklyTarget else 0,
                        targetPerDay = targetPerDay,
                        remindMinutes = remindMinutes
                    )
                )
                // 先办完用户的事（保存），再要权限 —— 反了就成了拦路收费。
                // 与另外 5 处调用点同一口径：拿不到精确闹钟权限只是晚几十秒，不会静默失效。
                if (remindMinutes >= 0) ExactAlarms.requestIfNeeded(requireContext())
            }
            .setNegativeButton(R.string.cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.action_delete) { _, _ -> confirmDelete(existing) }
        }
        builder.show()
    }

    private fun confirmDelete(habit: Habit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.habit_delete_message, habit.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.delete(habit)
                toast(getString(R.string.habit_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 小工具

    private fun paintDot(dot: View, selected: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorOf(dot))
            if (selected) setStroke(dp(2), attrColor(R.attr.colorBrandContent))
        }
    }

    /** 圆点自身的颜色由创建时记在 tag 上（重绘时要拿回来）。 */
    private fun colorOf(dot: View): Int = dot.tag as? Int ?: 0

    private fun freqBg(selected: Boolean): Int =
        if (selected) R.drawable.bg_option_row_selected else R.drawable.bg_option_row

    private fun attrColor(attrRes: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attrRes, value, true)
        return value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 与倒数日共用同一套六色，免得两处各有一份调色板。 */
        val COLOR_PALETTE = listOf(
            0xFF3B5B4E.toInt(),
            0xFFC9A961.toInt(),
            0xFF8B4513.toInt(),
            0xFF4A6FA5.toInt(),
            0xFFA0522D.toInt(),
            0xFF2F4F4F.toInt()
        )
        const val DEFAULT_WEEKLY_TARGET = 3
        const val DEFAULT_REMIND_MINUTES = 20 * 60
    }
}
