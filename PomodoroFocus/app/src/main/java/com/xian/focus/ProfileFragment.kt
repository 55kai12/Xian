package com.xian.focus

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sl.utakephoto.crop.CropOptions
import com.sl.utakephoto.exception.TakeException
import com.sl.utakephoto.manager.ITakePhotoResult
import com.sl.utakephoto.manager.UTakePhoto
import com.squareup.picasso.Picasso
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
            "累计祈福 ${prefs.getInt("bless_count", 0)} 次 · 最近：${prefs.getString("fortune", "")}" 
        } else "尚未祈福"
    }

    private fun showFortuneData() {
        val prefs = requireContext().getSharedPreferences("fortune_data", 0)
        val fortune = prefs.getString("fortune", null)
        val meaning = prefs.getString("meaning", null)
        val message = if (fortune == null) "还没有每日祈福记录。" else "最近签：$fortune\n\n解签：$meaning\n\n累计祈福：${prefs.getInt("bless_count", 0)} 次"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("每日祈福").setMessage(message).setPositiveButton(R.string.dialog_confirm, null).show()
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
            val cropOptions = CropOptions.Builder()
                .setAspectX(9)
                .setAspectY(16)
                .setWithOwnCrop(true)
                .create()
            try {
                UTakePhoto.with(this)
                    .openAlbum()
                    .setCrop(cropOptions)
                    .build(object : ITakePhotoResult {
                        override fun takeSuccess(uriList: MutableList<Uri>?) {
                            val uri = uriList?.firstOrNull() ?: return
                            saveWallpaper(uri)
                        }
                        override fun takeFailure(ex: TakeException?) {
                            Toast.makeText(requireContext(), R.string.wallpaper_restored, Toast.LENGTH_SHORT).show()
                        }
                        override fun takeCancel() = Unit
                    })
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.wallpaper_restored, Toast.LENGTH_SHORT).show()
            }
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
        Toast.makeText(requireContext(), R.string.exporting, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val tasks = repository.getAllTasks()
                    val records = repository.getAllRecords()
                    val dir = requireContext().getExternalFilesDir(null) ?: return@withContext null
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val out = File(dir, "xian_data_$ts.csv")
                    out.printWriter(Charsets.UTF_8).use { w ->
                        w.println("=== 任务数据 ===")
                        w.println("ID,标题,备注,优先级,预计贤时,已完成贤时,是否完成,截止日期,截止时间,创建时间,分类,重复规则")
                        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        tasks.forEach { t ->
                            w.println(listOf(
                                t.id,
                                "\"${t.title.replace("\"", "\"\"")}\"",
                                "\"${(t.description ?: "").replace("\"", "\"\"")}\"",
                                t.priority,
                                t.estimatedPomodoros,
                                t.completedPomodoros,
                                if (t.isCompleted) "是" else "否",
                                t.dueDate?.let { df.format(Date(it)) } ?: "",
                                t.dueTimeMinutes?.let { "${it / 60}:${it % 60}" } ?: "",
                                df.format(Date(t.createdAt)),
                                t.listType,
                                t.repeatRule
                            ).joinToString(","))
                        }
                        w.println()
                        w.println("=== 专注记录 ===")
                        w.println("ID,关联任务ID,开始时间,结束时间,类型,是否完成,时长(分钟)")
                        records.forEach { r ->
                            val mins = ((r.endTime - r.startTime) / 60000).toInt()
                            w.println(listOf(
                                r.id,
                                r.taskId ?: "",
                                df.format(Date(r.startTime)),
                                df.format(Date(r.endTime)),
                                r.type,
                                if (r.isFinished) "是" else "否",
                                mins
                            ).joinToString(","))
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
                Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
