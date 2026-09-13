package com.xian.focus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.CalendarContract
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.xian.focus.data.Task
import com.xian.focus.databinding.DialogAddStudyTaskBinding
import com.xian.focus.databinding.ItemSubtaskEditBinding
import java.util.Calendar
import java.util.Date

/**
 * 任务表单的辅助构造与任务新增 / 编辑弹窗。
 *
 * 两个弹窗共用同一张布局（DialogAddStudyTaskBinding），字段与交互基本一致，
 * 差别只在「已有数据回填」和「多一个删除按钮」，所以放在一起维护。
 * 原先它们挤在 TasksFragment 里，把那个文件撑到 1400 行以上；
 * 搬出来后 TasksFragment 只管列表、日历和抽屉。
 *
 * 这些都是 TasksFragment 的扩展函数 —— 表单要读写 Fragment 的当前浏览日期、
 * 图片选择器、ViewModel 等状态，用扩展函数比传一堆参数清楚。
 */
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
internal fun TasksFragment.addEstimateRow(
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
            text = getString(R.string.estimated_pomodoros_label)
            textSize = 12f
            setTextColor(requireContext().themedColor(R.attr.colorBrandContent, R.color.theme_qinglv_primary_content))
        })
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initial.coerceIn(1, TasksFragment.MAX_ESTIMATED_POMODOROS).toString())
            setSelection(text.length)
            hint = "1"
            textSize = 14f
        }
        row.addView(input)
        container.addView(row, 0)
        return input
    }

internal fun TasksFragment.estimatedFrom(input: android.widget.EditText): Int =
        (input.text?.toString()?.toIntOrNull() ?: 1).coerceIn(1, TasksFragment.MAX_ESTIMATED_POMODOROS)

    /**
     * 分类 / 日期筛选胶囊的统一外观。
     * 旧实现写死了青色 + 浅灰，既不跟四套主题、也不跟深色模式，
     * 深色下会变成浅灰底上的浅灰字。
     */
internal fun TasksFragment.paintChipView(view: TextView, selected: Boolean, cornerRadiusPx: Float) {
        val context = requireContext()
        view.setTextColor(
            if (selected) ContextCompat.getColor(context, R.color.on_primary)
            else ContextCompat.getColor(context, R.color.text_secondary)
        )
        view.background = GradientDrawable().apply {
            cornerRadius = cornerRadiusPx
            setColor(
                if (selected) context.themedColor(R.attr.colorBrandSurface, R.color.theme_qinglv_primary)
                else ContextCompat.getColor(context, R.color.chip_unselected_bg)
            )
        }
    }

internal fun TasksFragment.showAddTaskDialog() {
        val dialogBinding = DialogAddStudyTaskBinding.inflate(layoutInflater)
        val form = dialogBinding.root.getChildAt(0) as? android.widget.LinearLayout
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val categoryRow = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        var selectedCategory = getString(R.string.category_life)
        val categoryChips = mutableListOf<android.widget.TextView>()
        fun paintChip(view: android.widget.TextView, selected: Boolean) = paintChipView(view, selected, dp(22).toFloat())
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
        addCategoryChip(getString(R.string.category_life)); addCategoryChip(getString(R.string.category_work))
        dialogBinding.subjectInput.setText(getString(R.string.category_life))
        val addCategoryChip = android.widget.TextView(requireContext()).apply { text = "+"; textSize = 16f; gravity = android.view.Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); paintChip(this, false) }
        addCategoryChip.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = getString(R.string.category_custom_hint) }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.category_add).setView(input).setPositiveButton(R.string.save) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { name -> addCategoryChip(name); selectCategory(name, categoryChips.last()); GroupColorStore.setColor(requireContext(), name, GroupColorStore.palette.first()) }
            }.setNegativeButton(R.string.cancel, null).show()
        }
        categoryRow.addView(addCategoryChip, android.widget.LinearLayout.LayoutParams(-2, -2))
        form?.addView(categoryRow, 0)
        val dateRow = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        // 默认跟着当前浏览的日期预选：今天→「今天」，明天→「明天」，其他日期→「选择日期」并显示该日期
        val browseDay = selectedDate ?: startOfToday()
        val defaultDateIndex = when (browseDay) {
            startOfToday() -> 0
            startOfToday() + TasksFragment.DAY_MILLIS -> 1
            else -> 2
        }
        var selectedQuickDate: Long? = if (defaultDateIndex == 0) startOfToday() else browseDay
        val dateChips = mutableListOf<android.widget.TextView>()
        fun selectDateChip(clicked: android.widget.TextView) {
            dateChips.forEach { paintChip(it, it === clicked) }
            clicked.animate().cancel(); clicked.scaleX = 0.9f; clicked.scaleY = 0.9f
            clicked.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        listOf(
            R.string.due_today, R.string.due_tomorrow, R.string.due_pick_date, R.string.due_none
        ).forEachIndexed { index, labelRes ->
            val chip = android.widget.TextView(requireContext()).apply {
                text = if (index == 2 && defaultDateIndex == 2) dateFormat.format(Date(browseDay)) else getString(labelRes)
                textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
            }
            paintChip(chip, index == defaultDateIndex)
            chip.setOnClickListener { selectedQuickDate = when (index) { 0 -> startOfToday(); 1 -> startOfToday() + TasksFragment.DAY_MILLIS; 2 -> { val init = Calendar.getInstance().apply { timeInMillis = selectedQuickDate ?: browseDay }; DatePickerDialog(requireContext(), { _, y, m, d -> selectedQuickDate = Calendar.getInstance().apply { set(y,m,d,0,0,0); set(Calendar.MILLISECOND,0) }.timeInMillis; chip.text = dateFormat.format(Date(selectedQuickDate!!)); selectDateChip(chip) }, init.get(Calendar.YEAR), init.get(Calendar.MONTH), init.get(Calendar.DAY_OF_MONTH)).show(); selectedQuickDate }; else -> null }; if (index != 2) selectDateChip(chip) }
            dateChips += chip
            dateRow.addView(chip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val moreIndex = form?.indexOfChild(dialogBinding.moreOptionsButton) ?: -1
        if (moreIndex >= 0) form?.addView(dateRow, moreIndex)
        dialogBinding.moreOptionsButton.setOnClickListener {
            val expanded = dialogBinding.moreOptionsContainer.visibility == View.VISIBLE
            dialogBinding.moreOptionsContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            dialogBinding.moreOptionsButton.text = getString(
                if (expanded) R.string.task_expand_more else R.string.task_collapse_more
            )
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

internal fun TasksFragment.showEditTaskDialog(task: Task) {
        showEditTaskDialogCompat(task)
    }

internal fun TasksFragment.showEditTaskDialogCompat(task: Task) {
        val dialogBinding = DialogAddStudyTaskBinding.inflate(layoutInflater)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun createChip(text: String, selected: Boolean) = android.widget.TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            paintChipView(this, selected, dp(22).toFloat())
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
            paintChipView(chip, selected, dp(22).toFloat())
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
        val categoryLife = getString(R.string.category_life)
        val categoryWork = getString(R.string.category_work)
        addCategoryChip(categoryLife)
        addCategoryChip(categoryWork)
        if (selectedCategory !in listOf(categoryLife, categoryWork, "", TaskViewModel.DEFAULT_GROUP)) {
            addCategoryChip(selectedCategory)
        }
        val addCategory = createChip("+", false)
        addCategory.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = getString(R.string.category_custom_hint) }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.category_add)
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
                1 -> selectedDueDate == startOfToday() + TasksFragment.DAY_MILLIS
                3 -> selectedDueDate == null
                else -> selectedDueDate != null && selectedDueDate != startOfToday() && selectedDueDate != startOfToday() + TasksFragment.DAY_MILLIS
            }
            chip.setTextColor(
                if (isSelected) ContextCompat.getColor(requireContext(), R.color.on_primary)
                else ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(
                    if (isSelected) requireContext().themedColor(R.attr.colorBrandSurface, R.color.theme_qinglv_primary)
                    else ContextCompat.getColor(requireContext(), R.color.chip_unselected_bg)
                )
            }
        }
        listOf(
            R.string.due_today, R.string.due_tomorrow, R.string.due_pick_date, R.string.due_none
        ).forEachIndexed { index, labelRes ->
            val chip = createChip(getString(labelRes), false)
            chip.setOnClickListener {
                when (index) {
                    0 -> selectedDueDate = startOfToday()
                    1 -> selectedDueDate = startOfToday() + TasksFragment.DAY_MILLIS
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
            text = getString(R.string.task_delete)
            textSize = 13f
            isAllCaps = false
            minHeight = dp(40)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.delete_red))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_task, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.task_delete)
                    .setMessage(R.string.task_delete_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
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
            dialogBinding.moreOptionsButton.text = getString(
                if (expanded) R.string.task_expand_more else R.string.task_collapse_more
            )
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
            .setTitle(R.string.dialog_edit_task)
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

internal fun TasksFragment.launchCalendarIntent(
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
