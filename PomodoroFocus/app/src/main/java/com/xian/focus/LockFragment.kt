package com.xian.focus

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xian.focus.databinding.FragmentLockBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class LockFragment : Fragment() {
    private var _binding: FragmentLockBinding? = null
    private val binding get() = _binding!!
    private val selectedWhitelist = mutableSetOf<String>()
    private var scheduledStartMin = 9 * 60
    private var scheduledEndMin = 12 * 60

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        selectedWhitelist.clear()
        selectedWhitelist.addAll(LockMachineController.whitelist(requireContext()))
        binding.duration30.isChecked = true
        binding.whitelistButton.text = getString(R.string.whitelist_format, selectedWhitelist.size)

        binding.whitelistButton.setOnClickListener { showAppPicker() }
        binding.startStopButton.setOnClickListener { toggleLock() }
        binding.lockModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val immediate = checkedId == R.id.modeImmediate
            binding.immediateSection.visibility = if (immediate) View.VISIBLE else View.GONE
            binding.scheduledSection.visibility = if (immediate) View.GONE else View.VISIBLE
        }
        if (LockMachineScheduler.isEnabled(requireContext())) {
            scheduledStartMin = LockMachineScheduler.startMinute(requireContext())
            scheduledEndMin = LockMachineScheduler.endMinute(requireContext())
        }
        updateScheduledButtons()
        updateScheduledInfo()
        binding.scheduledStartButton.setOnClickListener {
            TimePickerDialog(requireContext(), { _, hour, minute ->
                scheduledStartMin = hour * 60 + minute
                updateScheduledButtons()
            }, scheduledStartMin / 60, scheduledStartMin % 60, true).show()
        }
        binding.scheduledEndButton.setOnClickListener {
            TimePickerDialog(requireContext(), { _, hour, minute ->
                scheduledEndMin = hour * 60 + minute
                updateScheduledButtons()
            }, scheduledEndMin / 60, scheduledEndMin % 60, true).show()
        }
        binding.saveScheduleButton.setOnClickListener {
            if (scheduledStartMin == scheduledEndMin) {
                Toast.makeText(requireContext(), R.string.custom_minutes_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            LockMachineScheduler.schedule(requireContext(), scheduledStartMin, scheduledEndMin)
            updateScheduledInfo()
            Toast.makeText(requireContext(), R.string.save_schedule, Toast.LENGTH_SHORT).show()
        }
        binding.cancelScheduleButton.setOnClickListener {
            LockMachineScheduler.cancel(requireContext())
            updateScheduledInfo()
        }
        binding.overlayPermissionButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
        }
        binding.accessibilityPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateUi()
                    delay(1000L)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val active = LockMachineController.isActive(requireContext())
        binding.lockStatusText.setText(if (active) R.string.lock_running else R.string.lock_not_running)
        if (active) {
            binding.lockRemainingText.text = LockMachineController.remainingText(requireContext())
            binding.lockRemainingText.visibility = View.VISIBLE
            binding.startStopButton.setText(R.string.stop_lock_machine)
        } else {
            binding.lockRemainingText.visibility = View.GONE
            binding.startStopButton.setText(R.string.start_lock_machine)
        }
        binding.exitQuotaHintText.text = getString(
            R.string.exit_quota_format,
            LockExitQuota.remaining(requireContext()),
            LockExitQuota.MONTHLY_LIMIT
        )
        binding.overlayPermissionButton.alpha = if (Settings.canDrawOverlays(requireContext())) 1f else 0.55f
        binding.accessibilityPermissionButton.alpha = if (isAccessibilityEnabled()) 1f else 0.55f
    }

    private fun toggleLock() {
        if (LockMachineController.isActive(requireContext())) {
            if (!LockExitQuota.canExit(requireContext())) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            // 应用内退出也走 30 秒冷静期 —— 贤本身永远在白名单里，
            // 这里要是能秒退，覆盖层上的冷静期就形同虚设
            confirmStopWithCooldown()
            return
        }
        if (!Settings.canDrawOverlays(requireContext())) {
            Toast.makeText(requireContext(), R.string.overlay_permission_title, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(requireContext(), R.string.accessibility_permission_title, Toast.LENGTH_SHORT).show()
            return
        }
        val custom = binding.customDurationInput.text?.toString()?.toIntOrNull()
        val duration = custom ?: when (binding.durationRadioGroup.checkedRadioButtonId) {
            R.id.duration15 -> 15
            R.id.duration60 -> 60
            R.id.duration90 -> 90
            else -> 30
        }
        if (duration <= 0) {
            Toast.makeText(requireContext(), R.string.custom_minutes_hint, Toast.LENGTH_SHORT).show()
            return
        }
        LockMachineService.start(requireContext(), duration.coerceIn(1, 600), selectedWhitelist)
        Toast.makeText(requireContext(), getString(R.string.lock_machine_running, getString(R.string.duration_minutes, duration)), Toast.LENGTH_SHORT).show()
        updateUi()
    }

    private fun confirmStopWithCooldown() {
        val contentView = layoutInflater.inflate(R.layout.dialog_exit_cooldown, null)
        val cooldownText = contentView.findViewById<TextView>(R.id.cooldownText)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.exit_confirm_title)
            .setMessage(getString(R.string.exit_confirm_body))
            .setPositiveButton(R.string.exit_confirm_exit) { _, _ ->
                LockMachineService.stop(requireContext())
            }
            .setNegativeButton(R.string.exit_confirm_cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val job = viewLifecycleOwner.lifecycleScope.launch {
            var remain = LockExitQuota.COOLDOWN_SECONDS
            while (remain > 0) {
                cooldownText.text = getString(R.string.exit_confirm_cooldown, remain)
                delay(1_000L)
                remain--
            }
            cooldownText.setText(R.string.exit_confirm_ready)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun updateScheduledButtons() {
        binding.scheduledStartButton.text = getString(R.string.scheduled_start_time) + "：" + formatMinute(scheduledStartMin)
        binding.scheduledEndButton.text = getString(R.string.scheduled_end_time) + "：" + formatMinute(scheduledEndMin)
    }

    private fun updateScheduledInfo() {
        if (LockMachineScheduler.isEnabled(requireContext())) {
            val s = formatMinute(LockMachineScheduler.startMinute(requireContext()))
            val e = formatMinute(LockMachineScheduler.endMinute(requireContext()))
            binding.scheduledInfoText.text = getString(R.string.scheduled_info_format, s, e)
        } else {
            binding.scheduledInfoText.setText(R.string.no_schedule)
        }
    }

    private fun formatMinute(minute: Int): String =
        String.format("%02d:%02d", minute / 60, minute % 60)

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${requireContext().packageName}/${FocusLockAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showAppPicker() {
        WhitelistAppPicker.show(requireContext(), viewLifecycleOwner.lifecycleScope, selectedWhitelist) { updated ->
            selectedWhitelist.clear()
            selectedWhitelist.addAll(updated)
            binding.whitelistButton.text = getString(R.string.whitelist_format, selectedWhitelist.size)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
