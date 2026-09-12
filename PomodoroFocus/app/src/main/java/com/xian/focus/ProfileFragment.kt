package com.xian.focus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.squareup.picasso.Picasso
import com.xian.focus.data.DataRestore
import com.xian.focus.data.FocusRepository
import com.xian.focus.databinding.DialogAppearanceBinding
import com.xian.focus.databinding.FragmentProfileBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var repository: FocusRepository

    /**
     * 壁纸选择：使用系统相册选择器，零权限。
     * 旧实现走 uTakePhoto，而该库会在 manifest 合并时带进
     * CAMERA / READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE / ACCESS_COARSE_LOCATION
     * 四个敏感权限 —— 只为换一张壁纸，性价比完全说不通。
     */
    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) saveWallpaper(uri)
        }

    /**
     * 数据导入：同样走 SAF，不申请存储权限。
     * MIME 列得宽一些 —— 各家文件管理器对 .csv 的登记不一致
     * （text/csv、text/comma-separated-values、甚至 application/octet-stream），
     * 只写 text/csv 的话在某些机型上会看到文件被置灰选不中。
     */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmImport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateCurrentThemeText()
        binding.appearanceCard.setOnClickListener { showAppearanceDialog() }
        binding.exportDataButton.setOnClickListener { exportData() }
        binding.fortuneDataCard.setOnClickListener { showFortuneData() }
        binding.settingsCard.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        val prefs = requireContext().getSharedPreferences("fortune_data", 0)
        binding.fortuneDataText.text = if (prefs.contains("fortune")) {
            getString(
                R.string.fortune_summary,
                prefs.getInt("bless_count", 0),
                prefs.getString("fortune", "").orEmpty()
            )
        } else {
            getString(R.string.fortune_none)
        }
    }

    private fun showFortuneData() {
        val prefs = requireContext().getSharedPreferences("fortune_data", 0)
        val fortune = prefs.getString("fortune", null)
        val meaning = prefs.getString("meaning", null)
        val message = if (fortune == null) {
            getString(R.string.fortune_empty)
        } else {
            getString(
                R.string.fortune_detail,
                fortune,
                meaning.orEmpty(),
                prefs.getInt("bless_count", 0)
            )
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fortune_history)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_confirm, null)
            .show()
    }

    private fun updateCurrentThemeText() {
        val names = arrayOf(R.string.theme_qinglv, R.string.theme_zhusha, R.string.theme_dailan, R.string.theme_xuanmo)
        val idx = ThemeStore.selectedTheme(requireContext()).coerceIn(0, 3)
        binding.currentThemeText.text = getString(names[idx])
    }

    private fun showAppearanceDialog() {
        val dialogBinding = DialogAppearanceBinding.inflate(layoutInflater)
        val selected = ThemeStore.selectedTheme(requireContext())
        val initial: RadioButton = when (selected) {
            ThemeStore.THEME_ZHUSHA -> dialogBinding.themeZhusha
            ThemeStore.THEME_DAILAN -> dialogBinding.themeDailan
            ThemeStore.THEME_XUANMO -> dialogBinding.themeXuanmo
            else -> dialogBinding.themeQinglv
        }
        initial.isChecked = true

        dialogBinding.themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeZhusha -> ThemeStore.THEME_ZHUSHA
                R.id.themeDailan -> ThemeStore.THEME_DAILAN
                R.id.themeXuanmo -> ThemeStore.THEME_XUANMO
                else -> ThemeStore.THEME_QINGLV
            }
            if (ThemeStore.selectedTheme(requireContext()) != theme) {
                ThemeStore.setSelectedTheme(requireContext(), theme)
                updateCurrentThemeText()
                requireActivity().recreate()
            }
        }

        loadWallpaperPreviewInto(dialogBinding.wallpaperPreview)
        dialogBinding.selectWallpaperButton.setOnClickListener {
            // 交给系统相册选择器，不再申请任何权限。
            // 裁剪也不需要：壁纸显示时由 Picasso 的 centerCrop 按版面比例适配。
            wallpaperPicker.launch(arrayOf("image/*"))
        }
        dialogBinding.restoreWallpaperButton.setOnClickListener {
            WallpaperStore.clear(requireContext())
            Toast.makeText(requireContext(), R.string.wallpaper_restored, Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.appearance_settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm, null)
            .show()
    }

    private fun loadWallpaperPreviewInto(imageView: android.widget.ImageView) {
        val path = WallpaperStore.getPath(requireContext())
        if (path != null && File(path).exists()) {
            imageView.visibility = View.VISIBLE
            Picasso.get().load(File(path)).fit().centerCrop().into(imageView)
        } else {
            imageView.visibility = View.GONE
        }
    }

    private fun saveWallpaper(uri: Uri) {
        try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return
            val file = File(requireContext().filesDir, "wallpaper.jpg")
            file.outputStream().use { output -> input.copyTo(output) }
            WallpaperStore.setPath(requireContext(), file.absolutePath)
            Toast.makeText(requireContext(), R.string.wallpaper_saved, Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.wallpaper_restored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportData() {
        // 说明：下面 CSV 的表头 / 小节名是**备份文件格式**，刻意保持中文不随语言变，
        // 否则换语言导出一次、再导回旧文件就对不上了（解析在 data/DataRestore.kt）。
        Toast.makeText(requireContext(), R.string.exporting, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val tasks = repository.getAllTasks()
                    val subtasks = repository.getAllSubtasks()
                    val records = repository.getAllRecords()
                    val countdowns = repository.getAllCountdownsOnce()
                    val dir = requireContext().getExternalFilesDir(null) ?: return@withContext null
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val out = File(dir, "xian_data_$ts.csv")
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    // CSV 转义：含逗号/引号/换行的字段加引号并转义内部引号
                    fun csv(vararg cells: Any?): String = cells.joinToString(",") { cell ->
                        val text = cell?.toString().orEmpty()
                        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                            "\"" + text.replace("\"", "\"\"") + "\""
                        } else {
                            text
                        }
                    }
                    out.printWriter(Charsets.UTF_8).use { w ->
                        // UTF-8 BOM：不写它的话，Windows 版 Excel 会按系统本地编码解析，
                        // 中文标题 / 备注一打开全是乱码。
                        w.print('\uFEFF')
                        w.println("=== 任务数据 ===")
                        w.println("ID,标题,备注,优先级,预计贤时,已完成贤时,是否完成,截止日期,截止时间,创建时间,分类,重复规则")
                        tasks.forEach { t ->
                            w.println(csv(
                                t.id,
                                t.title,
                                t.description ?: "",
                                t.priority,
                                t.estimatedPomodoros,
                                t.completedPomodoros,
                                if (t.isCompleted) "是" else "否",
                                t.dueDate?.let { df.format(Date(it)) } ?: "",
                                t.dueTimeMinutes?.let { "${it / 60}:${it % 60}" } ?: "",
                                df.format(Date(t.createdAt)),
                                t.listType,
                                t.repeatRule
                            ))
                        }
                        w.println()
                        w.println("=== 子任务 ===")
                        w.println("ID,所属任务ID,标题,是否完成")
                        subtasks.forEach { s ->
                            w.println(csv(s.id, s.taskId, s.title, if (s.isCompleted) "是" else "否"))
                        }
                        w.println()
                        w.println("=== 专注记录 ===")
                        w.println("ID,关联任务ID,开始时间,结束时间,类型,是否完成,时长(分钟)")
                        records.forEach { r ->
                            val mins = ((r.endTime - r.startTime) / 60000).toInt()
                            w.println(csv(
                                r.id,
                                r.taskId ?: "",
                                df.format(Date(r.startTime)),
                                df.format(Date(r.endTime)),
                                r.type,
                                if (r.isFinished) "是" else "否",
                                mins
                            ))
                        }
                        w.println()
                        w.println("=== 倒数日 ===")
                        w.println("ID,标题,目标日期,是否每年重复,备注,创建时间")
                        countdowns.forEach { c ->
                            w.println(csv(
                                c.id,
                                c.title,
                                df.format(Date(c.targetDate)),
                                if (c.repeatYearly) "是" else "否",
                                c.note,
                                df.format(Date(c.createdAt))
                            ))
                        }
                        w.println()
                        // 日记存在 SharedPreferences 里，之前既不在导出范围、也逃过了"清除数据"，
                        // 等于没有任何备份出口 —— 这里一并导出。
                        w.println("=== 每日复盘（日记） ===")
                        w.println("日期,评分(0-3),图片数量,内容")
                        val diaryPrefs = requireContext()
                            .getSharedPreferences("review_prefs", android.content.Context.MODE_PRIVATE)
                        val diaryDates = diaryPrefs.all.keys
                            .mapNotNull { key ->
                                DIARY_KEY_PREFIXES.firstOrNull { key.startsWith(it) }
                                    ?.let { key.removePrefix(it) }
                            }
                            .distinct()
                            .sorted()
                        diaryDates.forEach { date ->
                            val note = diaryPrefs.getString("note_$date", "").orEmpty()
                            val rating = diaryPrefs.getInt("rating_$date", 0)
                            val imageCount = diaryPrefs.getString("images_$date", "")
                                ?.split(",")
                                ?.count { it.isNotBlank() }
                                ?: 0
                            w.println(csv(date, rating, imageCount, note))
                        }
                        w.println()
                        // 应用限额也只在 SharedPreferences 里，不导出等于换台手机重设一遍
                        w.println("=== 应用限额 ===")
                        w.println("包名,每日限额(分钟)")
                        val limitContext = requireContext()
                        AppLimitStore.limitedPackages(limitContext).forEach { packageName ->
                            w.println(
                                csv(
                                    packageName,
                                    AppLimitStore.limitMinutes(limitContext, packageName)
                                )
                            )
                        }
                    }
                    out
                } catch (e: Exception) {
                    null
                }
            }
            if (file != null && file.exists()) {
                val uri: Uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.export_data)))
                Toast.makeText(requireContext(), R.string.export_done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** 导入会往库里写数据且不可逆，先确认再动手。 */
    private fun confirmImport(uri: Uri) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.data_import)
            .setMessage(R.string.import_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> importData(uri) }
            .show()
    }

    private fun importData(uri: Uri) {
        Toast.makeText(requireContext(), R.string.importing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    val text = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: return@withContext null
                    DataRestore.restore(requireContext(), repository, text)
                } catch (e: Exception) {
                    null
                }
            }
            when {
                report == null ->
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_LONG).show()
                report.total == 0 ->
                    Toast.makeText(requireContext(), R.string.import_nothing, Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.import_done, report.total),
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    private companion object {
        /** 日记（每日复盘）在 review_prefs 中的键前缀。 */
        val DIARY_KEY_PREFIXES = listOf("note_", "rating_", "images_")
    }
}
