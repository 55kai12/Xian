package com.xian.focus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.data.FocusRepository
import com.xian.focus.databinding.ItemRecycleBinBinding

/** 回收站里的一条。任务（按重复系列合并）、倒数日、便贴在这里统一成同一个形状。 */
data class TrashItem(
    val kind: Kind,
    /** 任务 = 系列 id（模板 id）；倒数日 = 倒数日 id；便贴 = 便签的 createdAt。 */
    val id: Long,
    val title: String,
    val deletedAt: Long
) {
    enum class Kind { TASK, COUNTDOWN, NOTE, HABIT }
}

class RecycleBinAdapter(
    private val onRestore: (TrashItem) -> Unit,
    private val onPurge: (TrashItem) -> Unit
) : ListAdapter<TrashItem, RecycleBinAdapter.TrashViewHolder>(TrashDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder =
        TrashViewHolder(ItemRecycleBinBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TrashViewHolder(
        private val binding: ItemRecycleBinBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TrashItem) {
            val context = binding.root.context
            binding.recycleTitleText.text = item.title
            binding.recycleSubtitleText.text = context.getString(
                R.string.recycle_bin_item_subtitle,
                context.getString(kindLabelRes(item.kind)),
                remainingDays(item.deletedAt)
            )
            binding.recycleRestoreButton.setOnClickListener { onRestore(item) }
            binding.recyclePurgeButton.setOnClickListener { onPurge(item) }
        }
    }

    private fun kindLabelRes(kind: TrashItem.Kind): Int = when (kind) {
        TrashItem.Kind.TASK -> R.string.recycle_bin_kind_task
        TrashItem.Kind.COUNTDOWN -> R.string.recycle_bin_kind_countdown
        TrashItem.Kind.NOTE -> R.string.recycle_bin_kind_note
        TrashItem.Kind.HABIT -> R.string.recycle_bin_kind_habit
    }

    private object TrashDiff : DiffUtil.ItemCallback<TrashItem>() {
        override fun areItemsTheSame(oldItem: TrashItem, newItem: TrashItem): Boolean =
            oldItem.kind == newItem.kind && oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TrashItem, newItem: TrashItem): Boolean =
            oldItem == newItem
    }

    companion object {
        /** 距离被真删还剩几天（保留期内）。刚删掉是 30，最后一天是 0。 */
        fun remainingDays(deletedAt: Long): Int {
            val elapsed = System.currentTimeMillis() - deletedAt
            val used = (elapsed / DAY_MILLIS).toInt()
            return (KEEP_DAYS - used).coerceAtLeast(0)
        }

        private val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private val KEEP_DAYS = (FocusRepository.TRASH_KEEP_MILLIS / DAY_MILLIS).toInt()
    }
}
