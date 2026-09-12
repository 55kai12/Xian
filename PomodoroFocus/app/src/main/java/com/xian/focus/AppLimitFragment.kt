package com.xian.focus

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xian.focus.databinding.FragmentAppLimitBinding

/**
 * 应用限额：给应用设每天能玩多久，用完当天锁死，次日自动恢复。
 * 入口在任务清单左上角抽屉里。
 */
class AppLimitFragment : Fragment() {

    private var _binding: FragmentAppLimitBinding? = null
    private val binding get() = _binding!!

    private data class Row(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val limitMinutes: Int,
        val usedMinutes: Long,
        val locked: Boolean
    )

    private var rows: List<Row> = emptyList()
    private lateinit var adapter: RowAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppLimitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = RowAdapter(LayoutInflater.from(requireContext()))
        binding.limitAppList.adapter = adapter
        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.addLimitAppButton.setOnClickListener { pickApps() }
        binding.limitAppList.setOnItemClickListener { _, _, position, _ ->
            showLimitDialog(rows[position])
        }
        binding.accessibilityHintCard.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val context = requireContext()
        val pm = context.packageManager
        rows = AppLimitStore.limitedPackages(context).map { packageName ->
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            Row(
                packageName = packageName,
                label = info?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
                icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                limitMinutes = AppLimitStore.limitMinutes(context, packageName),
                usedMinutes = AppLimitStore.usedSeconds(context, packageName) / 60L,
                locked = AppLimitStore.isLocked(context, packageName)
            )
        }.sortedBy { it.label }
        adapter.notifyDataSetChanged()
        binding.emptyLimitText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.accessibilityHintCard.visibility =
            if (LockHealth.isAccessibilityOn(context)) View.GONE else View.VISIBLE
    }

    /** 复用锁机的应用选择器；新勾上的先给默认额度，再点进列表逐个调。 */
    private fun pickApps() {
        val context = requireContext()
        val current = AppLimitStore.limitedPackages(context).toSet()
        WhitelistAppPicker.show(
            context,
            viewLifecycleOwner.lifecycleScope,
            current,
            R.string.app_limit_picker_title
        ) { selected ->
            val added = selected - current
            added.forEach { AppLimitStore.setLimit(context, it, DEFAULT_LIMIT_MINUTES) }
            // 在选择器里取消勾选 = 取消该应用的限额
            (current - selected).forEach { AppLimitStore.setLimit(context, it, 0) }
            refresh()
            if (added.isNotEmpty()) {
                Toast.makeText(
                    context,
                    getString(R.string.app_limit_added_toast, added.size, DEFAULT_LIMIT_MINUTES),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLimitDialog(row: Row) {
        val presets = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = presets.map { getString(R.string.app_limit_minutes_format, it) } +
            getString(R.string.app_limit_unlimited)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_limit_dialog_title, row.label))
            .setItems(labels.toTypedArray()) { _, which ->
                val minutes = if (which < presets.size) presets[which] else 0
                AppLimitStore.setLimit(requireContext(), row.packageName, minutes)
                refresh()
            }
            .show()
    }

    private inner class RowAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_limit, parent, false)
            val row = rows[position]
            view.findViewById<ImageView>(R.id.limitAppIcon).setImageDrawable(row.icon)
            view.findViewById<TextView>(R.id.limitAppLabel).text = row.label
            view.findViewById<TextView>(R.id.limitAppDetail).text = if (row.locked) {
                getString(R.string.app_limit_row_locked)
            } else {
                getString(R.string.app_limit_row_detail, row.usedMinutes, row.limitMinutes)
            }
            view.findViewById<TextView>(R.id.limitAppLimit).text =
                getString(R.string.app_limit_minutes_format, row.limitMinutes)
            return view
        }
    }

    companion object {
        private const val DEFAULT_LIMIT_MINUTES = 30
    }
}
