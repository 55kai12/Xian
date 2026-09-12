package com.xian.focus

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.data.Subtask
import com.xian.focus.data.Task
import com.xian.focus.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TaskAdapter(
    private val selectedTaskIdProvider: (() -> Int?)? = null,
    private val onStartTask: (Task) -> Unit,
    private val onToggleTask: (Task) -> Unit,
    private val onToggleSubtask: (Subtask) -> Unit,
    private val onEditTask: (Task) -> Unit,
    private val onDeleteTask: (Task) -> Unit,
    private val onImageClick: (Task) -> Unit = {}
) : ListAdapter<Task, TaskAdapter.TaskViewHolder>(TaskDiffCallback) {

    var subtaskCounts: Map<Int, Pair<Int, Int>> = emptyMap()
    var subtasksMap: Map<Int, List<Subtask>> = emptyMap()
    private val expandedTaskIds = mutableSetOf<Int>()
    private val imageExpandedTaskIds = mutableSetOf<Int>()
    private var selectedTaskId: Int? = null
    private var unfinishedNumbers: Map<Int, Int> = emptyMap()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun submitTasks(tasks: List<Task>) {
        var number = 0
        unfinishedNumbers = tasks.associate { task ->
            task.id to if (task.isCompleted) 0 else ++number
        }
        submitList(tasks)
    }

    fun setSelectedTaskId(taskId: Int?) {
        if (selectedTaskId == taskId) return
        selectedTaskId = taskId
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = getItem(position)
        holder.bind(task, unfinishedNumbers[task.id] ?: position + 1)
    }

    inner class TaskViewHolder(
        private val binding: ItemTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context get() = binding.root.context

        /** 语义色一律走 colors.xml，好让深色模式跟着翻面。 */
        private fun color(colorRes: Int) = ContextCompat.getColor(context, colorRes)

        /** 上一次绑定的完成态：用来判断该不该播"刚完成"的动画 */
        private var lastBoundCompleted: Boolean? = null
        private var strikeAnimator: android.animation.ValueAnimator? = null

        /** 打勾那一下给个回弹，让"完成了"有存在感 */
        private fun pulse(view: View) {
            view.animate().cancel()
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.6f))
                .start()
        }

        /** 划线由左向右展开，而不是啪地整条出现 */
        private fun animateStrikeWidth(targetWidth: Int) {
            strikeAnimator?.cancel()
            strikeAnimator = android.animation.ValueAnimator.ofInt(0, targetWidth).apply {
                duration = 280L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    binding.taskTitleStrikeLine.layoutParams =
                        binding.taskTitleStrikeLine.layoutParams.apply {
                            width = anim.animatedValue as Int
                        }
                }
                start()
            }
        }

        fun bind(task: Task, number: Int) {
            val isSelected = task.id == (selectedTaskIdProvider?.invoke() ?: selectedTaskId)
            binding.root.isSelected = isSelected

            val prefs = context.getSharedPreferences("event_settings", 0)
            val showOrderNumber = prefs.getBoolean("show_order_number", false)
            val showStrikethrough = prefs.getBoolean("show_strikethrough", true)
            val showNote = prefs.getBoolean("show_note", false)
            binding.taskNumberText.text = when {
                task.isCompleted -> "✓"
                showOrderNumber -> number.toString()
                else -> ""
            }
            binding.taskNumberText.setTextColor(
                if (task.isCompleted) color(R.color.on_primary) else color(R.color.text_tertiary)
            )
            binding.taskNumberText.setBackgroundResource(
                if (task.isCompleted) R.drawable.bg_task_number_completed else R.drawable.bg_task_number
            )
            binding.taskNumberText.contentDescription = context.getString(
                if (task.isCompleted) R.string.a11y_restore_task else R.string.a11y_complete_task
            )
            binding.taskNumberText.setOnClickListener { onToggleTask(task) }
            binding.taskNumberText.alpha = 1f
            binding.root.setOnClickListener { onEditTask(task) }
            binding.taskTitleText.text = task.title
            binding.taskTitleText.paint.isStrikeThruText = false

            // "刚完成"的动效只在真的从未完成变成完成时播 ——
            // ViewHolder 会被复用重绑，不判断就会在滚动时一路乱闪
            val justCompleted = lastBoundCompleted == false && task.isCompleted
            lastBoundCompleted = task.isCompleted

            val strikeVisible = task.isCompleted && showStrikethrough
            val strikeWidth = if (strikeVisible) {
                binding.taskTitleText.paint.measureText(task.title).toInt()
            } else {
                0
            }
            binding.taskTitleStrikeLine.visibility =
                if (strikeVisible) View.VISIBLE else View.GONE
            if (justCompleted && strikeVisible) {
                animateStrikeWidth(strikeWidth)
                pulse(binding.taskNumberText)
            } else {
                strikeAnimator?.cancel()
                binding.taskTitleStrikeLine.layoutParams =
                    binding.taskTitleStrikeLine.layoutParams.apply { width = strikeWidth }
            }
            binding.taskTitleText.setTextColor(
                if (task.isCompleted) color(R.color.completed_grey) else color(R.color.text_primary)
            )
            binding.taskCountText.text = "${task.completedPomodoros}/${task.estimatedPomodoros}"

            val description = if (showNote) task.description?.takeIf { it.isNotBlank() } else null
            binding.taskDescriptionText.text = description
            binding.taskDescriptionText.visibility = if (description == null) View.GONE else View.VISIBLE

            // 重复标签
            val repeatLabel = when (task.repeatRule) {
                TaskViewModel.REPEAT_DAILY -> context.getString(R.string.repeat_daily)
                TaskViewModel.REPEAT_WEEKLY -> context.getString(R.string.repeat_weekly)
                TaskViewModel.REPEAT_MONTHLY -> context.getString(R.string.repeat_monthly)
                else -> null
            }
            if (repeatLabel != null) {
                binding.taskRepeatTag.text = "↻ $repeatLabel"
                binding.taskRepeatTag.visibility = View.VISIBLE
            } else {
                binding.taskRepeatTag.visibility = View.GONE
            }

            // 分类标签
            runCatching {
                val category = task.listType?.takeIf { it.isNotBlank() && it != TaskViewModel.DEFAULT_GROUP }
                if (category != null) {
                    binding.taskCategoryTag.text = category
                    val catColor = GroupColorStore.colorFor(context, category)
                    binding.taskCategoryTag.setTextColor(catColor)
                    (binding.taskCategoryTag.background as? android.graphics.drawable.GradientDrawable)?.apply {
                        setColor(android.graphics.Color.argb(30, android.graphics.Color.red(catColor), android.graphics.Color.green(catColor), android.graphics.Color.blue(catColor)))
                        setStroke(1, catColor)
                    }
                    binding.taskCategoryTag.visibility = View.VISIBLE
                } else {
                    binding.taskCategoryTag.visibility = View.GONE
                }
            }.onFailure { binding.taskCategoryTag.visibility = View.GONE }

            bindSubtasks(task)
            bindImage(task)
            binding.taskDueText.visibility = View.GONE

            binding.timerButton.isEnabled = !task.isCompleted
            binding.timerButton.alpha = if (task.isCompleted) 0.4f else 1f
            binding.toggleTaskButton.setText(
                if (task.isCompleted) R.string.restore_task else R.string.complete_task
            )
            binding.timerButton.setOnClickListener { onStartTask(task) }
            binding.startTaskButton.setOnClickListener(null)
            binding.toggleTaskButton.setOnClickListener(null)
            binding.deleteTaskButton.setOnClickListener(null)
        }

        private fun bindSubtasks(task: Task) {
            val (total, done) = subtaskCounts[task.id] ?: (0 to 0)
            if (total <= 0) {
                binding.subtaskSection.visibility = View.GONE
                return
            }
            binding.subtaskSection.visibility = View.VISIBLE
            val isExpanded = expandedTaskIds.contains(task.id)
            binding.subtaskToggleButton.text = if (isExpanded) {
                context.getString(R.string.subtask_collapse_count, done, total)
            } else {
                context.getString(R.string.subtask_expand_count, done, total)
            }
            binding.subtaskContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE

            if (isExpanded) {
                binding.subtaskContainer.removeAllViews()
                val subtasks = subtasksMap[task.id] ?: emptyList()
                subtasks.forEachIndexed { index, st ->
                    val row = android.widget.LinearLayout(binding.root.context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    val radio = android.widget.ImageView(binding.root.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (18 * resources.displayMetrics.density).toInt(),
                            (18 * resources.displayMetrics.density).toInt()
                        )
                        setImageResource(if (st.isCompleted) android.R.drawable.radiobutton_on_background else android.R.drawable.radiobutton_off_background)
                        setColorFilter(
                            if (st.isCompleted) {
                                context.themedColor(R.attr.colorBrandContent, R.color.theme_qinglv_primary_content)
                            } else {
                                color(R.color.text_tertiary)
                            }
                        )
                        contentDescription = context.getString(
                            if (st.isCompleted) R.string.a11y_restore_subtask else R.string.a11y_complete_subtask
                        )
                        setOnClickListener { onToggleSubtask(st) }
                    }
                    val title = android.widget.TextView(binding.root.context).apply {
                        text = "${index + 1}. ${st.title}"
                        textSize = 13f
                        setTextColor(if (st.isCompleted) color(R.color.text_tertiary) else color(R.color.text_secondary))
                        paint.isStrikeThruText = st.isCompleted
                        setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    }
                    row.addView(radio)
                    row.addView(title)
                    binding.subtaskContainer.addView(row)
                }
            }

            binding.subtaskToggleButton.setOnClickListener {
                if (expandedTaskIds.contains(task.id)) {
                    expandedTaskIds.remove(task.id)
                } else {
                    expandedTaskIds.add(task.id)
                }
                notifyItemChanged(adapterPosition)
            }
        }

        private fun bindImage(task: Task) {
            val imageUri = task.imageUri?.takeIf { it.isNotBlank() }
            if (imageUri.isNullOrBlank()) {
                binding.imageSection.visibility = View.GONE
                return
            }
            binding.imageSection.visibility = View.VISIBLE
            val isExpanded = imageExpandedTaskIds.contains(task.id)
            binding.imageToggleButton.text = if (isExpanded) {
                context.getString(R.string.image_collapse)
            } else {
                context.getString(R.string.image_expand)
            }
            binding.taskImageView.visibility = if (isExpanded) View.VISIBLE else View.GONE
            if (isExpanded) {
                runCatching {
                    binding.taskImageView.setImageURI(android.net.Uri.parse(imageUri))
                }.onFailure {
                    binding.taskImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
            binding.imageToggleButton.setOnClickListener {
                if (imageExpandedTaskIds.contains(task.id)) {
                    imageExpandedTaskIds.remove(task.id)
                } else {
                    imageExpandedTaskIds.add(task.id)
                }
                notifyItemChanged(adapterPosition)
            }
            binding.taskImageView.setOnClickListener { onImageClick(task) }
        }

        private fun bindDueDate(task: Task) {
            val dueDate = task.dueDate
            if (dueDate == null) {
                binding.taskDueText.visibility = View.GONE
                return
            }
            val datePart = dateFormat.format(Date(dueDate))
            val timePart = task.dueTimeMinutes?.let {
                "%02d:%02d".format(it / 60, it % 60)
            }
            val formatted = if (timePart == null) datePart else "$datePart $timePart"
            val dueTimeMillis = dueDate + (task.dueTimeMinutes?.times(60_000L) ?: (24L * 60L * 60L * 1000L - 1L))
            val overdue = !task.isCompleted && System.currentTimeMillis() > dueTimeMillis
            binding.taskDueText.text = if (overdue) {
                binding.root.context.getString(R.string.overdue) + " · " + formatted
            } else {
                binding.root.context.getString(R.string.task_due_format, formatted)
            }
            binding.taskDueText.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (overdue) R.color.overdue_text else R.color.due_text
                )
            )
            binding.taskDueText.visibility = View.VISIBLE
        }
    }

    private object TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean =
            oldItem == newItem
    }
}
