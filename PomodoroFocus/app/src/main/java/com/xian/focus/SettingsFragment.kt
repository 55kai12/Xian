package com.xian.focus

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.xian.focus.data.DailyGoalPreferences
import com.xian.focus.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var goalPrefs: DailyGoalPreferences

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
            .setMessage("确定要清除所有任务、专注记录、倒数日和设置吗？此操作不可恢复。")
            .setPositiveButton("清除") { _, _ ->
                // 清除 SharedPreferences
                requireContext().getSharedPreferences("focus_preferences", 0).edit().clear().apply()
                requireContext().getSharedPreferences("fortune_data", 0).edit().clear().apply()
                // 清除数据库
                requireContext().deleteDatabase("focuslist.db")
                Toast.makeText(requireContext(), "数据已清除，重启应用后生效", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
