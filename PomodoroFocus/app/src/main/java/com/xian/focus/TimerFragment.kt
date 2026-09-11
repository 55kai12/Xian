package com.xian.focus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xian.focus.data.TimerDurations
import com.xian.focus.databinding.DialogCustomLockBinding
import com.xian.focus.databinding.DialogDailyGoalBinding
import com.xian.focus.databinding.DialogStatsBinding
import com.xian.focus.databinding.DialogTimerSettingsBinding
import com.xian.focus.databinding.FragmentTimerBinding
import com.xian.focus.service.FocusTimerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimerFragment : Fragment() {
    private var _binding: FragmentTimerBinding? = null
    private val binding get() = _binding!!

    private val timerViewModel: TimerViewModel by activityViewModels()
    private val goalViewModel: DailyGoalViewModel by activityViewModels()
    private val statsViewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        setupControls()
        observeState()
    }

    private fun setupControls() {
        binding.startButton.setOnClickListener {
            if (timerViewModel.timerState.value.status != TimerStatus.PAUSED) {
                if (!requestLockPermissions()) return@setOnClickListener
            }
            if (timerViewModel.timerState.value.status == TimerStatus.PAUSED) {
                timerViewModel.resumeTimer()
            } else {
                timerViewModel.startTimer(timerViewModel.timerState.value.currentTaskId)
            }
            FocusTimerService.start(requireContext())
        }
        binding.pauseButton.setOnClickListener { timerViewModel.pauseTimer() }
        binding.resetButton.setOnClickListener {
            timerViewModel.resetTimer()
            FocusTimerService.stop(requireContext())
        }
        binding.goalButton.setOnClickListener { showGoalDialog() }
        binding.timerSettingsButton.setOnClickListener { showTimerSettingsDialog() }
        binding.statsButton.setOnClickListener { showStatsDialog() }
        binding.lockButton.setOnClickListener {
            ensureLockPermissionsOrToggle()
        }
        binding.appLockButton.setOnClickListener {
            showAppLockDialog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    timerViewModel.timerState.collect { state ->
                        val timerActive = state.status != TimerStatus.IDLE
                        renderTimerState(state)
                        applyLockMode(timerViewModel.lockMode.value, timerActive)
                    }
                }
                launch {
                    goalViewModel.state.collect {
                        binding.goalLabel.text = getString(
                            R.string.goal_progress_format,
                            it.completed,
                            it.goal
                        )
                    }
                }
                launch {
                    timerViewModel.lockMode.collect { enabled ->
                        val timerActive = timerViewModel.timerState.value.status != TimerStatus.IDLE
                        binding.lockButton.setText(
                            if (enabled) R.string.lock_disable else R.string.lock_enable
                        )
                        applyLockMode(enabled, timerActive)
                    }
                }
                launch {
                    timerViewModel.focusCompletedEvents.collect {
                        goalViewModel.refresh()
                        statsViewModel.refresh()
                    }
                }
            }
        }
    }

    private fun renderTimerState(state: TimerState) {
        binding.timerText.text = formatSeconds(state.remainingSeconds)
        binding.statusText.setText(
            when (state.status) {
                TimerStatus.IDLE -> R.string.status_idle
                TimerStatus.FOCUSING -> R.string.status_focusing
                TimerStatus.SHORT_BREAK -> R.string.status_short_break
                TimerStatus.LONG_BREAK -> R.string.status_long_break
                TimerStatus.PAUSED -> R.string.status_paused
            }
        )
        binding.startButton.setText(
            if (state.status == TimerStatus.PAUSED) R.string.resume else R.string.restart_focus
        )
        if (state.status == TimerStatus.IDLE) {
            binding.startButton.setText(R.string.start_focus)
        }
        binding.pauseButton.isEnabled = when (state.status) {
            TimerStatus.FOCUSING,
            TimerStatus.SHORT_BREAK,
            TimerStatus.LONG_BREAK -> true
            TimerStatus.IDLE,
            TimerStatus.PAUSED -> false
        }
    }

    private fun applyLockMode(enabled: Boolean, timerActive: Boolean) {
        val window = requireActivity().window
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNavigation)?.visibility =
            if (enabled && timerActive) View.GONE else View.VISIBLE
        if (enabled && timerActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        } else {
            LockOverlayController.hide(requireContext())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun ensureLockPermissionsOrToggle() {
        if (!requestLockPermissions()) return
        timerViewModel.setLockMode(!timerViewModel.lockMode.value)
    }

    private fun requestLockPermissions(): Boolean {
        if (!Settings.canDrawOverlays(requireContext())) {
            showPermissionDialog(
                R.string.overlay_permission_title,
                R.string.overlay_permission_message
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
            return false
        }
        if (!isAccessibilityEnabled()) {
            showPermissionDialog(
                R.string.accessibility_permission_title,
                R.string.accessibility_permission_message
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return false
        }
        return true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${requireContext().packageName}/${FocusLockAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showPermissionDialog(
        title: Int,
        message: Int,
        onOpenSettings: () -> Unit
    ) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.open_settings) { _, _ -> onOpenSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGoalDialog() {
        val dialogBinding = DialogDailyGoalBinding.inflate(layoutInflater)
        dialogBinding.goalInput.setText(goalViewModel.state.value.goal.toString())
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.daily_goal_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                goalViewModel.setDailyGoal(dialogBinding.goalInput.text.toString().toIntOrNull() ?: 8)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTimerSettingsDialog() {
        val dialogBinding = DialogTimerSettingsBinding.inflate(layoutInflater)
        val current = timerViewModel.currentDurations()
        val focusHours = current.focusMinutes / 60
        val focusMins = current.focusMinutes % 60
        dialogBinding.focusHoursInput.setText(if (focusHours > 0) focusHours.toString() else "")
        dialogBinding.focusMinutesInput.setText(if (focusMins > 0) focusMins.toString() else "")
        dialogBinding.shortBreakMinutesInput.setText(current.shortBreakMinutes.toString())
        dialogBinding.longBreakMinutesInput.setText(current.longBreakMinutes.toString())
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.timer_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val hours = dialogBinding.focusHoursInput.text?.toString()?.toIntOrNull() ?: 0
                val mins = dialogBinding.focusMinutesInput.text?.toString()?.toIntOrNull() ?: 0
                val focus = (hours * 60 + mins).coerceAtLeast(1)
                val shortBreak = dialogBinding.shortBreakMinutesInput.text?.toString()?.toIntOrNull()
                    ?: current.shortBreakMinutes
                val longBreak = dialogBinding.longBreakMinutesInput.text?.toString()?.toIntOrNull()
                    ?: current.longBreakMinutes
                timerViewModel.updateDurations(
                    TimerDurations(
                        focusMinutes = focus,
                        shortBreakMinutes = shortBreak,
                        longBreakMinutes = longBreak
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStatsDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = statsViewModel.loadStats()
            val weekly = statsViewModel.loadWeeklyCounts()
            val dialogBinding = DialogStatsBinding.inflate(layoutInflater)
            XianCharts.setBarData(dialogBinding.weeklyChart, weekly)
            dialogBinding.statToday.text = stats.todayCount.toString()
            dialogBinding.statWeek.text = stats.lastSevenDaysCount.toString()
            dialogBinding.statTotal.text = stats.totalCount.toString()
            dialogBinding.statMinutes.text = stats.totalMinutes.toString()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.dialog_confirm, null)
                .show()
        }
    }

    private fun showCustomLockDialog() {
        if (!requestLockPermissions()) return
        val dialogBinding = DialogCustomLockBinding.inflate(layoutInflater)
        dialogBinding.duration30.isChecked = true
        val selectedWhitelist = LockMachineController.whitelist(requireContext()).toMutableSet()
        fun updateWhitelistButton() {
            dialogBinding.whitelistButton.text =
                getString(R.string.whitelist_format, selectedWhitelist.size)
        }
        updateWhitelistButton()
        dialogBinding.whitelistButton.setOnClickListener {
            showAppPicker(selectedWhitelist) { updated ->
                selectedWhitelist.clear()
                selectedWhitelist.addAll(updated)
                updateWhitelistButton()
            }
        }
        val isActive = LockMachineController.isActive(requireContext())
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_lock_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(if (isActive) R.string.stop_lock_machine else R.string.start_lock_machine) { _, _ ->
                if (isActive) {
                    LockMachineService.stop(requireContext())
                    return@setPositiveButton
                }
                val customDuration = dialogBinding.customDurationInput.text?.toString()?.toIntOrNull()
                val duration = customDuration ?: when (dialogBinding.durationRadioGroup.checkedRadioButtonId) {
                    R.id.duration15 -> 15
                    R.id.duration60 -> 60
                    R.id.duration90 -> 90
                    else -> 30
                }
                if (duration <= 0) {
                    Toast.makeText(requireContext(), R.string.custom_minutes_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LockMachineService.start(requireContext(), duration.coerceIn(1, 600), selectedWhitelist)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.lock_machine_running, getString(R.string.duration_minutes, duration)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppPicker(selected: Set<String>, onResult: (Set<String>) -> Unit) {
        WhitelistAppPicker.show(requireContext(), viewLifecycleOwner.lifecycleScope, selected, onResult)
    }

    private fun showAppLockDialog() {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            hint = getString(R.string.set_pin_hint)
        }
        val isEnabled = AppLockStore.isEnabled(requireContext())
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_lock)
            .setView(input)
            .setPositiveButton(if (isEnabled) R.string.stop_lock_machine else R.string.save) { _, _ ->
                if (isEnabled) {
                    AppLockStore.clearPin(requireContext())
                } else {
                    val pin = input.text?.toString().orEmpty()
                    if (pin.length == 4 && pin.all { it.isDigit() }) {
                        AppLockStore.setPin(requireContext(), pin)
                    } else {
                        Toast.makeText(requireContext(), R.string.set_pin_hint, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNavigation)?.visibility =
            View.VISIBLE
        _binding = null
        super.onDestroyView()
    }

    private fun formatSeconds(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
}
