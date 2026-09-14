package com.xian.focus

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xian.focus.data.AppDatabase
import com.xian.focus.data.DailyGoalPreferences
import com.xian.focus.databinding.FragmentSettingsBinding
import com.xian.focus.service.FocusTimerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var goalPrefs: DailyGoalPreferences

    @Inject
    lateinit var database: AppDatabase

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        goalPrefs = DailyGoalPreferences(requireContext())

        updateDailyGoalText()
        updateVersionText()

        binding.settingsBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.dailyGoalCard.setOnClickListener { showDailyGoalDialog() }
        binding.eventSettingsCard.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, EventSettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.aboutCard.setOnClickListener { showAboutDialog() }
        binding.lockSettingsCard.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, LockSettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.clearDataCard.setOnClickListener { showClearDataDialog() }
        refreshHealth()
    }

    override fun onResume() {
        super.onResume()
        // 用户从系统设置页返回时，状态自动更新
        if (_binding != null) refreshHealth()
    }

    /**
     * 刷新「锁机运行状态」四项前提。
     * 之前锁机在任何一项缺失时都只是静默失效，用户无从判断问题出在哪，
     * 现在直接把缺什么摆出来，点一下就能去开。
     */
    private fun refreshHealth() {
        val items = LockHealth.check(requireContext())
        val rows = listOf(
            binding.healthRowAccessibility to binding.healthStatusAccessibility,
            binding.healthRowOverlay to binding.healthStatusOverlay,
            binding.healthRowNotification to binding.healthStatusNotification,
            binding.healthRowBattery to binding.healthStatusBattery
        )
        items.forEachIndexed { index, item ->
            val (row, status) = rows[index]
            status.setText(if (item.granted) R.string.health_ok else R.string.health_missing)
            status.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (item.granted) R.color.success else R.color.danger_alt
                )
            )
            row.setOnClickListener { openHealthSettings(item.settingsIntent) }
        }
        // 自启动没法在应用内读到真实状态（各 ROM 的白名单没有标准 API），所以只做引导
        binding.healthRowAutostart.setOnClickListener {
            openHealthSettings(LockHealth.autostartIntent(requireContext()))
        }
    }

    private fun openHealthSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    private fun updateDailyGoalText() {
        binding.dailyGoalValueText.text = getString(R.string.goal_summary, goalPrefs.getDailyGoal())
    }

    private fun updateVersionText() {
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
        binding.versionText.text = getString(R.string.about_version, versionName)
    }

    private fun showDailyGoalDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_daily_goal, null, false)
        val input = view.findViewById<EditText>(R.id.goalInput)
        input.setText(goalPrefs.getDailyGoal().toString())
        input.setSelection(input.text.length)
        // 快捷值只把数字填进输入框，落盘仍然只走「保存」这一条路径
        val presets = view.findViewById<LinearLayout>(R.id.goalPresets)
        for (index in 0 until presets.childCount) {
            val preset = presets.getChildAt(index) as TextView
            preset.setOnClickListener {
                input.setText(preset.text.toString())
                input.setSelection(input.text.length)
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.goal_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value >= 1) {
                    goalPrefs.setDailyGoal(value)
                    updateDailyGoalText()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.goal_updated, value),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), R.string.goal_invalid, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
        val view = layoutInflater.inflate(R.layout.dialog_about, null, false)
        view.findViewById<TextView>(R.id.aboutDialogVersion).text =
            getString(R.string.about_version, versionName)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showClearDataDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_data)
            .setMessage(R.string.clear_data_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ -> clearAllData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 彻底清除本机所有用户数据。
     *
     * 旧实现只清了 focus_preferences 和 fortune_data 两个 SharedPreferences，
     * 然后 deleteDatabase —— 结果：
     * 1. 日记（review_prefs）、应用锁 PIN（app_lock_prefs）、主题、壁纸、锁机设置、
     *    分类配色、事件设置全部残留，用户以为清干净了，其实隐私数据还在；
     * 2. 直接删库文件，但已经打开的数据库连接仍持有文件句柄，部分机型上
     *    "删了个寂寞"，数据依旧能读出来。
     *
     * 现在改为：停服务 → 清全部 prefs → clearAllTables() 清空库 → 清文件 → 重启界面。
     */
    private fun clearAllData() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // 正在跑的计时 / 锁机会在清空后把状态再写回来，必须先停
            runCatching { FocusTimerService.stop(appContext) }
            runCatching { LockMachineService.stop(appContext) }
            runCatching { LockMachineScheduler.cancel(appContext) }

            withContext(Dispatchers.IO) {
                // 同步 commit：确保在提示用户之前就已经真正落盘
                ALL_PREFS.forEach { name ->
                    runCatching {
                        appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                            .edit().clear().commit()
                    }
                }
                // 用 clearAllTables 而不是 deleteDatabase：
                // 复用同一个已打开的连接清表，删得干净且不会留下悬空的单例
                runCatching { database.clearAllTables() }
                // 壁纸与导出缓存文件
                runCatching { File(appContext.filesDir, "wallpaper.jpg").takeIf { it.exists() }?.delete() }
                runCatching { appContext.getExternalFilesDir(null)?.listFiles()?.forEach { it.delete() } }
            }

            Toast.makeText(requireContext(), R.string.clear_done, Toast.LENGTH_LONG).show()
            restartToFreshState()
        }
    }

    /**
     * 用全新的 Activity 重建界面。
     * ViewModel 是挂在 Activity 上的，只 recreate() 不会让它们丢掉旧数据，
     * 所以这里带 CLEAR_TASK 重新启动并结束当前实例。
     */
    private fun restartToFreshState() {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** 本应用用到的全部 SharedPreferences 文件名，清数据时必须一个都不能漏。 */
        val ALL_PREFS = listOf(
            "focus_preferences",   // 每日目标
            "timer_settings",      // 番茄钟时长 + 计时 session + 锁机开关
            "event_settings",      // 事件设置 / 显示选项 / 节假日缓存
            "review_prefs",        // 每日复盘日记（文字、评分、图片）
            "app_lock_prefs",      // 应用锁 PIN
            "group_color_prefs",   // 分类配色
            "lock_machine_prefs",  // 自定义锁机的结束时间与白名单
            "lock_schedule_prefs", // 自定义锁机时间段
            "theme_prefs",         // 主题
            "wallpaper_prefs",     // 壁纸
            "fortune_data"         // 每日祈福
        )
    }
}
