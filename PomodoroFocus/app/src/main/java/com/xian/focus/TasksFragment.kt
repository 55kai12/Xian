package com.xian.focus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.haibin.calendarview.CalendarView
import com.jaredrummler.materialspinner.MaterialSpinner
import com.xian.focus.data.Task
import com.xian.focus.databinding.DialogAddStudyTaskBinding
import com.xian.focus.databinding.FragmentTasksBinding
import com.xian.focus.databinding.ItemSubtaskEditBinding
import com.xian.focus.service.FocusTimerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class TasksFragment : Fragment() {
    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val taskViewModel: TaskViewModel by activityViewModels()
    private val timerViewModel: TimerViewModel by activityViewModels()
    private val statsViewModel: StatsViewModel by activityViewModels()

    private lateinit var taskAdapter: TaskAdapter
    private var weekStartMillis = 0L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var selectedDate: Long? = null
    private var imageSelectionCallback: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            imageSelectionCallback?.invoke(uri)
        }
        imageSelectionCallback = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTaskList()
        observeState()
        runCatching { setupCalendar() }
    }

    private fun setupCalendar() {
        weekStartMillis = mondayOfWeek(startOfToday())
        selectedDate = startOfToday()
        val density = resources.displayMetrics.density
        val collapsedHeightPx = (72 * density).toInt()
        val calendarRowHeightPx = (44 * density).toInt()
        val trendHeightPx = (48 * density).toInt()
        var expandedHeightPx = calendarRowHeightPx * 6

        fun updateExpandedHeight(year: Int, month: Int, applyImmediately: Boolean = false) {
            expandedHeightPx = calendarRowHeightPx * monthWeekCount(year, month)
            binding.calendarContent.maxExpandHeight = expandedHeightPx - collapsedHeightPx
            if (applyImmediately) {
                binding.calendarDrawer.layoutParams = binding.calendarDrawer.layoutParams.apply {
                    height = expandedHeightPx
                }
            }
        }

        binding.monthCalendar.setWeekStarWithMon()
        binding.monthCalendar.setCalendarItemHeight(calendarRowHeightPx)
        updateExpandedHeight(binding.monthCalendar.curYear, binding.monthCalendar.curMonth)
        fun updateCalendarTitle(showMonth: Boolean) {
            binding.calendarTitle.text = if (showMonth) {
                "${binding.monthCalendar.curYear}.${binding.monthCalendar.curMonth}"
            } else {
                "贤"
            }
        }
        fun showCalendarView(showMonth: Boolean) {
            binding.monthCalendar.visibility = if (showMonth) View.VISIBLE else View.GONE
            binding.monthCalendar.getWeekViewPager().visibility = View.GONE
            binding.monthCalendar.getMonthViewPager().visibility = if (showMonth) View.VISIBLE else View.GONE
            binding.weekCalendarStrip.visibility = if (showMonth) View.GONE else View.VISIBLE
            updateCalendarTitle(showMonth)
        }
        binding.weekCalendarStrip.setWeek(weekStartMillis, selectedDate ?: weekStartMillis)
        binding.weekCalendarStrip.onDateClick = { dayMillis ->
            selectDate(dayMillis)
            binding.monthCalendar.scrollToCalendar(
                Calendar.getInstance().apply { timeInMillis = dayMillis }.get(Calendar.YEAR),
                Calendar.getInstance().apply { timeInMillis = dayMillis }.get(Calendar.MONTH) + 1,
                Calendar.getInstance().apply { timeInMillis = dayMillis }.get(Calendar.DAY_OF_MONTH)
            )
            binding.weekCalendarStrip.setWeek(weekStartMillis, dayMillis)
        }
        showCalendarView(showMonth = false)
        binding.calendarDrawer.layoutParams = binding.calendarDrawer.layoutParams.apply { height = collapsedHeightPx }
        binding.trendChart.alpha = 1f
        binding.trendChart.translationY = 0f

        binding.calendarContent.onExpandProgress = { progress ->
            val lp = binding.calendarDrawer.layoutParams
            lp.height = collapsedHeightPx + ((expandedHeightPx - collapsedHeightPx) * progress).toInt()
            binding.calendarDrawer.layoutParams = lp
            binding.trendChart.alpha = (1f - progress).coerceIn(0f, 1f)
            binding.trendChart.translationY = -binding.trendChart.height * progress
            binding.trendChart.layoutParams = binding.trendChart.layoutParams.apply {
                height = (trendHeightPx * (1f - progress)).toInt()
            }
            showCalendarView(showMonth = progress > 0f)
            binding.calendarContent.interceptHorizontal = progress < 0.5f
        }
        binding.calendarContent.onExpandSettled = { _ ->
            binding.calendarContent.interceptHorizontal = binding.calendarContent.expandState < 0.5f
        }
        binding.calendarContent.onSwipeLeft = {
            weekStartMillis += 7L * DAY_MILLIS
            selectedDate = (selectedDate ?: weekStartMillis) + 7L * DAY_MILLIS
            scrollCalendarToWeekStart()
            binding.weekCalendarStrip.setWeek(weekStartMillis, selectedDate ?: weekStartMillis)
            loadWeekTrend()
            renderCurrentList()
        }
        binding.calendarContent.onSwipeRight = {
            weekStartMillis -= 7L * DAY_MILLIS
            selectedDate = (selectedDate ?: weekStartMillis) - 7L * DAY_MILLIS
            scrollCalendarToWeekStart()
            binding.weekCalendarStrip.setWeek(weekStartMillis, selectedDate ?: weekStartMillis)
            loadWeekTrend()
            renderCurrentList()
        }
        binding.calendarContent.interceptHorizontal = true
        var handleDownY = 0f
        var handleStartProgress = 0f
        val clickSlop = 10f * resources.displayMetrics.density
        binding.calendarPullIndicator.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    handleDownY = event.rawY
                    handleStartProgress = binding.calendarContent.expandState
                    binding.calendarPullIndicator.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - handleDownY
                    val dragRange = binding.calendarContent.maxExpandHeight.toFloat().coerceAtLeast(1f)
                    binding.calendarContent.setExpandProgress(handleStartProgress + dy / dragRange)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val totalDy = event.rawY - handleDownY
                    if (kotlin.math.abs(totalDy) < clickSlop) {
                        binding.calendarContent.setExpanded(binding.calendarContent.expandState < 0.5f)
                    } else {
                        binding.calendarContent.setExpanded(binding.calendarContent.expandState >= 0.5f)
                    }
                    binding.calendarPullIndicator.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { binding.calendarContent.setExpanded(binding.calendarContent.expandState >= 0.5f); true }
                else -> true
            }
        }
        binding.monthCalendar.setOnCalendarSelectListener(object : CalendarView.OnCalendarSelectListener {
            override fun onCalendarOutOfRange(calendar: com.haibin.calendarview.Calendar?) = Unit
            override fun onCalendarSelect(calendar: com.haibin.calendarview.Calendar, isClick: Boolean) {
                // 翻月滑动也会回调选择事件；只有用户点击日期时更新任务筛选。
                if (!isClick) return
                val dayMillis = Calendar.getInstance().apply {
                    set(calendar.year, calendar.month - 1, calendar.day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                selectDate(dayMillis)
                weekStartMillis = mondayOfWeek(dayMillis)
                binding.weekCalendarStrip.setWeek(weekStartMillis, dayMillis)
                loadWeekTrend()
            }
        })
        binding.monthCalendar.setOnMonthChangeListener { year, month ->
            updateExpandedHeight(year, month, binding.calendarContent.expandState >= 1f)
            if (binding.calendarContent.expandState > 0f) {
                binding.calendarTitle.text = "$year.$month"
            }
        }
        binding.monthCalendar.scrollToCurrent()
        binding.trendChart.setData(emptyList())
        loadWeekTrend()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            taskViewModel.refresh()
            loadWeekTrend()
            renderCurrentList()
        }
    }

    private fun scrollCalendarToWeekStart() {
        val calendar = Calendar.getInstance().apply { timeInMillis = weekStartMillis }
        binding.monthCalendar.scrollToCalendar(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun loadWeekTrend() {
        viewLifecycleOwner.lifecycleScope.launch {
            statsViewModel.loadWeeklyCountsForWeek(weekStartMillis)
        }
    }


    private fun selectDate(dayMillis: Long) {
        selectedDate = dayMillis
        renderCurrentList()
    }

    private fun mondayOfWeek(time: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = time }
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val offset = (dayOfWeek - Calendar.MONDAY + 7) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -offset)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun monthWeekCount(year: Int, month: Int): Int {
        val calendar = Calendar.getInstance().apply {
            set(year, month - 1, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val firstDayOffset = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        return (firstDayOffset + daysInMonth + 6) / 7
    }

    private fun setupTaskList() {
        taskAdapter = TaskAdapter(
            selectedTaskIdProvider = { timerViewModel.timerState.value.currentTaskId },
            onStartTask = { task ->
                timerViewModel.startTimer(task.id)
                FocusTimerService.start(requireContext())
                requireActivity().findViewById<BottomNavigationView>(R.id.bottomNavigation)
                    .selectedItemId = R.id.nav_timer
            },
            onToggleTask = { task ->
                taskViewModel.toggleComplete(task) { loadWeekTrend(); renderCurrentList() }
            },
            onToggleSubtask = { subtask -> taskViewModel.toggleSubtask(subtask) },
            onEditTask = { task -> showEditTaskDialog(task) },
            onDeleteTask = { task -> taskViewModel.deleteTask(task) },
            onImageClick = { task -> task.imageUri?.takeIf { it.isNotBlank() }?.let { showImagePreview(it) } }
        )
        binding.tasksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
        }
        var dragOrder: MutableList<Task>? = null
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val order = dragOrder ?: taskAdapter.currentList.toMutableList().also { dragOrder = it }
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from >= order.size || to >= order.size) return false
                // Keep unfinished and completed sections separated while dragging.
                if (order[from].isCompleted != order[to].isCompleted) return false
                val moved = order.removeAt(from)
                order.add(to, moved)
                taskAdapter.submitTasks(order.toList())
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragOrder = taskAdapter.currentList.toMutableList()
                    viewHolder?.itemView?.alpha = 0.85f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                dragOrder?.let { taskViewModel.reorderTasks(it.toList()) }
                dragOrder = null
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        touchHelper.attachToRecyclerView(binding.tasksRecyclerView)

        binding.addTaskButton.setOnClickListener { showAddTaskDialog() }
        binding.fortuneButton.setOnClickListener { showFortuneDialog() }
        binding.menuButton.setOnClickListener { binding.tasksDrawerLayout.openDrawer(androidx.core.view.GravityCompat.START) }
        binding.drawerCountdownItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, CountdownFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun showFortuneDialog() {
        val context = requireContext()
        val dialogDensity = resources.displayMetrics.density
        fun dp(v: Int) = (v * dialogDensity).toInt()
        val fortunes = listOf("上上签", "上吉签", "中吉签", "平安签", "小吉签")
        val interpretations = listOf(
            "今日思路清晰，适合攻克难题，专注会带来好收获。",
            "今日顺势而为，按计划推进，容易得到意外助力。",
            "今日稳中有进，积少成多，坚持就会看见变化。",
            "今日宜静心守成，放慢脚步，先把眼前事情做好。",
            "今日略有波折，调整节奏再出发，结果会逐渐变好。"
        )
        val index = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) % fortunes.size
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(16), dp(24), dp(12)) }
        val circleSize = dp(120)
        val drawButton = TextView(context).apply {
            text = "签"; gravity = Gravity.CENTER; textSize = 26f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B5B4E.toInt()) }
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize); elevation = 8f
        }
        val result = TextView(context).apply { gravity = Gravity.CENTER; textSize = 20f; setTextColor(0xFF3B5B4E.toInt()); visibility = View.GONE }
        val explanation = TextView(context).apply { gravity = Gravity.CENTER; textSize = 14f; setTextColor(0xFF555555.toInt()); visibility = View.GONE }
        val action = com.google.android.material.button.MaterialButton(context).apply { text = "开始抽签"; isAllCaps = false; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) } }
        root.addView(drawButton)
        root.addView(result, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        root.addView(explanation, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(4) })
        root.addView(action)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle(R.string.fortune_title).setView(root).create()
        var state = 0
        action.setOnClickListener {
            when (state) {
                0 -> {
                    state = 1; action.isEnabled = false
                    drawButton.animate().rotationYBy(720f).scaleX(0.8f).scaleY(0.8f).setDuration(900).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                        drawButton.text = "签\n${fortunes[index]}"; drawButton.textSize = 17f
                        drawButton.animate().rotationYBy(720f).scaleX(1f).scaleY(1f).setDuration(600).withEndAction {
                            result.text = fortunes[index]; result.visibility = View.VISIBLE; action.text = "解签"; action.isEnabled = true
                        }.start()
                    }.start()
                }
                1 -> { state = 2; explanation.text = interpretations[index]; explanation.visibility = View.VISIBLE; action.text = "祈福" }
                else -> {
                    val prefs = context.getSharedPreferences("fortune_data", 0)
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    prefs.edit().putString("date", today).putString("fortune", fortunes[index]).putString("meaning", interpretations[index]).putInt("bless_count", prefs.getInt("bless_count", 0) + 1).apply()
                    action.text = "已祈福"; action.isEnabled = false
                    Toast.makeText(context, "祈福成功，愿今日顺遂", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /** 显示任务图片大图预览 */
    private fun showImagePreview(uriStr: String) {
        val context = requireContext()
        val imageView = android.widget.ImageView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            runCatching { setImageURI(android.net.Uri.parse(uriStr)) }
        }
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(imageView)
            setCanceledOnTouchOutside(true)
        }
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (true) {
                        try {
                            taskViewModel.pendingTasks.collect { renderCurrentList() }
                            break
                        } catch (e: Exception) {
                            android.util.Log.e("TasksFragment", "pendingTasks collect error", e)
                            kotlinx.coroutines.delay(100)
                        }
                    }
                }
                launch {
                    runCatching {
                        taskViewModel.subtasks.collect { updateSubtaskCounts() }
                    }
                }
                launch {
                    runCatching {
                        statsViewModel.weeklyCounts.collect {
                            if (statsViewModel.weeklyWeekStart.value == weekStartMillis) {
                                binding.trendChart.setData(it)
                            }
                        }
                    }
                }
                launch {
                    runCatching {
                        timerViewModel.timerState.collect {
                            taskAdapter.notifyItemRangeChanged(0, taskAdapter.itemCount)
                        }
                    }
                }
                launch {
                    runCatching {
                        taskViewModel.errorMessage.collect { msg ->
                            if (!msg.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "保存失败: $msg", Toast.LENGTH_LONG).show()
                                taskViewModel.clearError()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderCurrentList() {
        val source = taskViewModel.pendingTasks.value
        val showCompleted = requireContext().getSharedPreferences("event_settings", 0).getBoolean("show_completed", true)
        val day = selectedDate ?: return
        var list = buildOccurrencesForDay(source, day)
        if (!showCompleted) list = list.filter { !it.isCompleted }
        list = list.sortedWith(compareBy<Task> { it.isCompleted }.thenBy { it.sortOrder }.thenByDescending { it.createdAt })
        taskAdapter.submitTasks(list)
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyView.setText(R.string.empty_pending)
        updateWeekProgressScheme()
    }

    /**
     * 构建某一天要显示的任务实例：
     * - 普通任务：仅 dueDate 等于当天才显示
     * - 重复模板（repeatRule != none 且 templateId == 0）：在适用的每一天生成虚拟实例；
     *   当天已有完成快照则显示快照（已完成），否则显示未完成虚拟实例
     * - 完成快照（templateId != 0）：不单独显示，由其模板带出
     */
    private fun buildOccurrencesForDay(allTasks: List<Task>, day: Long): List<Task> {
        val daySnapshots = allTasks
            .filter { it.templateId != 0 && it.dueDate == day }
            .associateBy { it.templateId }
        val today = startOfDay(System.currentTimeMillis())
        val result = mutableListOf<Task>()
        for (task in allTasks) {
            if (task.templateId != 0) continue
            if (task.repeatRule == TaskViewModel.REPEAT_NONE) {
                // 没有截止日期的普通任务归到「今天」。
                // 旧实现只比较 dueDate == day，null 永远不相等，于是这类任务
                // 在任何一天都不显示，用户既看不到、也勾不掉、更删不掉，成为死数据。
                if (task.dueDate == day || (task.dueDate == null && day == today)) result.add(task)
            } else {
                // 重复任务没填日期时以「创建日期」作为起始锚点。
                // 旧实现 `task.dueDate ?: continue` 会直接跳过整条任务，让它彻底消失；
                // 而 weekly/monthly 需要锚点来判断星期几 / 几号，所以不能简单按"每天"处理。
                val start = task.dueDate ?: startOfDay(task.createdAt)
                if (day < start || !matchesRepeat(task.repeatRule, start, day)) continue
                val snapshot = daySnapshots[task.id]
                if (snapshot != null) result.add(snapshot)
                else result.add(task.copy(dueDate = day))
            }
        }
        return result
    }

    /** 把任意时间戳归零到当天 00:00:00.000，保证与日历上取到的"天"可比较。 */
    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun matchesRepeat(rule: String, start: Long, day: Long): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = day }
        return when (rule) {
            TaskViewModel.REPEAT_DAILY -> true
            TaskViewModel.REPEAT_WEEKLY -> startCal.get(java.util.Calendar.DAY_OF_WEEK) == dayCal.get(java.util.Calendar.DAY_OF_WEEK)
            TaskViewModel.REPEAT_MONTHLY -> startCal.get(java.util.Calendar.DAY_OF_MONTH) == dayCal.get(java.util.Calendar.DAY_OF_MONTH)
            else -> false
        }
    }
    private fun updateWeekProgressScheme() {
        val allTasks = taskViewModel.pendingTasks.value
        val stripProgressMap = HashMap<Long, Float>()
        for (i in 0 until 7) {
            val dayMillis = weekStartMillis + i * DAY_MILLIS
            val dayTasks = buildOccurrencesForDay(allTasks, dayMillis)
            val total = dayTasks.size
            val completed = dayTasks.count { it.isCompleted }
            val progress = if (total > 0) completed.toFloat() / total else 0f
            if (progress > 0f) {
                stripProgressMap[dayMillis] = progress
            }
        }
        binding.weekCalendarStrip.progressMap = stripProgressMap
        binding.weekCalendarStrip.invalidate()
    }

    private fun updateSubtaskCounts() {
        val all = taskViewModel.subtasks.value
        val map = HashMap<Int, Pair<Int, Int>>()
        all.groupBy { it.taskId }.forEach { (taskId, list) ->
            map[taskId] = list.size to list.count { it.isCompleted }
        }
        taskAdapter.subtaskCounts = map
        taskAdapter.subtasksMap = all.groupBy { it.taskId }
        taskAdapter.notifyItemRangeChanged(0, taskAdapter.itemCount)
    }

    /**
     * 往「展开更多」区域里插入一行「预计贤时」输入框。
     *
     * 之前这个值在保存时被写死为 `val estimated = 1`，后果不只是标签难看得像摆设：
     * 番茄钟结束后会执行 `isCompleted = count >= estimatedPomodoros`，
     * 也就是任何任务只要跑完 1 个番茄钟就被自动标记完成，多轮任务根本没法用。
     * 界面上补上这个输入后，预估与「自动化完成」才真正说得通。
     *
     * @return 供调用方读取数值的输入框
     */
    private fun addEstimateRow(
        container: android.widget.LinearLayout,
        initial: Int
    ): android.widget.EditText {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(requireContext()).apply {
            text = "预计贤时（番茄钟个数）"
            textSize = 12f
            setTextColor(0xFF3B5B4E.toInt())
        })
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initial.coerceIn(1, MAX_ESTIMATED_POMODOROS).toString())
            setSelection(text.length)
            hint = "1"
            textSize = 14f
        }
        row.addView(input)
        container.addView(row, 0)
        return input
    }

    private fun estimatedFrom(input: android.widget.EditText): Int =
        (input.text?.toString()?.toIntOrNull() ?: 1).coerceIn(1, MAX_ESTIMATED_POMODOROS)

    private fun showAddTaskDialog() {
        val dialogBinding = DialogAddStudyTaskBinding.inflate(layoutInflater)
        val form = dialogBinding.root.getChildAt(0) as? android.widget.LinearLayout
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val categoryRow = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        var selectedCategory = "生活"
        val categoryChips = mutableListOf<android.widget.TextView>()
        fun paintChip(view: android.widget.TextView, selected: Boolean) {
            view.setTextColor(if (selected) Color.WHITE else 0xFF777777.toInt())
            view.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (selected) 0xFF35BDBB.toInt() else 0xFFF4F4F4.toInt()) }
        }
        fun selectCategory(name: String, clicked: android.widget.TextView) {
            selectedCategory = name; dialogBinding.subjectInput.setText(name)
            categoryChips.forEach { paintChip(it, it === clicked) }
            clicked.animate().cancel(); clicked.scaleX = 0.9f; clicked.scaleY = 0.9f
            clicked.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        fun addCategoryChip(name: String) {
            val chip = android.widget.TextView(requireContext()).apply { text = name; textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7)) }
            paintChip(chip, name == selectedCategory)
            chip.setOnClickListener { selectCategory(name, chip) }
            categoryChips += chip
            categoryRow.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        addCategoryChip("生活"); addCategoryChip("工作")
        dialogBinding.subjectInput.setText("生活")
        val addCategoryChip = android.widget.TextView(requireContext()).apply { text = "+"; textSize = 16f; gravity = android.view.Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); paintChip(this, false) }
        addCategoryChip.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = "自定义分类" }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("添加分类").setView(input).setPositiveButton(R.string.save) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { name -> addCategoryChip(name); selectCategory(name, categoryChips.last()); GroupColorStore.setColor(requireContext(), name, GroupColorStore.palette.first()) }
            }.setNegativeButton(R.string.cancel, null).show()
        }
        categoryRow.addView(addCategoryChip, android.widget.LinearLayout.LayoutParams(-2, -2))
        form?.addView(categoryRow, 0)
        val dateRow = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        var selectedQuickDate: Long? = startOfToday()
        val dateChips = mutableListOf<android.widget.TextView>()
        fun selectDateChip(clicked: android.widget.TextView) {
            dateChips.forEach { paintChip(it, it === clicked) }
            clicked.animate().cancel(); clicked.scaleX = 0.9f; clicked.scaleY = 0.9f
            clicked.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        listOf("今天", "明天", "选择日期", "没有日期").forEachIndexed { index, label ->
            val chip = android.widget.TextView(requireContext()).apply { text = label; textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)) }
            paintChip(chip, index == 0)
            chip.setOnClickListener { selectedQuickDate = when (index) { 0 -> startOfToday(); 1 -> startOfToday() + DAY_MILLIS; 2 -> { DatePickerDialog(requireContext(), { _, y, m, d -> selectedQuickDate = Calendar.getInstance().apply { set(y,m,d,0,0,0); set(Calendar.MILLISECOND,0) }.timeInMillis; selectDateChip(chip) }, Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH), Calendar.getInstance().get(Calendar.DAY_OF_MONTH)).show(); selectedQuickDate }; else -> null }; if (index != 2) selectDateChip(chip) }
            dateChips += chip
            dateRow.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val moreIndex = form?.indexOfChild(dialogBinding.moreOptionsButton) ?: -1
        if (moreIndex >= 0) form?.addView(dateRow, moreIndex)
        dialogBinding.moreOptionsButton.setOnClickListener {
            val expanded = dialogBinding.moreOptionsContainer.visibility == View.VISIBLE
            dialogBinding.moreOptionsContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            dialogBinding.moreOptionsButton.text = if (expanded) "展开更多" else "收起更多"
        }
        val priorities = listOf(
            getString(R.string.priority_high),
            getString(R.string.priority_medium),
            getString(R.string.priority_low)
        )
        dialogBinding.prioritySpinner.setItems(priorities)
        dialogBinding.prioritySpinner.selectedIndex = 1

        val repeatOptions = listOf(
            getString(R.string.repeat_none),
            getString(R.string.repeat_daily),
            getString(R.string.repeat_weekly),
            getString(R.string.repeat_monthly)
        )
        dialogBinding.repeatSpinner.setItems(repeatOptions)
        dialogBinding.repeatSpinner.selectedIndex = 0

        val estimateInput = addEstimateRow(dialogBinding.moreOptionsContainer, 1)

        val subtaskRows = mutableListOf<ItemSubtaskEditBinding>()
        fun renumberSubtasks() {
            subtaskRows.forEachIndexed { index, row ->
                row.subtaskIndex.text = "${index + 1}"
            }
        }
        fun addSubtaskRow() {
            val rowBinding = ItemSubtaskEditBinding.inflate(layoutInflater, dialogBinding.subtaskContainer, false)
            rowBinding.removeSubtaskButton.setOnClickListener {
                dialogBinding.subtaskContainer.removeView(rowBinding.root)
                subtaskRows.remove(rowBinding)
                renumberSubtasks()
            }
            subtaskRows.add(rowBinding)
            dialogBinding.subtaskContainer.addView(rowBinding.root)
            renumberSubtasks()
        }
        dialogBinding.addSubtaskButton.setOnClickListener { addSubtaskRow() }

        var selectedDueDate: Long? = null
        var selectedDueTime: Int? = null
        var selectedImageUri: String? = null
        fun showSelectedImage(uri: Uri?) {
            dialogBinding.imagePreview.visibility = if (uri == null) View.GONE else View.VISIBLE
            if (uri != null) dialogBinding.imagePreview.setImageURI(uri)
        }
        dialogBinding.insertImageButton.setOnClickListener {
            imageSelectionCallback = { uri -> selectedImageUri = uri.toString(); showSelectedImage(uri) }
            imagePicker.launch(arrayOf("image/*"))
        }
        var selectedColor: Int = GroupColorStore.palette[0]
        fun applyCustomPreview(color: Int) {
            dialogBinding.customColorPreview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(3, Color.WHITE)
            }
        }
        applyCustomPreview(selectedColor)
        val colorChips = listOf(
            dialogBinding.colorChip1,
            dialogBinding.colorChip2,
            dialogBinding.colorChip3,
            dialogBinding.colorChip4,
            dialogBinding.colorChip5,
            dialogBinding.colorChip6
        )
        colorChips.forEachIndexed { index, view ->
            val color = GroupColorStore.palette[index]
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            view.setOnClickListener {
                selectedColor = color
                applyCustomPreview(color)
                colorChips.forEach { chip ->
                    (chip.background as? GradientDrawable)?.setStroke(0, Color.TRANSPARENT)
                }
                (view.background as? GradientDrawable)?.setStroke(4, Color.WHITE)
            }
        }
        (colorChips.first().background as? GradientDrawable)?.setStroke(4, Color.WHITE)

        dialogBinding.customColorButton.setOnClickListener {
            ColorPickerDialogBuilder.with(requireContext())
                .setTitle(R.string.custom_color)
                .initialColor(selectedColor)
                .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
                .density(12)
                .setPositiveButton(R.string.save) { _, color, _ ->
                    selectedColor = color
                    applyCustomPreview(color)
                    colorChips.forEach { chip ->
                        (chip.background as? GradientDrawable)?.setStroke(0, Color.TRANSPARENT)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .build()
                .show()
        }

        dialogBinding.dueDateButton.text = getString(R.string.no_due_date)
        dialogBinding.dueTimeButton.text = getString(R.string.no_due_time)
        dialogBinding.dueDateButton.setOnClickListener {
            val calendar = Calendar.getInstance()
            if (selectedDueDate != null) calendar.timeInMillis = selectedDueDate!!
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    selectedDueDate = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    dialogBinding.dueDateButton.text = dateFormat.format(Date(selectedDueDate!!))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        dialogBinding.dueTimeButton.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    selectedDueTime = hourOfDay * 60 + minute
                    dialogBinding.dueTimeButton.text = "%02d:%02d".format(hourOfDay, minute)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_study_task)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = dialogBinding.taskTitleInput.text?.trim()?.toString().orEmpty()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.task_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val subject = dialogBinding.subjectInput.text?.trim()?.toString()
                    ?.takeIf { it.isNotBlank() } ?: TaskViewModel.DEFAULT_GROUP
                val estimated = estimatedFrom(estimateInput)
                val priority = when (dialogBinding.prioritySpinner.selectedIndex) {
                    0 -> 1
                    1 -> 2
                    else -> 3
                }
                val description = dialogBinding.descriptionInput.text?.trim()?.toString()
                    ?.takeIf { it.isNotBlank() }
                val repeatRule = when (dialogBinding.repeatSpinner.selectedIndex) {
                    1 -> TaskViewModel.REPEAT_DAILY
                    2 -> TaskViewModel.REPEAT_WEEKLY
                    3 -> TaskViewModel.REPEAT_MONTHLY
                    else -> TaskViewModel.REPEAT_NONE
                }
                val subtaskTitles = subtaskRows.mapNotNull {
                    it.subtaskTitleInput.text?.toString()?.takeIf { text -> text.isNotBlank() }
                }
                GroupColorStore.setColor(requireContext(), subject, selectedColor)
                taskViewModel.addTask(
                    Task(
                        title = title,
                        description = description,
                        priority = priority,
                        estimatedPomodoros = estimated,
                        dueDate = selectedDueDate ?: selectedQuickDate,
                        dueTimeMinutes = selectedDueTime,
                        imageUri = selectedImageUri,
                        listType = subject,
                        repeatRule = repeatRule
                    ),
                    subtaskTitles
                )
                if (dialogBinding.calendarReminderSwitch.isChecked) {
                    if (selectedDueDate != null) {
                        launchCalendarIntent(title, description, selectedDueDate, selectedDueTime)
                    } else {
                        Toast.makeText(requireContext(), R.string.calendar_no_due, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create().also { dialog ->
                dialog.setCanceledOnTouchOutside(true)
                dialog.setOnShowListener {
                    dialog.window?.setGravity(android.view.Gravity.BOTTOM)
                    dialog.window?.setLayout(-1, -2)
                }
                dialog.show()
            }
    }

    private fun showEditTaskDialog(task: Task) {
        showEditTaskDialogCompat(task)
    }

    private fun showEditTaskDialogCompat(task: Task) {
        val dialogBinding = DialogAddStudyTaskBinding.inflate(layoutInflater)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun createChip(text: String, selected: Boolean) = android.widget.TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(if (selected) Color.WHITE else 0xFF777777.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (selected) 0xFF35BDBB.toInt() else 0xFFF4F4F4.toInt())
            }
        }

        dialogBinding.taskTitleInput.setText(task.title)
        dialogBinding.descriptionInput.setText(task.description.orEmpty())
        dialogBinding.prioritySpinner.setItems(listOf(
            getString(R.string.priority_high), getString(R.string.priority_medium), getString(R.string.priority_low)
        ))
        dialogBinding.prioritySpinner.selectedIndex = (task.priority - 1).coerceIn(0, 2)
        dialogBinding.repeatSpinner.setItems(listOf(
            getString(R.string.repeat_none), getString(R.string.repeat_daily), getString(R.string.repeat_weekly), getString(R.string.repeat_monthly)
        ))
        dialogBinding.repeatSpinner.selectedIndex = when (task.repeatRule) {
            TaskViewModel.REPEAT_DAILY -> 1
            TaskViewModel.REPEAT_WEEKLY -> 2
            TaskViewModel.REPEAT_MONTHLY -> 3
            else -> 0
        }

        val estimateInput = addEstimateRow(dialogBinding.moreOptionsContainer, task.estimatedPomodoros)

        val form = dialogBinding.root.getChildAt(0) as? android.widget.LinearLayout
        var selectedCategory = task.listType.ifBlank { TaskViewModel.DEFAULT_GROUP }

        var selectedColor = GroupColorStore.colorFor(requireContext(), selectedCategory)
        val colorChips = listOf(
            dialogBinding.colorChip1,
            dialogBinding.colorChip2,
            dialogBinding.colorChip3,
            dialogBinding.colorChip4,
            dialogBinding.colorChip5,
            dialogBinding.colorChip6
        )
        fun refreshColorSelection() {
            colorChips.forEachIndexed { index, view ->
                val color = GroupColorStore.palette[index]
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (selectedColor == color) setStroke(dp(2), Color.WHITE)
                }
            }
            dialogBinding.customColorPreview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(selectedColor)
                setStroke(dp(2), Color.WHITE)
            }
        }
        colorChips.forEachIndexed { index, view ->
            view.setOnClickListener {
                selectedColor = GroupColorStore.palette[index]
                refreshColorSelection()
            }
        }
        dialogBinding.customColorButton.setOnClickListener {
            ColorPickerDialogBuilder.with(requireContext())
                .setTitle(R.string.custom_color)
                .initialColor(selectedColor)
                .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
                .density(12)
                .setPositiveButton(R.string.save) { _, color, _ ->
                    selectedColor = color
                    refreshColorSelection()
                }
                .setNegativeButton(R.string.cancel, null)
                .build()
                .show()
        }
        refreshColorSelection()

        val categoryRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        val categoryChips = mutableListOf<android.widget.TextView>()
        fun paintCategoryChip(chip: android.widget.TextView) {
            val selected = chip.text.toString() == selectedCategory
            chip.setTextColor(if (selected) Color.WHITE else 0xFF777777.toInt())
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (selected) 0xFF35BDBB.toInt() else 0xFFF4F4F4.toInt())
            }
        }
        fun selectCategory(category: String, clicked: android.widget.TextView) {
            selectedCategory = category
            dialogBinding.subjectInput.setText(category)
            categoryChips.forEach(::paintCategoryChip)
            selectedColor = GroupColorStore.colorFor(requireContext(), category)
            refreshColorSelection()
            clicked.animate().cancel()
            clicked.scaleX = 0.9f
            clicked.scaleY = 0.9f
            clicked.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        fun addCategoryChip(category: String) {
            val chip = createChip(category, category == selectedCategory)
            categoryChips += chip
            chip.setOnClickListener { selectCategory(category, chip) }
            categoryRow.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        addCategoryChip("生活")
        addCategoryChip("工作")
        if (selectedCategory !in listOf("生活", "工作", "未分类", "")) addCategoryChip(selectedCategory)
        val addCategory = createChip("+", false)
        addCategory.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = "自定义分类" }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("添加分类")
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { category ->
                        GroupColorStore.setColor(requireContext(), category, GroupColorStore.palette.first())
                        addCategoryChip(category)
                        selectCategory(category, categoryChips.last())
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        categoryRow.addView(addCategory)
        form?.addView(categoryRow, 0)

        var selectedImageUri: String? = task.imageUri
        fun showSelectedImage(uri: Uri?) {
            dialogBinding.imagePreview.visibility = if (uri == null) View.GONE else View.VISIBLE
            if (uri != null) dialogBinding.imagePreview.setImageURI(uri)
        }
        selectedImageUri?.let { showSelectedImage(Uri.parse(it)) }
        dialogBinding.insertImageButton.setOnClickListener {
            imageSelectionCallback = { uri -> selectedImageUri = uri.toString(); showSelectedImage(uri) }
            imagePicker.launch(arrayOf("image/*"))
        }

        var selectedDueDate = task.dueDate
        val dateRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val dateChips = mutableListOf<android.widget.TextView>()
        fun paintDateChip(chip: android.widget.TextView, idx: Int) {
            val isSelected = when (idx) {
                0 -> selectedDueDate == startOfToday()
                1 -> selectedDueDate == startOfToday() + DAY_MILLIS
                3 -> selectedDueDate == null
                else -> selectedDueDate != null && selectedDueDate != startOfToday() && selectedDueDate != startOfToday() + DAY_MILLIS
            }
            chip.setTextColor(if (isSelected) Color.WHITE else 0xFF777777.toInt())
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isSelected) 0xFF35BDBB.toInt() else 0xFFF4F4F4.toInt())
            }
        }
        listOf("今天", "明天", "选择日期", "没有日期").forEachIndexed { index, label ->
            val chip = createChip(label, false)
            chip.setOnClickListener {
                when (index) {
                    0 -> selectedDueDate = startOfToday()
                    1 -> selectedDueDate = startOfToday() + DAY_MILLIS
                    2 -> DatePickerDialog(requireContext(), { _, year, month, day ->
                        selectedDueDate = Calendar.getInstance().apply {
                            set(year, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        dateChips.forEachIndexed { i, c -> paintDateChip(c, i) }
                    }, Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH), Calendar.getInstance().get(Calendar.DAY_OF_MONTH)).show()
                    else -> selectedDueDate = null
                }
                dateChips.forEachIndexed { i, c -> paintDateChip(c, i) }
            }
            dateChips += chip
            paintDateChip(chip, index)
            dateRow.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        lateinit var editDialog: android.app.Dialog
        val deleteTaskButton = android.widget.Button(requireContext()).apply {
            text = "删除任务"
            textSize = 13f
            isAllCaps = false
            minHeight = dp(40)
            setTextColor(0xFFB3261E.toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_task, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除任务")
                    .setMessage("确认删除该任务吗？")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("删除") { _, _ ->
                        taskViewModel.deleteTask(task)
                        editDialog.dismiss()
                    }
                .show()
            }
        }
        val moreRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val moreIndex = form?.indexOfChild(dialogBinding.moreOptionsButton) ?: -1
        if (moreIndex >= 0) form?.removeView(dialogBinding.moreOptionsButton)
        dialogBinding.moreOptionsButton.setOnClickListener {
            val expanded = dialogBinding.moreOptionsContainer.visibility == View.VISIBLE
            dialogBinding.moreOptionsContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            dialogBinding.moreOptionsButton.text = if (expanded) "展开更多" else "收起更多"
        }
        moreRow.addView(dialogBinding.moreOptionsButton)
        moreRow.addView(deleteTaskButton, android.widget.LinearLayout.LayoutParams(-2, dp(40)).apply {
            marginStart = dp(8)
        })

        if (moreIndex >= 0) {
            form?.addView(dateRow, moreIndex)
            form?.addView(moreRow, moreIndex + 1)
        } else {
            form?.addView(dateRow)
            form?.addView(moreRow)
        }

        // 子任务编辑
        val subtaskRows = mutableListOf<com.xian.focus.databinding.ItemSubtaskEditBinding>()
        fun renumberSubtasks() {
            subtaskRows.forEachIndexed { index, row ->
                row.subtaskIndex.text = "${index + 1}"
            }
        }
        fun addSubtaskRow(title: String = "") {
            val rowBinding = com.xian.focus.databinding.ItemSubtaskEditBinding.inflate(layoutInflater, dialogBinding.subtaskContainer, false)
            rowBinding.subtaskTitleInput.setText(title)
            rowBinding.removeSubtaskButton.setOnClickListener {
                dialogBinding.subtaskContainer.removeView(rowBinding.root)
                subtaskRows.remove(rowBinding)
                renumberSubtasks()
            }
            subtaskRows.add(rowBinding)
            dialogBinding.subtaskContainer.addView(rowBinding.root)
            renumberSubtasks()
        }
        dialogBinding.addSubtaskButton.setOnClickListener { addSubtaskRow() }
        // 加载已有子任务
        taskViewModel.subtasks.value.filter { it.taskId == task.id }.forEach { addSubtaskRow(it.title) }

        editDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑任务")
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = dialogBinding.taskTitleInput.text?.toString()?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    val repeatRule = listOf(
                        TaskViewModel.REPEAT_NONE, TaskViewModel.REPEAT_DAILY,
                        TaskViewModel.REPEAT_WEEKLY, TaskViewModel.REPEAT_MONTHLY
                    )[dialogBinding.repeatSpinner.selectedIndex]
                    GroupColorStore.setColor(requireContext(), selectedCategory, selectedColor)
                    val subtaskTitles = subtaskRows.mapNotNull {
                        it.subtaskTitleInput.text?.toString()?.takeIf { text -> text.isNotBlank() }
                    }
                    taskViewModel.updateTaskWithSubtasks(task.copy(
                        title = title,
                        description = dialogBinding.descriptionInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                        listType = selectedCategory,
                        dueDate = selectedDueDate,
                        imageUri = selectedImageUri,
                        priority = dialogBinding.prioritySpinner.selectedIndex + 1,
                        estimatedPomodoros = estimatedFrom(estimateInput),
                        repeatRule = repeatRule
                    ), subtaskTitles)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        editDialog.show()
    }

    private fun launchCalendarIntent(
        title: String,
        description: String?,
        dueDate: Long,
        dueTimeMinutes: Int?
    ) {
        val start = dueDate + (dueTimeMinutes?.times(60_000L) ?: (9L * 60L * 60L * 1000L))
        val end = start + 30L * 60L * 1000L
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, description ?: "")
            .putExtra(CalendarContract.Events.HAS_ALARM, 1)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.calendar_no_due, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        /** 单个任务允许的预计番茄钟数量上限，防止手滑输入离谱数值。 */
        private const val MAX_ESTIMATED_POMODOROS = 99
    }
}
