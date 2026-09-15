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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xian.focus.databinding.FragmentLockBinding
import com.xian.focus.databinding.ItemLockSlotBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LockFragment : Fragment() {
    private var _binding: FragmentLockBinding? = null
    private val binding get() = _binding!!
    private val selectedWhitelist = mutableSetOf<String>()

    /**
     * 正在编辑的时段草稿。只有点「保存」才落盘 —— 编到一半的半成品不该被定时任务拿去用。
     * 与已保存列表不一致时，页面上会给一条「有未保存的修改」。
     */
    private val slotDraft = mutableListOf<LockMachineScheduler.Slot>()

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
        slotDraft.clear()
        slotDraft.addAll(LockMachineScheduler.slots(requireContext()))
        binding.duration30.isChecked = true
        binding.root.post { binding.root.staggerScrollContent() }
        updateWhitelistButton()

        binding.whitelistButton.setOnClickListener { showAppPicker() }
        binding.startStopButton.setOnClickListener { toggleLock() }
        binding.lockModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val immediate = checkedId == R.id.modeImmediate
            binding.immediateSection.visibility = if (immediate) View.VISIBLE else View.GONE
            binding.scheduledSection.visibility = if (immediate) View.GONE else View.VISIBLE
        }
        // 「自定义分钟数」那行的圆点跟上面 15/30/60/90 是同一组单选：谁被选中就用谁的值。
        // RadioGroup 管不到框外的那个圆点，两边互相清一下就行 ——
        // clearCheck() 会把 checkedId 回调成 -1，所以这边要判一下再动，
        // 否则两个监听会互相触发。
        binding.durationRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) binding.durationCustom.isChecked = false
        }
        binding.durationCustom.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.durationRadioGroup.clearCheck()
        }
        // 输入了分钟数就默认选中自定义 —— 填了数字却忘了点圆点、结果按 30 分钟锁上，
        // 那才是真的坑。想改回预设，点一下上面四个圈就行。
        binding.customDurationInput.doAfterTextChanged {
            if (!it.isNullOrEmpty()) binding.durationCustom.isChecked = true
        }
        binding.addSlotButton.setOnClickListener { addSlot() }
        binding.saveScheduleButton.setOnClickListener { saveSchedule() }
        binding.cancelScheduleButton.setOnClickListener { cancelSchedule() }
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
        binding.usagePermissionButton.setOnClickListener {
            startActivity(AppLimitWatcher.usageAccessIntent())
        }
        // 定时锁机/提醒到点要准，靠的是精确闹钟权限；没有它功能照常（自动退化），只是会晚。
        binding.exactAlarmPermissionButton.setOnClickListener {
            ExactAlarms.requestIfNeeded(requireContext())
        }

        renderSlots()
        updateScheduledInfo()

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
        val context = requireContext()
        val active = LockMachineController.isActive(context)
        binding.lockStatusText.setText(if (active) R.string.lock_running else R.string.lock_not_running)
        if (active) {
            binding.lockRemainingText.text = LockMachineController.remainingText(context)
            binding.lockRemainingText.visibility = View.VISIBLE
            // 跨日锁机光看倒计时不知道该几点结束，这里补上绝对时刻（「预计结束：明天 07:00」）
            binding.lockEndText.text =
                getString(R.string.lock_end_at_format, LockMachineController.endText(context))
            binding.lockEndText.visibility = View.VISIBLE
            binding.startStopButton.setText(R.string.stop_lock_machine)
        } else {
            binding.lockRemainingText.visibility = View.GONE
            binding.lockEndText.visibility = View.GONE
            binding.startStopButton.setText(R.string.start_lock_machine)
        }
        val quota = LockExitQuota.remaining(context)
        // 用完时说满一点：只显示「剩余 0 / 2」，用户会以为是自己看错了，
        // 然后反复点「停止锁机」—— 那里也只会再弹一次 Toast。
        binding.exitQuotaHintText.text = if (quota > 0) {
            getString(R.string.exit_quota_format, quota, LockExitQuota.MONTHLY_LIMIT)
        } else {
            getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT)
        }
        val accessibilityOn = isAccessibilityEnabled()
        val usageOn = AppLimitWatcher.hasUsageAccess(context)
        binding.overlayPermissionButton.alpha = if (Settings.canDrawOverlays(context)) 1f else 0.55f
        binding.accessibilityPermissionButton.alpha = if (accessibilityOn) 1f else 0.55f
        binding.usagePermissionButton.alpha = if (usageOn) 1f else 0.55f
        // 精确闹钟跟上面三个不是一档：缺了不影响锁机能不能跑，只影响准不准（晚几十秒），
        // 所以不做「变暗提示」这种常驻噪音 —— 没开才露出来，开了就收起来。
        binding.exactAlarmPermissionButton.visibility =
            if (ExactAlarms.canUseExact(context)) View.GONE else View.VISIBLE
        // 白名单要生效，前提是「当前前台是哪个应用」有源可查。无障碍事件更实时，但服务未必
        // 开着或活着；使用记录是系统自己的账本，不依赖任何服务。两个源都给出来，
        // 一个都不亮时就等于白名单失效 —— 起锁照盖、盖上没人让开，人被卡在里面还不知道为什么。
        binding.foregroundSourceText.setText(
            when {
                accessibilityOn -> R.string.lock_foreground_hint_accessibility
                usageOn -> R.string.lock_foreground_hint_usage
                else -> R.string.lock_foreground_hint_missing
            }
        )
    }

    private fun toggleLock() {
        val context = requireContext()
        if (LockMachineController.isActive(context)) {
            if (!LockExitQuota.canExit(context)) {
                toast(getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT))
                return
            }
            // 应用内退出也走 30 秒冷静期 —— 贤本身永远在白名单里，
            // 这里要是能秒退，覆盖层上的冷静期就形同虚设
            confirmStopWithCooldown()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            toast(getString(R.string.overlay_permission_title))
            return
        }
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.accessibility_permission_title))
            return
        }
        // 时长由「哪个圆点被选中」决定 —— 不再让输入框里残留的数字偷偷压过预设。
        val duration = if (binding.durationCustom.isChecked) {
            binding.customDurationInput.text?.toString()?.toIntOrNull() ?: 0
        } else {
            when (binding.durationRadioGroup.checkedRadioButtonId) {
                R.id.duration15 -> 15
                R.id.duration60 -> 60
                R.id.duration90 -> 90
                else -> 30
            }
        }
        if (duration <= 0) {
            toast(getString(R.string.custom_minutes_hint))
            return
        }
        LockMachineService.start(context, duration.coerceIn(1, 600))
        toast(getString(R.string.lock_machine_running, getString(R.string.duration_minutes, duration)))
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

    // ---------- 定时锁机：时段列表 ----------

    private fun renderSlots() {
        val container = binding.slotContainer
        container.removeAllViews()
        slotDraft.forEachIndexed { index, slot ->
            val row = ItemLockSlotBinding.inflate(layoutInflater, container, false)
            row.slotTimeText.text = slotLabel(slot)
            row.root.setOnClickListener { editSlot(index) }
            row.slotDeleteButton.setOnClickListener { removeSlot(index) }
            container.addView(row.root)
        }
        val empty = slotDraft.isEmpty()
        binding.emptySlotsText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.slotHintText.visibility = if (empty) View.GONE else View.VISIBLE
        binding.addSlotButton.isEnabled = slotDraft.size < LockMachineScheduler.MAX_SLOTS
    }

    /**
     * 时段文案。结束时间早于开始时间就是跨日，必须写明「次日」——
     * 只写「23:00 → 07:00」很容易被当成当天早上七点，看起来就像锁机没生效。
     */
    private fun slotLabel(slot: LockMachineScheduler.Slot): String =
        if (slot.crossesMidnight) {
            getString(
                R.string.slot_cross_day_format,
                TimeLabels.clock(slot.startMinute),
                TimeLabels.clock(slot.endMinute)
            )
        } else {
            getString(
                R.string.slot_same_day_format,
                TimeLabels.clock(slot.startMinute),
                TimeLabels.clock(slot.endMinute)
            )
        }

    private fun addSlot() {
        if (slotDraft.size >= LockMachineScheduler.MAX_SLOTS) {
            toast(getString(R.string.slot_limit_hint, LockMachineScheduler.MAX_SLOTS))
            return
        }
        pickSlot(null) { slot ->
            slotDraft += slot
            sortAndRender()
        }
    }

    private fun editSlot(index: Int) {
        val current = slotDraft.getOrNull(index) ?: return
        pickSlot(current) { slot ->
            slotDraft[index] = slot
            sortAndRender()
        }
    }

    private fun removeSlot(index: Int) {
        if (index !in slotDraft.indices) return
        slotDraft.removeAt(index)
        sortAndRender()
    }

    private fun sortAndRender() {
        slotDraft.sortBy { it.startMinute }
        renderSlots()
        updateScheduledInfo()
    }

    /**
     * 依次问开始、结束两个时间。
     *
     * 结束早于开始不报错 —— 那正是「跨日」这种合法设置（23:00 → 次日 07:00），
     * 只有两个时间完全一样（0 时长）才拒绝。
     */
    private fun pickSlot(
        initial: LockMachineScheduler.Slot?,
        onPicked: (LockMachineScheduler.Slot) -> Unit
    ) {
        val startDefault = initial?.startMinute ?: DEFAULT_SLOT_START_MINUTE
        TimePickerDialog(requireContext(), { _, startHour, startMinuteOfHour ->
            val startMinute = startHour * 60 + startMinuteOfHour
            val endDefault = initial?.endMinute
                ?: (startMinute + DEFAULT_SLOT_MINUTES) % LockMachineScheduler.MINUTES_PER_DAY
            TimePickerDialog(requireContext(), { _, endHour, endMinuteOfHour ->
                val endMinute = endHour * 60 + endMinuteOfHour
                if (endMinute == startMinute) {
                    toast(getString(R.string.slot_invalid_hint))
                } else {
                    onPicked(LockMachineScheduler.Slot(startMinute, endMinute))
                }
            }, endDefault / 60, endDefault % 60, true)
                .apply { setTitle(R.string.scheduled_end_time) }
                .show()
        }, startDefault / 60, startDefault % 60, true)
            .apply { setTitle(R.string.scheduled_start_time) }
            .show()
    }

    /**
     * 保存并立刻自检。
     *
     * 自检是这次修复的重点：`applyAlarms` 只会把已经过去的开始时刻顺延到明天，
     * 所以 23:26 保存一个 23:23 开始的时段会一路等到明天 —— 用户看到的就是「设了不生效」。
     * 保存完顺手问一句「现在是不是已经在时段里了」，是就当场开始锁机。
     */
    private fun saveSchedule() {
        val context = requireContext()
        if (slotDraft.isEmpty()) {
            toast(getString(R.string.slot_empty_hint))
            return
        }
        LockMachineScheduler.schedule(context, slotDraft.toList())
        val started = LockMachineScheduler.resumeIfInScheduledWindow(context)
        updateScheduledInfo()
        updateUi()
        when {
            started != null -> toast(getString(R.string.schedule_locked_now))
            else -> {
                val next = LockMachineScheduler.nextStartAt(context)
                    ?: (System.currentTimeMillis() + 60_000L)
                toast(getString(R.string.schedule_saved_next, TimeLabels.relative(context, next)))
            }
        }
        // 存完顺手把「闹钟和提醒」要过来 —— 定时锁机要准点，而这条权限 Android 14 起默认不给。
        // 放在保存之后：先把用户的事办完，再问他要权限，顺序反了就成了拦路收费。
        ExactAlarms.requestIfNeeded(context)
    }

    private fun cancelSchedule() {
        LockMachineScheduler.cancel(requireContext())
        slotDraft.clear()
        sortAndRender()
        toast(getString(R.string.cancel_schedule))
    }

    private fun updateScheduledInfo() {
        val context = requireContext()
        val saved = LockMachineScheduler.slots(context)
        if (saved.isEmpty()) {
            binding.scheduledInfoText.setText(R.string.no_schedule)
        } else {
            binding.scheduledInfoText.text = getString(
                R.string.scheduled_info_format,
                saved.size,
                saved.joinToString(SLOT_SEPARATOR) { slotLabel(it) }
            )
        }
        val next = LockMachineScheduler.nextStartAt(context)
        binding.scheduledNextText.text =
            if (next == null) "" else getString(R.string.schedule_next_format, TimeLabels.relative(context, next))
        binding.scheduledNextText.visibility = if (next == null) View.GONE else View.VISIBLE
        binding.unsavedHintText.visibility = if (saved == slotDraft) View.GONE else View.VISIBLE
    }

    // ---------- 白名单 ----------

    private fun updateWhitelistButton() {
        binding.whitelistButton.text =
            getString(R.string.whitelist_format, selectedWhitelist.size)
    }

    private fun showAppPicker() {
        WhitelistAppPicker.show(
            requireContext(),
            viewLifecycleOwner.lifecycleScope,
            selectedWhitelist
        ) { updated ->
            selectedWhitelist.clear()
            selectedWhitelist.addAll(updated)
            // 选完立刻落盘。白名单是设置项，不能等到「开始锁机」才存 ——
            // 否则只保存定时时段、或者切个页面重建视图，这次选择就白选了。
            LockMachineController.saveWhitelist(requireContext(), selectedWhitelist)
            updateWhitelistButton()
            refreshOverlayIfShowing()
        }
    }

    /** 锁机正在跑时改了白名单，覆盖层里的文件夹要立刻跟上。 */
    private fun refreshOverlayIfShowing() {
        if (LockMachineOverlayController.isShowing()) {
            LockMachineOverlayController.show(requireContext())
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${requireContext().packageName}/${FocusLockAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** 新增时段时的默认开始时间：晚上 10 点，最贴近「睡前别玩手机」这个用法。 */
        const val DEFAULT_SLOT_START_MINUTE = 22 * 60

        /** 新增时段时的默认时长（分钟）。 */
        const val DEFAULT_SLOT_MINUTES = 60

        const val SLOT_SEPARATOR = "、"
    }
}
