package com.xian.focus

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
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
import com.xian.focus.data.CountdownCalendar
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

    /** 弹窗里当前选的是哪种历法。 */
    private var selectedCalendar: Int = CountdownCalendar.SOLAR

    /** 弹窗里暂存的农历月/日/闰月，仅 [selectedCalendar] = 农历时有意义。 */
    private var selectedLunarMonth: Int = 1
    private var selectedLunarDay: Int = 1
    private var selectedLunarLeap: Boolean = false

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
        selectedCalendar = countdown?.calendarType ?: CountdownCalendar.SOLAR
        // 农历初值：编辑既有农历条目就沿用它的月日；其余情况（新建、公历条目、从公历切过来）
        // 一律由当时的公历日期现场换算，省得两套状态各自维护、切来切去对不上号。
        if (selectedCalendar == CountdownCalendar.LUNAR) {
            selectedLunarMonth = countdown?.lunarMonth ?: 1
            selectedLunarDay = countdown?.lunarDay ?: 1
            selectedLunarLeap = countdown?.lunarLeap == true
        } else {
            val lunar = LunarCalendar.solarToLunar(selectedTargetDate)
            selectedLunarMonth = lunar.month
            selectedLunarDay = lunar.day
            selectedLunarLeap = lunar.leap
        }

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

        // ---- 历法：公历 / 农历 ----
        val solarText = TextView(requireContext()).apply {
            text = getString(R.string.countdown_calendar_solar)
            textSize = 14f
            setPadding(0, 24, 40, 0)
        }
        val lunarText = TextView(requireContext()).apply {
            text = getString(R.string.countdown_calendar_lunar)
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val calendarRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(solarText)
            addView(lunarText)
        }
        dialogView.addView(calendarRow)

        val dateButton = TextView(requireContext()).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
            setTextColor(requireContext().themedColor(R.attr.colorBrandContent, R.color.theme_qinglv_primary_content))
        }
        dialogView.addView(dateButton)

        // ---- 日期文案与历法切换（必须在 repeatText 之前：它的点击回调要刷新日期）----

        // 用布尔量记状态，而不是去判断文案里有没有「✓」—— 文案是要翻译的，靠它做逻辑一翻就废。
        // ⚠️ 声明必须排在 lunarSolarDate() 之前：那个函数要先读它判断「取下一次」还是「取今年那次」。
        var repeatChecked = countdown?.repeatYearly == true

        /**
         * 农历条目的公历落点。**两种语义要分开**（v2.0.91 起）：
         * - 重复：取「下一次」（今年已过就明年）—— 与列表/提醒口径一致，明年自然还会再来；
         * - 不重复：取**今年的**那一天，哪怕已经过了。
         *   ⚠️ 不能直接复用 [LunarCalendar.nextOccurrence]：它保证返回未来，
         *   会把「今年八月十五已过」的一次性条目悄悄挪到明年，跟「只算一次」矛盾。
         */
        fun lunarSolarDate(): Long {
            if (repeatChecked) {
                return LunarCalendar.nextOccurrence(selectedLunarMonth, selectedLunarDay, selectedLunarLeap)
            }
            val thisYear = LunarCalendar.solarToLunar(System.currentTimeMillis()).year
            return LunarCalendar.lunarToSolar(
                thisYear,
                selectedLunarMonth,
                selectedLunarDay,
                selectedLunarLeap
            )
        }

        fun dateText(): String = if (selectedCalendar == CountdownCalendar.LUNAR) {
            getString(
                R.string.countdown_date_lunar_full,
                LunarCalendar.format(
                    requireContext(),
                    selectedLunarMonth,
                    selectedLunarDay,
                    selectedLunarLeap
                ),
                dateFormat.format(Date(lunarSolarDate()))
            )
        } else {
            dateFormat.format(Date(selectedTargetDate))
        }

        fun targetDateLabel(): String = getString(R.string.countdown_target_date, dateText())

        fun refreshCalendarRow() {
            val isLunar = selectedCalendar == CountdownCalendar.LUNAR
            val selectedColorValue =
                requireContext().themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_primary_content)
            val normalColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
            solarText.setTextColor(if (isLunar) normalColor else selectedColorValue)
            lunarText.setTextColor(if (isLunar) selectedColorValue else normalColor)
            dateButton.text = targetDateLabel()
        }

        // 每年重复 + 删除（水平排列）
        val repeatRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
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
                // 农历条目的落点跟这个开关联动（重复取「下一次」、不重复取「今年那次」），
                // 所以切完必须刷新日期文案 —— 否则用户看到的是切换前的旧日子。
                if (selectedCalendar == CountdownCalendar.LUNAR) refreshCalendarRow()
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

        solarText.setOnClickListener {
            // 从农历切回来：把当前的公历落点接过来当起点，日期不会跳回打开弹窗那一刻的值
            if (selectedCalendar != CountdownCalendar.SOLAR) {
                selectedTargetDate = lunarSolarDate()
            }
            selectedCalendar = CountdownCalendar.SOLAR
            refreshCalendarRow()
        }
        lunarText.setOnClickListener {
            // 切到农历：按当前公历日期重新换算，免得拿到的是打开弹窗那一刻的旧值
            if (selectedCalendar != CountdownCalendar.LUNAR) {
                val lunar = LunarCalendar.solarToLunar(selectedTargetDate)
                selectedLunarMonth = lunar.month
                selectedLunarDay = lunar.day
                selectedLunarLeap = lunar.leap
            }
            selectedCalendar = CountdownCalendar.LUNAR
            refreshCalendarRow()
        }

        dateButton.setOnClickListener {
            if (selectedCalendar == CountdownCalendar.LUNAR) {
                showLunarPicker { month, day, leap ->
                    selectedLunarMonth = month
                    selectedLunarDay = day
                    selectedLunarLeap = leap
                    dateButton.text = targetDateLabel()
                }
            } else {
                val cal = Calendar.getInstance().apply { timeInMillis = selectedTargetDate }
                DatePickerDialog(
                    requireContext(),
                    { _, year, month, day ->
                        selectedTargetDate = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        dateButton.text = targetDateLabel()
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }

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

        refreshCalendarRow()

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
                val isLunar = selectedCalendar == CountdownCalendar.LUNAR
                // 农历条目存下的 targetDate 有两层用途（v2.0.91 起）：
                // - 重复时：只是「下一次的公历落点」，给提醒和关联任务一个具体时刻，
                //   真正目标日每次由 effectiveTargetDate 按农历现算，所以它明年不会过期；
                // - 不重复时：**它就是唯一一次的落点**（农历记的是月日、不含年份，
                //   无法从月日反推「哪一年」，必须靠这个公历锚点）。
                val savedTargetDate = if (isLunar) lunarSolarDate() else selectedTargetDate
                if (countdown == null) {
                    viewModel.addCountdown(
                        Countdown(
                            title = title,
                            targetDate = savedTargetDate,
                            repeatYearly = repeatYearly,
                            calendarType = selectedCalendar,
                            lunarMonth = selectedLunarMonth,
                            lunarDay = selectedLunarDay,
                            lunarLeap = selectedLunarLeap,
                            color = selectedColor,
                            sortOrder = System.currentTimeMillis().toInt(),
                            note = noteInput.text.toString().trim()
                        )
                    )
                } else {
                    viewModel.updateCountdown(
                        countdown.copy(
                            title = title,
                            targetDate = savedTargetDate,
                            repeatYearly = repeatYearly,
                            calendarType = selectedCalendar,
                            lunarMonth = selectedLunarMonth,
                            lunarDay = selectedLunarDay,
                            lunarLeap = selectedLunarLeap,
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

    /**
     * 农历日期选择器：月（正月…腊月）+ 日（初一…三十）+ 闰月勾选。
     *
     * 日号一律给到三十，不按当年实际天数收窄 —— 闰月和平月的天数逐年不同，
     * 收窄就得先知道用户要哪一年，而这个弹窗刻意不问年份（农历条目只记月日）。
     * 碰上没有三十的月份，落库时由 LunarCalendar 压到月末。
     */
    private fun showLunarPicker(onPicked: (month: Int, day: Int, leap: Boolean) -> Unit) {
        val context = requireContext()
        val months = LunarCalendar.monthNames(context)
        val days = LunarCalendar.dayNames(context)
        val columnWidth = (resources.displayMetrics.widthPixels * 0.3f).toInt()

        val monthPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = months.size
            value = selectedLunarMonth.coerceIn(1, months.size)
            displayedValues = months
            wrapSelectorWheel = false
        }
        val dayPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = days.size
            value = selectedLunarDay.coerceIn(1, days.size)
            displayedValues = days
            wrapSelectorWheel = false
        }
        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(monthPicker, LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(dayPicker, LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val leapBox = CheckBox(context).apply {
            text = getString(R.string.countdown_lunar_leap)
            isChecked = selectedLunarLeap
        }
        val hint = TextView(context).apply {
            text = getString(R.string.countdown_lunar_leap_note)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
            addView(pickerRow)
            addView(leapBox)
            addView(hint)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.countdown_lunar_pick_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                onPicked(monthPicker.value, dayPicker.value, leapBox.isChecked)
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
