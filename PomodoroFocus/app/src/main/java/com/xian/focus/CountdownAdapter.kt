package com.xian.focus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.data.Countdown
import com.xian.focus.databinding.ItemCountdownBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CountdownAdapter(
    private val viewModel: CountdownViewModel,
    private val onItemClick: (Countdown) -> Unit,
    private val onItemLongClick: (Countdown) -> Unit
) : ListAdapter<Countdown, CountdownAdapter.CountdownViewHolder>(CountdownDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountdownViewHolder {
        val binding = ItemCountdownBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CountdownViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountdownViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CountdownViewHolder(
        private val binding: ItemCountdownBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(countdown: Countdown) {
            val context = binding.root.context
            // 日期格式随语言走，所以格式化器在这里按 Context 建（列表很短，开销可忽略）。
            val dateFormat = SimpleDateFormat(
                context.getString(R.string.countdown_date_format),
                Locale.getDefault()
            )
            binding.countdownTitleText.text = countdown.title
            binding.countdownColorBar.setBackgroundColor(countdown.color)

            // 序号和备注默认隐藏（倒数日不使用事件设置）
            binding.countdownOrderText.visibility = android.view.View.GONE
            binding.countdownNoteText.visibility = android.view.View.GONE

            val effectiveDate = viewModel.getEffectiveTargetDate(countdown)
            binding.countdownDateText.text = dateFormat.format(Date(effectiveDate)) +
                    if (countdown.repeatYearly) context.getString(R.string.countdown_yearly_suffix) else ""

            val days = viewModel.getDaysRemaining(countdown)
            if (days >= 0) {
                binding.countdownDaysText.text = days.toString()
                binding.countdownDaysLabel.text = context.getString(R.string.countdown_days_left)
                binding.countdownDaysText.setTextColor(countdown.color)
            } else {
                binding.countdownDaysText.text = (-days).toString()
                binding.countdownDaysLabel.text = context.getString(R.string.countdown_days_past)
                binding.countdownDaysText.setTextColor(
                    ContextCompat.getColor(context, R.color.text_tertiary)
                )
            }

            binding.root.setOnClickListener { onItemClick(countdown) }
            binding.root.setOnLongClickListener {
                onItemLongClick(countdown)
                true
            }
        }
    }

    private object CountdownDiffCallback : DiffUtil.ItemCallback<Countdown>() {
        override fun areItemsTheSame(oldItem: Countdown, newItem: Countdown): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Countdown, newItem: Countdown): Boolean =
            oldItem == newItem
    }
}
