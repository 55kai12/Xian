package com.xian.focus

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WhitelistAppPicker {
    data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

    fun show(
        context: Context,
        lifecycleScope: CoroutineScope,
        selected: Set<String>,
        titleRes: Int = R.string.whitelist_apps,
        onResult: (Set<String>) -> Unit
    ) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps(context) }
            val checked = apps.map { selected.contains(it.packageName) }.toBooleanArray()
            val listView = ListView(context).apply {
                adapter = AppAdapter(context, apps, checked)
                dividerHeight = 0
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(listView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val result = apps.filterIndexed { index, _ -> checked[index] }
                        .map { it.packageName }
                        .toSet()
                    onResult(result)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun loadApps(context: Context): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        return pm.queryIntentActivities(intent, 0)
            .map {
                AppInfo(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    private class AppAdapter(
        context: Context,
        private val apps: List<AppInfo>,
        private val checked: BooleanArray
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_picker, parent, false)
            val app = apps[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.appLabel).text = app.label
            val checkBox = view.findViewById<CheckBox>(R.id.appCheck)
            checkBox.isChecked = checked[position]
            view.setOnClickListener {
                checked[position] = !checked[position]
                checkBox.isChecked = checked[position]
            }
            return view
        }
    }
}
