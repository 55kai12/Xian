package com.xian.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xian.focus.data.FocusRepository
import com.xian.focus.databinding.FragmentEventSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EventSettingsFragment : Fragment() {

    private var _binding: FragmentEventSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var repository: FocusRepository

    private val prefs by lazy {
        requireContext().getSharedPreferences("event_settings", 0)
    }

    private val sortValues = arrayOf("days_remaining", "target_date", "created_at")

    private val sortResIds = arrayOf(
        R.string.sort_days_remaining, R.string.sort_target_date, R.string.sort_created_at
    )

    /** 文案要跟着语言走，所以每次现取，不做字段初始化。 */
    private fun sortLabels() = sortResIds.map { getString(it) }.toTypedArray()

    private val sortDescResIds = arrayOf(
        R.string.sort_days_remaining_desc,
        R.string.sort_target_date_desc,
        R.string.sort_created_at_desc
    )

    /** 一行说明比三个相似的名字好分辨 —— 排序方式的差别本来就不好从名字上看出来。 */
    private fun sortDescs() = sortDescResIds.map { getString(it) }

    /** 提前天数的口语化说明：光看「3 天」还得自己换算成「三天前提醒」。 */
    private fun reminderDaysDesc(days: Int): String = when (days) {
        1 -> getString(R.string.event_reminder_days_desc_same_day)
        7 -> getString(R.string.event_reminder_days_desc_week)
        14 -> getString(R.string.event_reminder_days_desc_fortnight)
        30 -> getString(R.string.event_reminder_days_desc_month)
        else -> getString(R.string.event_reminder_days_desc_days, days)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEventSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val reminderEnabled = prefs.getBoolean("reminder_enabled", true)
        val reminderDays = prefs.getInt("reminder_days", 1)
        val autoLink = prefs.getBoolean("auto_link_task", true)
        val sortBy = prefs.getString("sort_by", "days_remaining")

        binding.eventReminderSwitch.isChecked = reminderEnabled
        binding.reminderDaysSection.visibility = if (reminderEnabled) View.VISIBLE else View.GONE
        binding.reminderDaysText.text = getString(R.string.days_count, reminderDays)
        binding.autoLinkTaskSwitch.isChecked = autoLink

        val sortIndex = sortValues.indexOf(sortBy).coerceAtLeast(0)
        binding.eventSortValueText.text = getString(sortResIds[sortIndex])

        binding.showCompletedSwitch.isChecked = prefs.getBoolean("show_completed", true)
        binding.showStrikethroughSwitch.isChecked = prefs.getBoolean("show_strikethrough", true)
        binding.showOrderNumberSwitch.isChecked = prefs.getBoolean("show_order_number", false)
        binding.showNoteSwitch.isChecked = prefs.getBoolean("show_note", false)
        binding.showHolidaySwitch.isChecked = prefs.getBoolean("show_holiday", true)
        binding.subtaskAutoCompleteSwitch.isChecked =
            prefs.getBoolean(TaskViewModel.KEY_SUBTASK_AUTO_COMPLETE, false)
    }

    private fun setupListeners() {
        binding.eventSettingsBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.eventReminderSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("reminder_enabled", isChecked).apply()
            binding.reminderDaysSection.visibility = if (isChecked) View.VISIBLE else View.GONE
            resyncCountdownReminders()
        }

        binding.reminderDaysSection.setOnClickListener {
            val current = prefs.getInt("reminder_days", 1)
            val selected = REMINDER_DAY_VALUES.indexOf(current).coerceAtLeast(0)
            OptionPicker.show(
                requireContext(),
                R.string.event_reminder_days_title,
                REMINDER_DAY_VALUES.map { days ->
                    OptionPicker.Item(
                        label = getString(R.string.days_count, days),
                        desc = reminderDaysDesc(days)
                    )
                },
                selected
            ) { which ->
                val days = REMINDER_DAY_VALUES[which]
                prefs.edit().putInt("reminder_days", days).apply()
                binding.reminderDaysText.text = getString(R.string.days_count, days)
                resyncCountdownReminders()
            }
        }

        binding.autoLinkTaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_link_task", isChecked).apply()
            if (!isChecked) {
                Toast.makeText(requireContext(), R.string.event_auto_link_off, Toast.LENGTH_SHORT).show()
            }
        }

        binding.eventSortCard.setOnClickListener {
            val current = prefs.getString("sort_by", "days_remaining")
            val selected = sortValues.indexOf(current).coerceAtLeast(0)
            val labels = sortLabels()
            OptionPicker.show(
                requireContext(),
                R.string.event_sort_title,
                labels.mapIndexed { index, label ->
                    OptionPicker.Item(label = label, desc = sortDescs()[index])
                },
                selected
            ) { which ->
                prefs.edit().putString("sort_by", sortValues[which]).apply()
                binding.eventSortValueText.text = getString(sortResIds[which])
            }
        }

        binding.showCompletedSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("show_completed", v).apply()
        }
        binding.showStrikethroughSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("show_strikethrough", v).apply()
        }
        binding.showOrderNumberSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("show_order_number", v).apply()
        }
        binding.showNoteSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("show_note", v).apply()
        }
        binding.showHolidaySwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("show_holiday", v).apply()
            HolidayStore.invalidateCache()
        }
        // 开关只落盘，真正的判断在 TaskViewModel.autoCompleteParent（勾子任务那一刻现读）。
        binding.subtaskAutoCompleteSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(TaskViewModel.KEY_SUBTASK_AUTO_COMPLETE, v).apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 开关或提前天数一变，所有事件的提醒都要重排（幂等，直接全量重算最省心）。 */
    private fun resyncCountdownReminders() {
        viewLifecycleOwner.lifecycleScope.launch {
            val countdowns = runCatching { repository.getAllCountdownsOnce() }.getOrNull() ?: return@launch
            countdowns.forEach { CountdownReminderScheduler.sync(requireContext(), it) }
        }
    }

    private companion object {
        /** 可选的提前提醒天数。 */
        val REMINDER_DAY_VALUES = intArrayOf(1, 3, 7, 14, 30)
    }
}
