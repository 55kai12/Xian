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
import com.xian.focus.data.DataBackup
import com.xian.focus.data.DataRestore
import com.xian.focus.data.FocusRepository
import com.xian.focus.databinding.DialogAppearanceBinding
import com.xian.focus.databinding.FragmentProfileBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
     *
     * MIME 直接给最宽的通配而不是逐个列 zip / csv —— 各家文件管理器对这两种类型的
     * 登记并不统一（application/zip、text/csv、text/comma-separated-values、
     * application/octet-stream…），列表写窄了就会出现「文件被置灰选不中」。
     * 格式判断交给 DataRestore 看文件头，选错文件也只会提示「没有可恢复的数据」，不会写坏数据。
     */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmImport(uri)
        }

    /** 导出 →「保存到文件」：SAF 另存为，位置由用户挑，不再只躺在 Android/data 里拿不出来。 */
    private val saveExportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) writeExportTo(uri)
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
        binding.root.post { binding.root.staggerScrollContent() }
        updateCurrentThemeText()
        binding.appearanceCard.setOnClickListener { showAppearanceDialog() }
        binding.exportDataButton.setOnClickListener { exportData() }
        binding.importDataButton.setOnClickListener { importPicker.launch(arrayOf("*/*")) }
        binding.fortuneDataCard.setOnClickListener { showFortuneData() }
        binding.settingsCard.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
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
            // 裁剪也不需要：铺满屏幕的等比裁剪在 MainActivity.loadWallpaper 里做。
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
            Toast.makeText(requireContext(), R.string.wallpaper_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 导出

    /** 导出分两条路：另存到用户自己挑的位置，或者直接分享出去。 */
    private fun exportData() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.data_export)
            .setItems(
                arrayOf(
                    getString(R.string.export_save_to_file),
                    getString(R.string.export_share)
                )
            ) { _, which ->
                if (which == 0) {
                    saveExportPicker.launch(DataBackup.fileName())
                } else {
                    shareExport()
                }
            }
            .show()
    }

    private fun writeExportTo(uri: Uri) {
        Toast.makeText(requireContext(), R.string.exporting, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        DataBackup.write(requireContext(), repository, out)
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                requireContext(),
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 分享：先写一份到缓存目录（系统会自动回收），再交给系统分享面板。 */
    private fun shareExport() {
        Toast.makeText(requireContext(), R.string.exporting, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val target = File(requireContext().cacheDir, DataBackup.fileName())
                val ok = try {
                    target.outputStream().use { out ->
                        DataBackup.write(requireContext(), repository, out)
                    }
                } catch (e: Exception) {
                    false
                }
                if (ok) target else null
            }
            if (file == null) {
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_data)))
        }
    }

    // ------------------------------------------------------------------ 导入

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
                    val input = requireContext().contentResolver.openInputStream(uri)
                        ?: return@withContext null
                    DataRestore.restore(requireContext(), repository, input)
                } catch (e: Exception) {
                    null
                }
            }
            if (report == null) {
                Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(
                requireContext(),
                if (report.total == 0) getString(R.string.import_nothing)
                else getString(R.string.import_done, report.total),
                Toast.LENGTH_LONG
            ).show()
            // 壁纸是整张图换掉的、主题是 setTheme 时才生效的，两者都只有重建 Activity
            // 才会真正铺到界面上 —— 不重建的话看起来就像「导进来了但没变」。
            if (report.wallpaperRestored || report.themeRestored) requireActivity().recreate()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
