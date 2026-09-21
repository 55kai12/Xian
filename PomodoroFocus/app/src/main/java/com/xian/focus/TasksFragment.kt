package com.xian.focus

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.haibin.calendarview.CalendarView
import com.jaredrummler.materialspinner.MaterialSpinner
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.Task
import com.xian.focus.databinding.FragmentTasksBinding
import com.xian.focus.service.FocusTimerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class TasksFragment : Fragment() {
    internal var _binding: FragmentTasksBinding? = null
    internal val binding get() = _binding!!

    internal val taskViewModel: TaskViewModel by activityViewModels()
    private val timerViewModel: TimerViewModel by activityViewModels()
    private val statsViewModel: StatsViewModel by activityViewModels()

    /** 只为了抽屉里「回收站」那行小字要的条数。 */
    @Inject
    lateinit var repository: FocusRepository

    private lateinit var taskAdapter: TaskAdapter
    private var weekStartMillis = 0L
    internal val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    internal var selectedDate: Long? = null
    internal var imageSelectionCallback: ((Uri) -> Unit)? = null
    internal val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
                getString(R.string.app_name)
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
        // 切周后选中日期一律落在「新一周里离原位置最近的那一天」（v2.0.92 起）：
        // - 前进一周 → 下一周的**第一天**（周一）；
        // - 后退一周 → 上一周的**最后一天**（周日）。
        // 全周七天都是这两条：当前选中日只要落在本周内，上一周里离它最近的必是上周日，
        // 下一周里离它最近的必是下周一，跟「原来停在周几」无关（所以不必再读 selectedDate）。
        // ⚠️ 此前是「保持同一星期几」（+7 / -7），用户反馈切周在接缝处对不上。
        binding.calendarContent.onSwipeLeft = {
            weekStartMillis += 7L * DAY_MILLIS
            selectedDate = weekStartMillis
            scrollCalendarToWeekStart()
            binding.weekCalendarStrip.setWeek(weekStartMillis, selectedDate ?: weekStartMillis)
            loadWeekTrend()
            renderCurrentList()
        }
        binding.calendarContent.onSwipeRight = {
            weekStartMillis -= 7L * DAY_MILLIS
            selectedDate = weekStartMillis + 6L * DAY_MILLIS
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
            updateAppLimitSummary()
            updateRecycleBinSummary()
            updateHabitSummary()
            maybeShowYesterdayBanner()
        }
    }

    // ---------- 昨日未完成横幅 ----------

    /**
     * 昨天有没做完的事就弹一次横幅（每天最多一次，见 [YesterdayBannerState]）。
     *
     * 数据源复用 [buildOccurrencesForDay] —— 它对任意一天都算得出「那天看得见的未完成项」，
     * 连重复任务的虚拟实例、「仅删除这一天」的跳过都一并处理了。
     * ⚠️ 别自己另写一套过滤：统计口径与清单口径一旦分家就会各说各话（历史上栽过）。
     *
     * 只在**今天**的视图下弹：用户翻到别的日期时弹「昨天」的提醒会很错乱。
     */
    private fun maybeShowYesterdayBanner() {
        val context = requireContext()
        // ⚠️ include 带 id 时 viewBinding 生成的是包装类 ViewYesterdayBannerBinding，
        // 不是 View —— 根上取 visibility 必须走 .root。
        val banner = binding.yesterdayBanner.root
        val today = startOfDay(System.currentTimeMillis())
        if (selectedDate != today || !YesterdayBannerState.shouldShow(context)) {
            banner.visibility = View.GONE
            return
        }
        val yesterday = yesterdayBannerTasks()
        if (yesterday.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        binding.yesterdayBanner.yesterdayBannerText.text =
            getString(R.string.yesterday_banner_format, yesterday.size)
        banner.visibility = View.VISIBLE
        YesterdayBannerState.markShown(context)
    }

    /**
     * 昨天「看得见但没完成」的任务。
     *
     * ⚠️ 必须排除**今天之后**才到期的重复实例 —— `buildOccurrencesForDay` 对过去某一天
     * 只会带出那一天本来该有的实例，所以这里不需要额外过滤日期；但**普通任务里
     * `dueDate == null` 的会被归到「今天」**（见那个函数里的注释），传昨天时它们不会出现，
     * 正是我们要的。
     */
    private fun yesterdayBannerTasks(): List<Task> {
        val day = startOfDay(System.currentTimeMillis() - DAY_MILLIS)
        return buildOccurrencesForDay(taskViewModel.pendingTasks.value, day)
            .filter { !it.isCompleted }
    }

    /** 「全部移到今天」：把昨天那些未完成的改到今天。 */
    private fun moveYesterdayToToday(tasks: List<Task>) {
        val today = startOfDay(System.currentTimeMillis())
        viewLifecycleOwner.lifecycleScope.launch {
            // 重复任务不能简单改日期（模板是规则、不是某一天的事），
            // 具体怎么挪由 TaskViewModel.moveTasksToDay 一处收口。
            val moved = taskViewModel.moveTasksToDay(tasks, today)
            val view = _binding ?: return@launch
            view.yesterdayBanner.root.visibility = View.GONE
            renderCurrentList()
            loadWeekTrend()
            Toast.makeText(requireContext(), getString(R.string.yesterday_moved_format, moved), Toast.LENGTH_SHORT).show()
        }
    }

    /** 「查看详情」：列出昨天未完成的那几条，点一条跳到昨天那天。 */
    private fun showYesterdayDetails(tasks: List<Task>) {
        val labels = tasks.map { it.title }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yesterday_details_title)
            .setItems(labels) { _, which ->
                // 跳到昨天，让用户在原位置处理那一条
                val day = startOfDay(System.currentTimeMillis() - DAY_MILLIS)
                selectedDate = day
                weekStartMillis = weekStartOf(day)
                scrollCalendarToWeekStart()
                binding.weekCalendarStrip.setWeek(weekStartMillis, day)
                binding.yesterdayBanner.root.visibility = View.GONE
                loadWeekTrend()
                renderCurrentList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 任意一天所在那一周的周一 0 点，与周历条的对齐方式保持一致。 */
    private fun weekStartOf(day: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = day }
        // Calendar.MONDAY = 2，一周从周一起（与「小习惯」的口径一致）
        val shift = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return day - shift * DAY_MILLIS
    }

    /** 抽屉里「应用限额」右边那行小字：今天累计用了多久。没设限额就空着。 */
    private fun updateAppLimitSummary() {
        val summary = AppLimitStore.summary(requireContext())
        binding.drawerAppLimitSummary.text = if (summary.appCount == 0) {
            ""
        } else {
            getString(R.string.app_limit_drawer_summary, summary.usedMinutes)
        }
    }

    /**
     * 抽屉里「回收站」右边那行小字：里面有几条。
     *
     * 任务按「重复系列」算一条 —— 删一次重复任务会往回收站里放几十行快照，
     * 按行数显示等于天天看到个三位数，反而看不出删了几样东西。
     */
    private fun updateRecycleBinSummary() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val series = repository.getTrashedTasks()
                    .map { if (it.templateId != 0) it.templateId else it.id }
                    .distinct()
                    .size
                series + repository.getTrashedCountdowns().size + NoteStore.loadTrash(appContext).size
            }
            val view = _binding ?: return@launch
            view.drawerRecycleBinSummary.text = if (count == 0) "" else count.toString()
        }
    }

    /**
     * 抽屉里「小习惯」右边的小字：今天达标了几个 / 今天该打卡的总数。
     *
     * 一个习惯都没有时留空 —— 显示「0/0」等于告诉用户这里有个空功能。
     * 达标判定问 [HabitStreak]，与打卡页同一口径，免得抽屉说 2/5、页面说 3/5。
     */
    private fun updateHabitSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val today = HabitStreak.startOfDay(now)
            val summary = withContext(Dispatchers.IO) {
                val scheduled = repository.getHabits().filter { it.coversDay(today) }
                if (scheduled.isEmpty()) {
                    null
                } else {
                    val done = scheduled.count { habit ->
                        HabitStreak.status(habit, repository.getHabitLogs(habit.id), now).done
                    }
                    done to scheduled.size
                }
            }
            val view = _binding ?: return@launch
            view.drawerHabitSummary.text = summary
                ?.let { (done, total) -> getString(R.string.habit_count_format, done, total) }
                .orEmpty()
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
        // 先把周起始时刻抓下来：协程真正执行时 weekStartMillis 可能已经被滑周改掉了，
        // 那样算出来的就是别的周的数据，曲线会跟当前显示的一周对不上。
        val weekStart = weekStartMillis
        viewLifecycleOwner.lifecycleScope.launch {
            statsViewModel.loadWeeklyCountsForWeek(weekStart)
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

    internal fun startOfToday(): Long = Calendar.getInstance().apply {
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
            onDeleteTask = { task -> deleteTaskWithConfirm(task) },
            onImageClick = { task -> task.imageUri?.takeIf { it.isNotBlank() }?.let { showImagePreview(it) } },
            onMoveTask = { task, delta -> moveTaskRow(task, delta) }
        )
        binding.tasksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
        }
        var dragOrder: MutableList<Task>? = null
        // 左划手势已交给 item 自己的 SwipeActionLayout —— 它要的是「露出按钮、等点击」，
        // 而 ItemTouchHelper 的 swipe 语义是「滑过阈值就划走」，松手会回弹，等不到点击。
        // 所以这里只保留拖拽排序，swipe 方向传 0 关掉。
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
                    // 拖拽期间所有行都在动，展开着的那一行会跟着跑，先收掉
                    taskAdapter.closeOpenSwipeActions()
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

            // SimpleCallback 要求实现，但 swipeDirs 传的是 0，永远不会被调到。
            // 左划的四个动作在 item 自己的 SwipeActionLayout 里点按钮触发。
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }
        })
        touchHelper.attachToRecyclerView(binding.tasksRecyclerView)

        // 滚动时收起展开的行 —— 否则它会跟着滚走，用户找不到自己在滑哪一行
        binding.tasksRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    taskAdapter.closeOpenSwipeActions()
                }
            }
        })

        binding.addTaskButton.setOnClickListener { showAddTaskDialog() }
        // 昨日未完成横幅的三个动作。列表每次渲染后都会重算「昨天还剩几条」，
        // 所以这里只管动作本身，不在这里判可见性。
        binding.yesterdayBanner.yesterdayMoveAll.setOnClickListener {
            val pending = yesterdayBannerTasks()
            if (pending.isEmpty()) {
                binding.yesterdayBanner.root.visibility = View.GONE
            } else {
                moveYesterdayToToday(pending)
            }
        }
        binding.yesterdayBanner.yesterdayDetails.setOnClickListener {
            val pending = yesterdayBannerTasks()
            if (pending.isEmpty()) {
                Toast.makeText(requireContext(), R.string.yesterday_details_empty, Toast.LENGTH_SHORT).show()
            } else {
                showYesterdayDetails(pending)
            }
        }
        binding.yesterdayBanner.yesterdayDismiss.setOnClickListener {
            YesterdayBannerState.muteToday(requireContext())
            binding.yesterdayBanner.root.visibility = View.GONE
        }
        binding.fortuneButton.setOnClickListener { showFortuneDialog() }
        binding.menuButton.setOnClickListener { binding.tasksDrawerLayout.openDrawer(androidx.core.view.GravityCompat.START) }
        binding.drawerCountdownItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, CountdownFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.drawerNoteItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, NoteFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.drawerAppLimitItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, AppLimitFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.drawerHabitItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, HabitFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.drawerRecycleBinItem.setOnClickListener {
            binding.tasksDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.frag_enter_from_right,
                    R.anim.frag_exit,
                    R.anim.frag_enter_from_left,
                    R.anim.frag_exit
                )
                .replace(R.id.fragmentContainer, RecycleBinFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    /**
     * 每日一签。
     *
     * 抽签结果用「年 + 日序」当随机种子 —— 同一天反复打开拿到同一支签（这是「今日运势」，
     * 不是老虎机，能无限重抽就失去意义了），跨天则完全不同。
     * 旧实现是 `DAY_OF_YEAR % 5`：5 天一个硬循环，而且所有人同一天抽到同一支。
     */
    private fun showFortuneDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val brandSurface = context.themedColor(R.attr.colorBrandSurface, R.color.theme_qinglv_primary)
        val brandContent = context.themedColor(R.attr.colorBrandContent, R.color.theme_qinglv_primary_content)
        val accent = context.themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
        val onPrimary = ContextCompat.getColor(context, R.color.on_primary)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

        val rnd = kotlin.random.Random(
            Calendar.getInstance().run { get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR) }
        )
        // 抽 n 个不重复词条。没用 shuffled：数组版的 shuffled(Random) 在这套 stdlib 上解析不出来；
        // 重抽只用 nextInt，coerce 是为了防以后把 n 调得比池子大时死循环
        fun pick(pool: Array<String>, n: Int): List<String> {
            val ids = LinkedHashSet<Int>()
            while (ids.size < n.coerceAtMost(pool.size)) ids += rnd.nextInt(pool.size)
            return ids.map { pool[it] }
        }
        val levelNames = arrayOf(
            R.string.fortune_level_supreme, R.string.fortune_level_high, R.string.fortune_level_mid,
            R.string.fortune_level_calm, R.string.fortune_level_small
        )
        val levelMsgs = arrayOf(
            R.array.fortune_msg_supreme, R.array.fortune_msg_high, R.array.fortune_msg_mid,
            R.array.fortune_msg_calm, R.array.fortune_msg_small
        )
        // 加权抽等级（上上 8 / 上吉 17 / 中吉 30 / 平安 30 / 小吉 15），再在该级文案池里随机取一条
        val weights = intArrayOf(8, 17, 30, 30, 15)
        var roll = rnd.nextInt(weights.sum())
        var level = 0
        while (level < weights.size - 1 && roll >= weights[level]) {
            roll -= weights[level]
            level++
        }
        val fortuneName = getString(levelNames[level])
        val messages = resources.getStringArray(levelMsgs[level])
        val message = messages[rnd.nextInt(messages.size)]
        val yi = pick(resources.getStringArray(R.array.fortune_yi_pool), 3)
        val ji = pick(resources.getStringArray(R.array.fortune_ji_pool), 2)

        val prefs = context.getSharedPreferences("fortune_data", 0)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(16))
        }
        // 金色小字抬头
        root.addView(TextView(context).apply {
            text = getString(R.string.fortune_title)
            textSize = 12f
            letterSpacing = 0.5f
            setTextColor(accent)
        })
        // 签牌：主题色圆牌 + 金环 + 投影
        val stick = TextView(context).apply {
            text = getString(R.string.fortune_stick)
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(onPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(brandSurface)
                setStroke(dp(3), accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(136), dp(136)).apply { topMargin = dp(16) }
            elevation = dp(10).toFloat()
        }
        val levelView = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(brandContent)
            visibility = View.GONE
        }
        val rule = View(context).apply {
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(1).toFloat() }
            visibility = View.GONE
        }
        val messageView = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(textPrimary)
            setLineSpacing(dp(6).toFloat(), 1f)
            visibility = View.GONE
        }
        // 宜 / 忌：圆角色标 + 词条。标签用自适应宽度，英文 "Good for" 塞不进固定圆点
        fun adviceRow(label: String, content: String, chipColor: Int) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(onPrimary)
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(chipColor)
                }
            })
            addView(TextView(context).apply {
                text = content
                textSize = 14f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) }
            })
        }
        val adviceBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(
                adviceRow(getString(R.string.fortune_yi), yi.joinToString(" · "), brandSurface),
                LinearLayout.LayoutParams(-1, -2)
            )
            addView(
                adviceRow(
                    getString(R.string.fortune_ji),
                    ji.joinToString(" · "),
                    ContextCompat.getColor(context, R.color.danger)
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            )
        }
        val action = com.google.android.material.button.MaterialButton(context).apply {
            text = getString(R.string.fortune_draw)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) }
        }
        root.addView(stick)
        root.addView(levelView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(rule, LinearLayout.LayoutParams(dp(56), dp(2)).apply { topMargin = dp(12) })
        root.addView(messageView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(adviceBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(action)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setView(root).create()

        fun showLevel() {
            levelView.text = fortuneName
            levelView.visibility = View.VISIBLE
            rule.visibility = View.VISIBLE
        }
        fun showMessage() {
            messageView.text = message
            messageView.visibility = View.VISIBLE
            adviceBox.visibility = View.VISIBLE
        }
        var state = 0
        // 今天已经抽过：按日期播种本来也抽不出别的结果，这里只把「已祈福」如实显示出来，
        // 顺带堵掉反复点「祈福」把累计次数刷上去
        if (prefs.getString("date", "") == today) {
            state = 2
            stick.text = fortuneName
            stick.textSize = 18f
            showLevel()
            showMessage()
            action.text = getString(R.string.fortune_blessed)
            action.isEnabled = false
        }
        action.setOnClickListener {
            when (state) {
                0 -> {
                    state = 1
                    action.isEnabled = false
                    // 摇签：左右小幅摆几下，再整支翻面揭晓
                    val shake = floatArrayOf(-7f, 7f, -5f, 5f, 0f)
                    var step = 0
                    fun shakeNext() {
                        if (step < shake.size) {
                            stick.animate().rotation(shake[step++]).setDuration(70)
                                .withEndAction { shakeNext() }.start()
                        } else {
                            stick.animate().rotationY(180f).setDuration(300).withEndAction {
                                stick.text = fortuneName
                                stick.textSize = 18f
                                stick.animate().rotationY(360f).setDuration(260).withEndAction {
                                    stick.rotationY = 0f
                                    showLevel()
                                    action.text = getString(R.string.fortune_interpret)
                                    action.isEnabled = true
                                }.start()
                            }.start()
                        }
                    }
                    shakeNext()
                }
                1 -> {
                    state = 2
                    showMessage()
                    action.text = getString(R.string.fortune_bless)
                }
                else -> {
                    prefs.edit()
                        .putString("date", today)
                        .putString("fortune", fortuneName)
                        .putString("meaning", message)
                        .putInt("bless_count", prefs.getInt("bless_count", 0) + 1)
                        .apply()
                    action.text = getString(R.string.fortune_blessed)
                    action.isEnabled = false
                    Toast.makeText(context, R.string.fortune_bless_done, Toast.LENGTH_SHORT).show()
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
                            taskViewModel.pendingTasks.collect {
                                renderCurrentList()
                                // 任务增删 / 勾选后趋势线必须跟着重算。
                                // 只靠切周和 onResume 触发的话，删掉已完成任务后曲线还挂着旧数据 ——
                                // 看起来就像「删了还在」。（周视图圆环走 renderCurrentList，一直是实时更新的。）
                                loadWeekTrend()
                            }
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
                                Toast.makeText(requireContext(), getString(R.string.save_failed, msg), Toast.LENGTH_LONG).show()
                                taskViewModel.clearError()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除的统一入口：普通任务直接删，重复任务必须先问清范围。
     *
     * 左滑动作用和编辑弹窗的删除按钮都走这里 —— 判断只写一份，
     * 免得哪个入口漏了，把「删今天这条」变成「删掉整个系列」。
     */
    private fun deleteTaskWithConfirm(task: Task) {
        if (task.repeatRule == TaskViewModel.REPEAT_NONE && task.templateId == 0) {
            taskViewModel.deleteTask(task)
            toastMovedToRecycleBin()
        } else {
            showRepeatDeleteDialog(task)
        }
    }

    /**
     * 左滑动作用的「上移一行 / 下移一行」（delta = -1 / +1）。
     *
     * 只在同一完成态区域内交换，和拖拽排序的规则一致，不跨「未完成 / 已完成」两段。
     *
     * 写回时只把 `templateId == 0` 的真实行交给 reorderTasks：列表里的重复任务行是
     * 「模板行的当天副本」（id 就是模板 id、dueDate 被改成了当天），把这种对象整行写回
     * 会顺手把模板的起始日期也改掉 —— 而 weekly / monthly 的判断锚点正是它。
     * 所以这里按系列 id 映射回真实模板行再重排。
     */
    private fun moveTaskRow(task: Task, delta: Int) {
        val shown = taskAdapter.currentList
        val from = shown.indexOfFirst { it.id == task.id }
        val to = from + delta
        if (from < 0 || to !in shown.indices) return
        if (shown[from].isCompleted != shown[to].isCompleted) return

        val realRows = taskViewModel.pendingTasks.value ?: return
        val templates = realRows.filter { it.templateId == 0 }.associateBy { it.id }
        fun seriesIdOf(t: Task) = if (t.templateId != 0) t.templateId else t.id
        val ordered = shown.mapNotNull { templates[seriesIdOf(it)] }
            .distinctBy { it.id }
            .toMutableList()
        val i = ordered.indexOfFirst { it.id == seriesIdOf(shown[from]) }
        val j = ordered.indexOfFirst { it.id == seriesIdOf(shown[to]) }
        if (i < 0 || j < 0) return
        ordered.add(j, ordered.removeAt(i))
        taskViewModel.reorderTasks(ordered)
    }

    /**
     * 重复任务的删除范围询问。
     *
     * 列表里的重复任务有两种形态，必须都归到同一个系列 id 上：
     * - 未完成：模板行的当天副本（templateId == 0，id 就是模板 id）
     * - 已完成：当天快照行（templateId == 模板 id）
     * 所以系列 id 取 templateId，只有它为 0 时才用 task.id。
     */
    internal fun showRepeatDeleteDialog(task: Task) {
        val templateId = if (task.templateId != 0) task.templateId else task.id
        val labels = arrayOf(
            getString(R.string.repeat_delete_once),
            getString(R.string.repeat_delete_all),
            getString(R.string.repeat_delete_all_keep_done),
            getString(R.string.cancel)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.repeat_delete_title, task.title))
            .setItems(labels) { _, which ->
                when (which) {
                    // 只让这一天不出现：别的日期和系列本身都不动。
                    // 日期用 task.dueDate —— 两种形态下它都等于当天（虚拟实例和快照都写在当天）
                    0 -> {
                        val day = task.dueDate ?: selectedDate
                        if (day != null) {
                            TaskSkipStore.skip(requireContext(), templateId, day)
                            renderCurrentList()
                        }
                    }
                    1 -> {
                        taskViewModel.deleteTaskSeries(templateId)
                        toastMovedToRecycleBin()
                    }
                    2 -> {
                        taskViewModel.deleteTaskSeriesKeepCompleted(templateId)
                        toastMovedToRecycleBin()
                    }
                    else -> Unit
                }
            }
            .show()
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
        val skipped = TaskSkipStore.all(requireContext())
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
                // 「仅删除该事件」记过这天：模板带出的实例、以及当天的完成快照，一并都不出现
                if (TaskSkipStore.key(task.id, day) in skipped) continue
                val snapshot = daySnapshots[task.id]
                if (snapshot != null) result.add(snapshot)
                // 没有当天快照 = 当天没完成，一律显示未完成。
                // 这里必须显式写 isCompleted = false：直接 task.copy(dueDate = day) 会继承
                // 模板自身的 isCompleted，而模板可能残留 true（把已完成的普通任务改成「每日」
                // 就会这样），结果是后面每一天都显示已完成划线，而且取消不掉 —— 删掉当天快照后
                // 拿出来的虚拟实例还是继承着那个 true。
                else result.add(task.copy(dueDate = day, isCompleted = false))
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

    /**
     * 判定委托给 [RepeatRule] —— 统计侧（FocusRepository）必须问同一个问题。
     * 两边各写一份的话，一旦漂移就会出现「清单里看不见、趋势线上却计了一笔」。
     */
    private fun matchesRepeat(rule: String, start: Long, day: Long): Boolean =
        RepeatRule.covers(rule, start, day)
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


    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        internal const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        /** 单个任务允许的预计番茄钟数量上限，防止手滑输入离谱数值。 */
        internal const val MAX_ESTIMATED_POMODOROS = 99
    }
}
