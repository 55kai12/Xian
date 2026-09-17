package com.xian.focus

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xian.focus.databinding.FragmentAppLimitBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用限额：给应用设每天能玩多久，用完当天锁死，次日自动恢复。
 * 入口在任务清单左上角抽屉里。
 */
class AppLimitFragment : Fragment() {

    private var _binding: FragmentAppLimitBinding? = null
    private val binding get() = _binding!!

    private data class Row(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        /** 今天实际可用的额度 = 设定限额 + 今日加时。 */
        val effectiveMinutes: Int,
        val usedMinutes: Long,
        val locked: Boolean
    )

    private var rows: List<Row> = emptyList()
    private lateinit var adapter: RowAdapter

    /** 上次界面上的「最近计时时刻」。它变没变，就是「有没有在记账」的信号。 */
    private var lastShownCountedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppLimitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = RowAdapter(LayoutInflater.from(requireContext()))
        binding.limitAppList.adapter = adapter
        binding.root.post { binding.root.staggerScrollContent() }
        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.addLimitAppButton.setOnClickListener { pickApps() }
        binding.limitAppList.setOnItemClickListener { _, _, position, _ ->
            showLimitDialog(rows[position])
        }
        binding.accessibilityHintCard.setOnClickListener { openAccessibilitySettings() }
        binding.usageAccessCard.setOnClickListener { openUsageAccessSettings() }
        // 状态行缺什么就带用户去补什么，别让他自己猜是哪一项
        binding.overviewStatusText.setOnClickListener {
            if (AppLimitWatcher.hasUsageAccess(requireContext())) {
                openAccessibilitySettings()
            } else {
                openUsageAccessSettings()
            }
        }
        // 停在页面上时数字要自己涨 —— 否则用户根本分不清「没在计时」和「界面没刷新」。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(LIVE_REFRESH_MILLIS)
                    refreshIfChanged()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openUsageAccessSettings() {
        runCatching { startActivity(AppLimitWatcher.usageAccessIntent()) }
    }

    private fun refresh() {
        val context = requireContext()
        // 限额列表一变，「该不该有守护服务」也可能跟着变。挂在这儿，省得每个改动点各写一遍。
        GuardService.sync(context)
        val pm = context.packageManager
        rows = AppLimitStore.limitedPackages(context).map { packageName ->
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            Row(
                packageName = packageName,
                label = info?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
                icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                effectiveMinutes = AppLimitStore.effectiveLimitMinutes(context, packageName),
                usedMinutes = AppLimitStore.usedSeconds(context, packageName) / 60L,
                locked = AppLimitStore.isLocked(context, packageName)
            )
        }.sortedWith(compareBy(WhitelistAppPicker.LABEL_ORDER) { it.label })
        adapter.notifyDataSetChanged()
        binding.emptyLimitText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        updateOverview()
    }

    /** 今日概览与计时自检一起刷新 —— 这两行说的都是「此刻的状态」。 */
    private fun updateOverview() {
        val context = requireContext()
        val summary = AppLimitStore.summary(context)
        binding.overviewUsedText.text = getString(
            R.string.app_limit_overview_used,
            formatMinutes(summary.usedMinutes),
            summary.appCount
        )
        val accessibilityOn = LockHealth.isAccessibilityOn(context)
        val usageAccessOn = AppLimitWatcher.hasUsageAccess(context)
        binding.accessibilityHintCard.visibility = if (accessibilityOn || usageAccessOn) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.usageAccessCard.visibility = if (usageAccessOn) View.GONE else View.VISIBLE
        val countedAt = AppLimitStore.lastCountedAt(context)
        lastShownCountedAt = countedAt
        binding.overviewStatusText.text = when {
            // 两条来源都没了才是真的不会生效
            !accessibilityOn && !usageAccessOn -> getString(R.string.app_limit_status_off)
            // 只靠无障碍时，覆盖安装后系统还按旧配置派发事件、连「读窗口」都没拿到 ——
            // 那个差别用户看不见，只会觉得「更新完就不记录了」，所以明说出来。
            !usageAccessOn && !LockHealth.canReadWindows(context) &&
                countedAt == 0L -> getString(R.string.app_limit_status_restart)

            // 只靠无障碍计时：进程活着时能记，但**从最近任务划掉贤就停**。
            // 这正是「删了后台就失效」的成因，得提前讲清楚，而不是等用户自己撞上。
            !usageAccessOn -> getString(R.string.app_limit_status_background_risk)
            countedAt == 0L -> getString(R.string.app_limit_status_ready)
            else -> getString(
                R.string.app_limit_status_ok,
                formatAgo(System.currentTimeMillis() - countedAt)
            )
        }
    }

    /** 只有「最近计时时刻」真的变了才重绑列表 —— 每 3 秒刷一次已经够勤了，别再让它闪。 */
    private fun refreshIfChanged() {
        if (AppLimitStore.lastCountedAt(requireContext()) == lastShownCountedAt) return
        refresh()
    }

    /** 够一小时就说「X 小时 Y 分」—— 97 分钟这种读起来没概念。 */
    private fun formatMinutes(minutes: Long): String = if (minutes >= 60L) {
        getString(R.string.app_limit_duration_hours, minutes / 60L, minutes % 60L)
    } else {
        getString(R.string.app_limit_duration_minutes, minutes)
    }

    private fun formatAgo(elapsedMillis: Long): String = when {
        elapsedMillis < 60_000L -> getString(R.string.app_limit_ago_just_now)
        elapsedMillis < 3_600_000L ->
            getString(R.string.app_limit_ago_minutes, elapsedMillis / 60_000L)
        else -> getString(R.string.app_limit_ago_hours, elapsedMillis / 3_600_000L)
    }

    /** 复用锁机的应用选择器；新勾上的先给默认额度，再点进列表逐个调。 */
    private fun pickApps() {
        val context = requireContext()
        val current = AppLimitStore.limitedPackages(context).toSet()
        WhitelistAppPicker.show(
            context,
            viewLifecycleOwner.lifecycleScope,
            current,
            R.string.app_limit_picker_title
        ) { selected ->
            val added = selected - current
            val removed = current - selected
            val finish = {
                refresh()
                if (added.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        getString(R.string.app_limit_added_toast, added.size, DEFAULT_LIMIT_MINUTES),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            // 给应用加限额是**收紧**，直接生效；
            // 取消勾选 = 取消限额，是**放宽** —— 与列表弹窗里选「不限制」走同一套三道闸。
            // 只拦弹窗那条路的话，这里取消一个勾就等于绕开了提醒 / 冷静期 / 密码。
            added.forEach { AppLimitStore.setLimit(context, it, DEFAULT_LIMIT_MINUTES) }
            if (removed.isEmpty()) {
                finish()
            } else {
                confirmRemove(removed.toList()) {
                    removed.forEach { AppLimitStore.setLimit(context, it, 0) }
                    finish()
                }
            }
        }
    }

    private fun showLimitDialog(row: Row) {
        val presets = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = presets.map { getString(R.string.app_limit_minutes_format, it) } +
            getString(R.string.app_limit_unlimited)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_limit_dialog_title, row.label))
            .setItems(labels.toTypedArray()) { _, which ->
                val minutes = if (which < presets.size) presets[which] else 0
                confirmAdjust(row, minutes)
            }
            .show()
    }

    /**
     * 调节时长的三道闸，入口一：列表弹窗里改额度。
     * 把后果说明白（已用照算、改低了立刻锁死、取消限制不归零），确认后进冷静期。
     * 这道取代了旧的「改低二次确认」—— 提醒文案里已经包含同样的信息。
     */
    private fun confirmAdjust(row: Row, minutes: Int) {
        val newLimitText =
            if (minutes > 0) getString(R.string.app_limit_minutes_format, minutes)
            else getString(R.string.app_limit_unlimited)
        val body = if (minutes > 0) {
            getString(R.string.app_limit_adjust_message, row.label, newLimitText, row.usedMinutes)
        } else {
            getString(R.string.app_limit_adjust_unlimited_message, row.label, row.usedMinutes)
        } + adjustNoteTail()
        runAdjustGate(body) { applyLimit(row, minutes) }
    }

    /** 入口二：选择器里取消勾选（= 取消限额）。同样是放宽，同样过闸。 */
    private fun confirmRemove(packages: List<String>, onApply: () -> Unit) {
        val pm = requireContext().packageManager
        val names = packages.joinToString(getString(R.string.app_limit_remove_separator)) { name ->
            runCatching { pm.getApplicationInfo(name, 0) }
                .getOrNull()?.let { pm.getApplicationLabel(it).toString() } ?: name
        }
        runAdjustGate(getString(R.string.app_limit_remove_message, names) + adjustNoteTail(), onApply)
    }

    /** 提醒文案的尾巴：冷静期 + 密码。密码那段只在开关真的开着时才写，免得说了做不到。 */
    private fun adjustNoteTail(): String = getString(R.string.app_limit_adjust_note) +
        if (LockPin.isRequired(requireContext(), PinScope.APP_LIMIT)) {
            getString(R.string.app_limit_adjust_note_pin)
        } else {
            ""
        }

    /**
     * 三道闸的骨架，两个入口共用：提醒 → 30 秒冷静期 → 密码。顺序照抄退出锁机
     * （冷静期拦冲动，密码拦「我自己」），调用方只管最后拿到 onApply。
     */
    private fun runAdjustGate(body: String, onApply: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_limit_adjust_title)
            .setMessage(body)
            .setPositiveButton(R.string.app_limit_adjust_proceed) { _, _ ->
                startCooldown(onApply)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 第二道：**30 秒冷静期**。倒计时没走完「确认调整」保持禁用 —— 与退出锁机的
     * 冷静期同一套思路：拦的是「一时冲动改口子」，真想清楚了不差这半分钟。
     */
    private fun startCooldown(onApply: () -> Unit) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_limit_cooldown_title)
            .setMessage(getString(R.string.app_limit_cooldown_message, ADJUST_COOLDOWN_SECONDS))
            .setPositiveButton(R.string.app_limit_cooldown_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val confirm = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        confirm.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            var remainSeconds = ADJUST_COOLDOWN_SECONDS
            while (remainSeconds > 0 && dialog.isShowing) {
                dialog.setMessage(getString(R.string.app_limit_cooldown_message, remainSeconds))
                delay(1_000L)
                remainSeconds--
            }
            if (!dialog.isShowing) return@launch
            dialog.setMessage(getString(R.string.app_limit_cooldown_ready))
            confirm.isEnabled = true
        }
        confirm.setOnClickListener {
            dialog.dismiss()
            verifyThenApply(onApply)
        }
    }

    /** 第三道：**密码**。开了「应用限额需要密码」就先验 PIN，与加时共用同一套开关。 */
    private fun verifyThenApply(onApply: () -> Unit) {
        if (LockPin.isRequired(requireContext(), PinScope.APP_LIMIT)) {
            showPinDialog(onApply)
        } else {
            onApply()
        }
    }

    private fun showPinDialog(onPass: () -> Unit) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(LockPin.PIN_LENGTH))
            hint = getString(R.string.app_limit_pin_dialog_hint)
        }
        val wrap = FrameLayout(context).apply {
            val pad = (22 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_limit_pin_dialog_title)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (LockPin.check(context, input.text.toString())) {
                dialog.dismiss()
                onPass()
            } else {
                input.error = getString(R.string.app_limit_pin_dialog_error)
                input.setText("")
            }
        }
    }

    private fun applyLimit(row: Row, minutes: Int) {
        AppLimitStore.setLimit(requireContext(), row.packageName, minutes)
        refresh()
    }

    private inner class RowAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_limit, parent, false)
            val row = rows[position]
            view.findViewById<ImageView>(R.id.limitAppIcon).setImageDrawable(row.icon)
            view.findViewById<TextView>(R.id.limitAppLabel).text = row.label
            view.findViewById<TextView>(R.id.limitAppDetail).text = if (row.locked) {
                getString(R.string.app_limit_row_locked)
            } else {
                getString(R.string.app_limit_row_detail, row.usedMinutes, row.effectiveMinutes)
            }
            view.findViewById<TextView>(R.id.limitAppLimit).text =
                getString(R.string.app_limit_minutes_format, row.effectiveMinutes)
            return view
        }
    }

    companion object {
        private const val DEFAULT_LIMIT_MINUTES = 30

        /** 调节时长的冷静期：与退出锁机同款思路，倒计时走完确认键才可用。 */
        private const val ADJUST_COOLDOWN_SECONDS = 30

        /** 页面停留时的刷新节奏：够快能看出「在涨」，又不至于频繁重绑列表。 */
        private const val LIVE_REFRESH_MILLIS = 3_000L
    }
}
