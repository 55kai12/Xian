package com.xian.focus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.databinding.ItemAppGridBinding

/**
 * 应用图标网格的适配器。
 *
 * 白名单二级页与「选应用」对话框共用 —— 两边选的是同一份应用列表，
 * 没有理由各写一套（选中态的表达、描边、角标都是同一套）。
 *
 * 选中态靠两处表达：图标外圈 2dp 点缀色描边 + 右上角勾选角标。
 * 名字颜色不参与表达：`colorOnSurfaceVariant` 属 appcompat，本模块非传递 R 类，
 * 代码里取不到它的 id，硬写一个色值又会跟深色模式打架。
 */
class AppGridAdapter(
    private val apps: List<WhitelistAppPicker.AppInfo>,
    initialSelected: Set<String>,
    private val onSelectionChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<AppGridAdapter.Holder>() {

    private val checked = BooleanArray(apps.size) {
        initialSelected.contains(apps[it].packageName)
    }

    val selectedCount: Int get() = checked.count { it }

    fun selectedPackages(): Set<String> =
        apps.filterIndexed { index, _ -> checked[index] }.map { it.packageName }.toSet()

    /**
     * 外部把选中态改成给定集合。
     *
     * 专给「闸门没放行」用：白名单二级页是点一下立刻落盘，被 [WhitelistGate] 拦下时
     * 得把这一下退回去 —— 否则界面上看着勾上了、其实没存，比直接不改更糟。
     */
    fun setSelection(packages: Set<String>) {
        apps.forEachIndexed { index, app -> checked[index] = packages.contains(app.packageName) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemAppGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    override fun getItemCount(): Int = apps.size

    inner class Holder(private val binding: ItemAppGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val accentColor =
            binding.root.context.themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)

        /** 描边是 px 单位，缓存好省得每次绑定都算一遍。 */
        private val borderWidthPx =
            (binding.root.resources.displayMetrics.density * 2f).toInt()

        fun bind(position: Int) {
            val app = apps[position]
            val on = checked[position]
            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.setBorderWidth(if (on) borderWidthPx else 0)
            binding.appIcon.setBorderColor(accentColor)
            binding.appCheckBadge.visibility = if (on) View.VISIBLE else View.GONE
            binding.appLabel.text = app.label
            binding.root.setOnClickListener {
                // 不拿 bind 时捕获的 on：连点两下时它可能是上一次的旧值，重绑是异步的
                checked[position] = !checked[position]
                notifyItemChanged(position)
                onSelectionChanged(selectedPackages())
            }
        }
    }

    companion object {
        /** 一行四个：图标 52dp + 名字，四列在常见屏宽下刚好不挤。 */
        const val SPAN = 4
    }
}
