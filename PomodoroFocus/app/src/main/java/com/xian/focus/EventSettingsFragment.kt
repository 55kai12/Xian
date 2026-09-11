package com.xian.focus

import android.app.AlertDialog
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
            val values = intArrayOf(1, 3, 7, 14, 30)
            val options = values.map { getString(R.string.days_count, it) }.toTypedArray()
            val selected = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.event_reminder_days_title)
                .setSingleChoiceItems(options, selected) { dialog, which ->
                    prefs.edit().putInt("reminder_days", values[which]).apply()
                    binding.reminderDaysText.text = getString(R.string.days_count, values[which])
                    dialog.dismiss()
                    resyncCountdownReminders()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.event_sort_title)
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    prefs.edit().putString("sort_by", sortValues[which]).apply()
                    binding.eventSortValueText.text = getString(sortResIds[which])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
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

    /** 开关或提前天数一变，所有事件的提醒都要重排（幂等，直接全量重算最省心）。 */
    private fun resyncCountdownReminders() {
        viewLifecycleOwner.lifecycleScope.launch {
            val countdowns = runCatching { repository.getAllCountdownsOnce() }.getOrNull() ?: return@launch
            countdowns.forEach { CountdownReminderScheduler.sync(requireContext(), it) }
        }
    }
}
