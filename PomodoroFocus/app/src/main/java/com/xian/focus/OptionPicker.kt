package com.xian.focus

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 二级页的选项选择器：事件设置里的「提前提醒天数」「事件排序方式」共用一套。
 *
 * 比 `setSingleChoiceItems` 多的东西只有一个目的 —— 让选项之间的差别看得出来：
 * 每行是一张圆角卡片、选中行整行高亮、还能带一句说明。点一行直接生效并关窗
 * （每个选项都只对应一个字段，再让用户点一次「确定」是多余的）。
 */
object OptionPicker {

    data class Item(val label: String, val desc: String? = null)

    fun show(
        context: Context,
        titleRes: Int,
        options: List<Item>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.dialog_option_picker, null, false)
        val rows = root.findViewById<LinearLayout>(R.id.optionRows)
        val inflater = LayoutInflater.from(context)
        options.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_option_row, rows, false)
            val selected = index == selectedIndex
            row.findViewById<TextView>(R.id.optionLabel).text = item.label
            val desc = row.findViewById<TextView>(R.id.optionDesc)
            if (item.desc.isNullOrEmpty()) {
                desc.visibility = View.GONE
            } else {
                desc.text = item.desc
                desc.visibility = View.VISIBLE
            }
            row.findViewById<RadioButton>(R.id.optionRadio).isChecked = selected
            row.setBackgroundResource(
                if (selected) R.drawable.bg_option_row_selected else R.drawable.bg_option_row
            )
            rows.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        options.indices.forEach { index ->
            rows.getChildAt(index).setOnClickListener {
                onPick(index)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
