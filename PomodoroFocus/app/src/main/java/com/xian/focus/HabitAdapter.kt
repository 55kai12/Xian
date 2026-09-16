package com.xian.focus

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.data.Habit
import com.xian.focus.databinding.ItemHabitBinding

/**
 * 列表里的一行：习惯 + 它此刻的状态。
 *
 * [scheduledToday] 单独拎出来是因为「今天不用打卡」和「今天还没打」在界面上要长得不一样 ——
 * 前者圆钮该是灰的、点了也没用，后者才是等着被点的。
 */
data class HabitRow(
    val habit: Habit,
    val status: HabitStreak.Status,
    val scheduledToday: Boolean
)

/**
 * 习惯列表。三个动作分得很清楚：
 * - 点圆钮 = 打卡 +1；长按圆钮 = 撤销今天（弹确认）；
 * - 点整行 = 编辑（本期不做详情页，编辑对话框就是全部信息）。
 */
class HabitAdapter(
    private val onPunch: (HabitRow) -> Unit,
    private val onUndo: (HabitRow) -> Unit,
    private val onEdit: (HabitRow) -> Unit
) : ListAdapter<HabitRow, HabitAdapter.HabitViewHolder>(HabitDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HabitViewHolder =
        HabitViewHolder(ItemHabitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: HabitViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HabitViewHolder(
        private val binding: ItemHabitBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: HabitRow) {
            val context = binding.root.context
            val habit = row.habit
            val status = row.status

            binding.habitNameText.text = habit.name
            binding.habitColorBar.setBackgroundColor(habit.color)
            binding.habitSubText.text = subtitle(context, row)

            // 圆钮上的字：达标打勾、目标为 1 显示空圈、计数型显示 3/8
            binding.habitPunchButton.text = when {
                !row.scheduledToday -> "—"
                status.done -> "✓"
                status.target == 1 -> "○"
                else -> context.getString(R.string.habit_count_format, status.todayCount, status.target)
            }
            binding.habitPunchButton.setBackgroundResource(
                if (row.scheduledToday && status.done) R.drawable.bg_habit_punch_done else R.drawable.bg_habit_punch
            )
            // 实心点缀色底上用固定白字，其余跟着主题色走 —— 这样深浅两套主题都不用另配颜色
            binding.habitPunchButton.setTextColor(
                if (row.scheduledToday && status.done) Color.WHITE else attrColor(context, R.attr.colorBrandContent)
            )

            binding.habitPunchButton.setOnClickListener { if (row.scheduledToday) onPunch(row) }
            binding.habitPunchButton.setOnLongClickListener {
                if (row.scheduledToday && status.todayCount > 0) {
                    onUndo(row)
                }
                true
            }
            binding.root.setOnClickListener { onEdit(row) }
        }

        /** 副标题：今天不用打卡 > 每周类看本周进度 > 每天类看连续天数（没有就看本周）。 */
        private fun subtitle(context: Context, row: HabitRow): String = when {
            !row.scheduledToday -> context.getString(R.string.habit_rest_today)
            row.habit.freqType != Habit.FREQ_DAILY ->
                context.getString(R.string.habit_week_progress, row.status.weekDone, row.status.weekTarget)
            row.status.streak > 0 -> context.getString(R.string.habit_streak, row.status.streak)
            else -> context.getString(R.string.habit_week_progress, row.status.weekDone, row.status.weekTarget)
        }
    }

    private object HabitDiff : DiffUtil.ItemCallback<HabitRow>() {
        override fun areItemsTheSame(oldItem: HabitRow, newItem: HabitRow): Boolean =
            oldItem.habit.id == newItem.habit.id

        override fun areContentsTheSame(oldItem: HabitRow, newItem: HabitRow): Boolean =
            oldItem == newItem
    }

    private companion object {
        /** 自定义主题属性取色（colorBrandContent 这类）。 */
        fun attrColor(context: Context, attrRes: Int): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(attrRes, value, true)
            return value.data
        }
    }
}
