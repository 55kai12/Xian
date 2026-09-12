package com.xian.focus

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xian.focus.databinding.FragmentReviewBinding
import com.wang.avi.AVLoadingIndicatorView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class ReviewFragment : Fragment() {
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    private val statsViewModel: StatsViewModel by activityViewModels()
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 日期显示格式随语言走，不能用字段初始化（那时 Fragment 还没 attach）。 */
    private fun diaryDisplayFormat() =
        SimpleDateFormat(getString(R.string.diary_date_format), Locale.getDefault())
    private var currentDiaryDate: Long = startOfDay(System.currentTimeMillis())
    private var statsLoaded = false
    private var weeklyLoaded = false
    private val currentImageUris = mutableListOf<String>()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            currentImageUris.add(uri.toString())
            saveImages()
            renderImages()
            updateDiaryIndicator()
        }
    }

    companion object {
        private const val DAY_MILLIS = 86400000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadDiary()
        binding.saveButton.setOnClickListener { saveDiary() }
        binding.addImageButton.setOnClickListener {
            imagePicker.launch(arrayOf("image/*"))
        }
        binding.prevDayButton.setOnClickListener {
            currentDiaryDate -= DAY_MILLIS
            loadDiary()
        }
        binding.nextDayButton.setOnClickListener {
            currentDiaryDate += DAY_MILLIS
            loadDiary()
        }
        binding.pickDateButton.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = currentDiaryDate }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    currentDiaryDate = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    loadDiary()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    statsViewModel.stats.collect { stats ->
                        if (stats == null) return@collect
                        binding.todayText.text = stats.todayCount.toString()
                        binding.weekText.text = stats.lastSevenDaysCount.toString()
                        binding.totalText.text = stats.totalCount.toString()
                        binding.minutesText.text = stats.totalMinutes.toString()
                        statsLoaded = true
                        maybeHideLoading()
                    }
                }
                launch {
                    statsViewModel.weeklyCounts.collect {
                        XianCharts.setBarData(binding.weeklyChart, it)
                        weeklyLoaded = true
                        maybeHideLoading()
                    }
                }
                try {
                    statsViewModel.loadStats()
                    statsViewModel.loadWeeklyCounts()
                } catch (e: Exception) {
                    // 统计加载失败不影响页面打开
                    statsLoaded = true
                    weeklyLoaded = true
                    maybeHideLoading()
                }
                // 锁机统计不走 StateFlow：每次回到页面都要拿最新值
                // （锁机结束回 app 时 StateFlow 不会重发同值）
                updateLockStats()
            }
        }
    }

    private fun updateLockStats() {
        val snapshot = LockStats.snapshot(requireContext())
        binding.lockStatSessionsText.text = snapshot.sessions.toString()
        binding.lockStatDurationText.text = formatLockMinutes(snapshot.minutes)
        binding.lockStatHeldText.text = snapshot.held.toString()
    }

    private fun formatLockMinutes(minutes: Int): String = when {
        minutes >= 60 -> getString(R.string.lock_duration_format_hm, minutes / 60, minutes % 60)
        else -> getString(R.string.lock_duration_format_m, minutes)
    }

    private fun maybeHideLoading() {
        if (statsLoaded && weeklyLoaded) {
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("review_prefs", Context.MODE_PRIVATE)

    private fun diaryKey(): String = dateKeyFormat.format(Date(currentDiaryDate))

    private fun imagesKey(): String = "images_${diaryKey()}"

    private fun loadDiary() {
        binding.diaryDateText.text = diaryDisplayFormat().format(Date(currentDiaryDate))
        val prefs = prefs()
        val note = prefs.getString("note_${diaryKey()}", "").orEmpty()
        val rating = prefs.getInt("rating_${diaryKey()}", 0)
        binding.noteInput.setText(note)
        binding.ratingRadioGroup.clearCheck()
        when (rating) {
            1 -> binding.ratingLow.isChecked = true
            2 -> binding.ratingMedium.isChecked = true
            3 -> binding.ratingHigh.isChecked = true
        }
        // 加载图片
        currentImageUris.clear()
        val imagesStr = prefs.getString(imagesKey(), "").orEmpty()
        if (imagesStr.isNotBlank()) {
            currentImageUris.addAll(imagesStr.split(",").filter { it.isNotBlank() })
        }
        renderImages()
        updateDiaryIndicator()
    }

    private fun renderImages() {
        val container = binding.imagesContainer
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (100 * dp).toInt()
        val margin = (6 * dp).toInt()
        for ((index, uriStr) in currentImageUris.withIndex()) {
            val frame = FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    marginEnd = margin
                }
            }
            val imageView = ImageView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_surface))
                runCatching {
                    setImageURI(Uri.parse(uriStr))
                }
                setOnClickListener { showImagePreview(uriStr) }
            }
            val removeBtn = ImageButton(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (24 * dp).toInt(),
                    (24 * dp).toInt()
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = null
                setColorFilter(ContextCompat.getColor(requireContext(), R.color.white))
                contentDescription = getString(R.string.review_remove_image)
                setOnClickListener {
                    currentImageUris.removeAt(index)
                    saveImages()
                    renderImages()
                    updateDiaryIndicator()
                }
            }
            frame.addView(imageView)
            frame.addView(removeBtn)
            container.addView(frame)
        }
        binding.imagesScrollView.visibility = if (currentImageUris.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun saveImages() {
        prefs().edit()
            .putString(imagesKey(), currentImageUris.joinToString(","))
            .apply()
    }

    private fun showImagePreview(uriStr: String) {
        val imageView = ImageView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            runCatching { setImageURI(Uri.parse(uriStr)) }
        }
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(imageView)
            setCanceledOnTouchOutside(true)
        }
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateDiaryIndicator() {
        val prefs = prefs()
        val note = prefs.getString("note_${diaryKey()}", "").orEmpty()
        val rating = prefs.getInt("rating_${diaryKey()}", 0)
        val hasContent = note.isNotBlank() || rating > 0 || currentImageUris.isNotEmpty()
        (binding.diaryIndicatorDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            if (hasContent) {
                requireContext().themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
            } else {
                ContextCompat.getColor(requireContext(), R.color.diary_dot)
            }
        )
    }

    private fun saveDiary() {
        val rating = when (binding.ratingRadioGroup.checkedRadioButtonId) {
            R.id.ratingLow -> 1
            R.id.ratingMedium -> 2
            R.id.ratingHigh -> 3
            else -> 0
        }
        prefs().edit()
            .putString("note_${diaryKey()}", binding.noteInput.text?.toString().orEmpty())
            .putInt("rating_${diaryKey()}", rating)
            .apply()
        updateDiaryIndicator()
        Toast.makeText(requireContext(), R.string.review_saved, Toast.LENGTH_SHORT).show()
    }

    private fun startOfDay(time: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
