package com.xian.focus

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.xian.focus.databinding.FragmentEventSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class EventSettingsFragment : Fragment() {

    private var _binding: FragmentEventSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences("event_settings", 0)
    }

    private val sortOptions = arrayOf("按剩余天数", "按目标日期", "按创建时间")
    private val sortValues = arrayOf("days_remaining", "target_date", "created_at")

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
        binding.reminderDaysText.text = "$reminderDays 天"
        binding.autoLinkTaskSwitch.isChecked = autoLink

        val sortIndex = sortValues.indexOf(sortBy).coerceAtLeast(0)
        binding.eventSortValueText.text = sortOptions[sortIndex]

        binding.showCompletedSwitch.isChecked = prefs.getBoolean("show_completed", true)
        binding.showStrikethroughSwitch.isChecked = prefs.getBoolean("show_strikethrough", true)
        binding.showOrderNumberSwitch.isChecked = prefs.getBoolean("show_order_number", false)
        binding.showNoteSwitch.isChecked = prefs.getBoolean("show_note", false)
        binding.showHolidaySwitch.isChecked = prefs.getBoolean("show_holiday", true)
    }

    private fun setupListeners() {
        binding.eventSettingsBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.eventReminderSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("reminder_enabled", isChecked).apply()
            binding.reminderDaysSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.reminderDaysSection.setOnClickListener {
            val current = prefs.getInt("reminder_days", 1)
            val options = arrayOf("1 天", "3 天", "7 天", "14 天", "30 天")
            val values = intArrayOf(1, 3, 7, 14, 30)
            val selected = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("提前提醒天数")
                .setSingleChoiceItems(options, selected) { dialog, which ->
                    prefs.edit().putInt("reminder_days", values[which]).apply()
                    binding.reminderDaysText.text = options[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.autoLinkTaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_link_task", isChecked).apply()
            if (!isChecked) {
                Toast.makeText(requireContext(), "已关闭，新建事件将不再生成关联任务", Toast.LENGTH_SHORT).show()
            }
        }

        binding.eventSortCard.setOnClickListener {
            val current = prefs.getString("sort_by", "days_remaining")
            val selected = sortValues.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("事件排序方式")
                .setSingleChoiceItems(sortOptions, selected) { dialog, which ->
                    prefs.edit().putString("sort_by", sortValues[which]).apply()
                    binding.eventSortValueText.text = sortOptions[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
