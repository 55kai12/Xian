package com.xian.focus

import android.app.DatePickerDialog
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xian.focus.data.Countdown
import com.xian.focus.databinding.FragmentCountdownBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class CountdownFragment : Fragment() {

    private var _binding: FragmentCountdownBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CountdownViewModel by viewModels()
    private lateinit var adapter: CountdownAdapter

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var selectedTargetDate: Long = 0L
    private var selectedColor: Int = 0xFF3B5B4E.toInt()
    private var currentDialog: android.app.Dialog? = null

    private val presetColors = listOf(
        0xFF3B5B4E.toInt(), // 墨绿
        0xFFC9A961.toInt(), // 古铜金
        0xFF8B4513.toInt(), // 赭石
        0xFF4A6FA5.toInt(), // 黛蓝
        0xFFA0522D.toInt(), // 朱砂
        0xFF2F4F4F.toInt()  // 玄墨
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCountdownBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeData()
        binding.addCountdownButton.setOnClickListener { showAddEditDialog(null) }
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onResume() {
        super.onResume()
        // 排序方式可能刚在设置页改过：Room 流不会因此重发数据，手动让列表按新规则重排一次
        viewModel.refreshSort()
    }

    private fun setupRecyclerView() {
        adapter = CountdownAdapter(
            viewModel = viewModel,
            onItemClick = { countdown -> showAddEditDialog(countdown) },
            onItemLongClick = { countdown -> showDeleteDialog(countdown) }
        )
        binding.countdownRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.countdownRecyclerView.adapter = adapter
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.countdowns.collect { list ->
                    adapter.submitList(list)
                    binding.emptyCountdownView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showAddEditDialog(countdown: Countdown?) {
        selectedTargetDate = countdown?.let { viewModel.getEffectiveTargetDate(it) }
            ?: run {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, 7)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
        selectedColor = countdown?.color ?: 0xFF3B5B4E.toInt()

        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.countdown_name_hint)
            setText(countdown?.title ?: "")
            textSize = 16f
        }
        dialogView.addView(titleInput)

        val noteInput = EditText(requireContext()).apply {
            hint = getString(R.string.countdown_note_hint)
            setText(countdown?.note ?: "")
            textSize = 14f
        }
        dialogView.addView(noteInput)

        fun targetDateLabel(): String =
            getString(R.string.countdown_target_date, dateFormat.format(Date(selectedTargetDate)))

        val dateButton = TextView(requireContext()).apply {
            text = targetDateLabel()
            textSize = 14f
            setPadding(0, 32, 0, 0)
            setTextColor(requireContext().themedColor(R.attr.colorBrandContent, R.color.theme_qinglv_primary_content))
            setOnClickListener {
                val cal = Calendar.getInstance().apply { timeInMillis = selectedTargetDate }
                DatePickerDialog(
                    requireContext(),
                    { _, year, month, day ->
                        selectedTargetDate = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        text = targetDateLabel()
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        dialogView.addView(dateButton)

        // 每年重复 + 删除（水平排列）
        val repeatRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
        // 用布尔量记状态，而不是去判断文案里有没有「✓」—— 文案是要翻译的，靠它做逻辑一翻就废。
        var repeatChecked = countdown?.repeatYearly == true
        val repeatText = TextView(requireContext()).apply {
            text = getString(
                if (repeatChecked) R.string.countdown_repeat_yearly_on else R.string.countdown_repeat_yearly
            )
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                repeatChecked = !repeatChecked
                text = getString(
                    if (repeatChecked) R.string.countdown_repeat_yearly_on else R.string.countdown_repeat_yearly
                )
            }
        }
        repeatRow.addView(repeatText)
        if (countdown != null) {
            val deleteButton = TextView(requireContext()).apply {
                text = getString(R.string.action_delete)
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.danger_alt))
                setPadding(24, 0, 0, 0)
                setOnClickListener {
                    viewModel.deleteCountdown(countdown)
                    toastMovedToRecycleBin()
                    currentDialog?.dismiss()
                }
            }
            repeatRow.addView(deleteButton)
        }
        dialogView.addView(repeatRow)

        // 颜色选择
        val colorLabel = TextView(requireContext()).apply {
            text = getString(R.string.countdown_color_pick)
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        dialogView.addView(colorLabel)

        val colorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presetColors.forEach { color ->
            val colorCircle = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 16 }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedColor) {
                        setStroke(4, ContextCompat.getColor(requireContext(), R.color.text_primary))
                    }
                }
                setOnClickListener {
                    selectedColor = color
                    for (i in 0 until colorRow.childCount) {
                        (colorRow.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)?.setStroke(0, 0)
                    }
                    (background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                        4, ContextCompat.getColor(requireContext(), R.color.text_primary)
                    )
                }
            }
            colorRow.addView(colorCircle)
        }
        dialogView.addView(colorRow)

        currentDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(if (countdown == null) R.string.countdown_add else R.string.countdown_edit))
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.countdown_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val repeatYearly = repeatChecked
                if (countdown == null) {
                    viewModel.addCountdown(
                        Countdown(
                            title = title,
                            targetDate = selectedTargetDate,
                            repeatYearly = repeatYearly,
                            color = selectedColor,
                            sortOrder = System.currentTimeMillis().toInt(),
                            note = noteInput.text.toString().trim()
                        )
                    )
                } else {
                    viewModel.updateCountdown(
                        countdown.copy(
                            title = title,
                            targetDate = if (repeatYearly) countdown.targetDate else selectedTargetDate,
                            repeatYearly = repeatYearly,
                            color = selectedColor,
                            note = noteInput.text.toString().trim()
                        )
                    )
                }
                // 倒数日提醒同样是闹钟驱动。提醒开关关着根本不会排闹钟，那就不问。
                if (CountdownReminderScheduler.isEnabled(requireContext())) {
                    ExactAlarms.requestIfNeeded(requireContext())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(countdown: Countdown) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.countdown_delete)
            .setMessage(getString(R.string.countdown_delete_confirm, countdown.title))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCountdown(countdown)
                toastMovedToRecycleBin()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
