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
import android.widget.Toast
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
                .replace(R.id.fragmentContainer, EventSettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.aboutCard.setOnClickListener { showAboutDialog() }
        binding.clearDataCard.setOnClickListener { showClearDataDialog() }
    }

    private fun updateDailyGoalText() {
        binding.dailyGoalValueText.text = "每天 ${goalPrefs.getDailyGoal()} 个贤时"
    }

    private fun updateVersionText() {
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
        binding.versionText.text = "贤 v$versionName"
    }

    private fun showDailyGoalDialog() {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(goalPrefs.getDailyGoal().toString())
            setSelection(text.length)
            hint = "输入每日目标贤时数"
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("设置每日贤时目标")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value >= 1) {
                    goalPrefs.setDailyGoal(value)
                    updateDailyGoalText()
                    Toast.makeText(requireContext(), "已更新为每天 $value 个贤时", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "请输入大于0的数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog() {
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
        val message = "贤 v$versionName\n\n中国古风专注助手\n\n功能：\n· 任务清单与番茄钟\n· 锁机专注\n· 个性化复盘与日记\n· 倒数日\n· 每日祈福"
        AlertDialog.Builder(requireContext())
            .setTitle("关于贤")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showClearDataDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清除所有数据")
            .setMessage("确定要清除所有任务、专注记录、倒数日、日记和全部设置吗？此操作不可恢复。")
            .setPositiveButton("清除") { _, _ -> clearAllData() }
            .setNegativeButton("取消", null)
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

            Toast.makeText(requireContext(), "所有数据已清除", Toast.LENGTH_LONG).show()
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
