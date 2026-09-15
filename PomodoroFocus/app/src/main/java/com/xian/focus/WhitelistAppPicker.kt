package com.xian.focus

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

object WhitelistAppPicker {
    data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

    /**
     * 应用名排序规则：中文按拼音、英文按字母。
     *
     * 直接 `sortedBy { label }` 走的是 String 的码位比较，中文部分看起来就是乱的
     * （「支付宝」排在「微信」前面，因为「支」的码位更小）。Collator(Locale.CHINA)
     * 是标准库里现成的拼音排序，不用引第三方拼音库。白名单列表（锁机页 / 覆盖层）
     * 和应用限额列表都共用这一份规则，三处顺序才一致。
     */
    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    val LABEL_ORDER: Comparator<String> = Comparator { a, b -> collator.compare(a, b) }

    fun show(
        context: Context,
        lifecycleScope: CoroutineScope,
        selected: Set<String>,
        titleRes: Int = R.string.whitelist_apps,
        onResult: (Set<String>) -> Unit
    ) {
        lifecycleScope.launch {
            val apps = loadApps(context)
            val content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_app_grid, null, false)
            val countText = content.findViewById<TextView>(R.id.gridCountText)
            val grid = content.findViewById<RecyclerView>(R.id.gridAppList)
            val adapter = AppGridAdapter(apps, selected) { picked ->
                countText.text = context.getString(R.string.whitelist_selected_count, picked.size)
            }
            countText.text = context.getString(R.string.whitelist_selected_count, adapter.selectedCount)
            grid.layoutManager = GridLayoutManager(context, AppGridAdapter.SPAN)
            grid.adapter = adapter
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(R.string.save) { _, _ ->
                    onResult(adapter.selectedPackages())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 应用列表（带缓存）。
     *
     * `queryIntentActivities` + 每个应用的 `loadLabel` / `loadIcon` 在几百个应用的机器上
     * 要跑几百毫秒，而对话框是「加载完才弹」的 —— 第一次点「白名单」会有一下明显的空档，
     * 用户体感就是「不流畅」。缓存住之后第二次起是秒开；60 秒的短有效期只是为了防止
     * 刚装的新应用一直不出现（不值得为它挂一个安装广播接收器）。
     *
     * 白名单二级页（[WhitelistFragment]）跟这里的选择器共用同一份缓存 —— 从二级页出来
     * 再点别的入口选应用，不必重扫一遍。
     */
    @Volatile
    private var cachedApps: List<AppInfo>? = null

    @Volatile
    private var cachedAt = 0L

    private const val CACHE_TTL_MILLIS = 60_000L

    internal suspend fun loadApps(context: Context): List<AppInfo> {
        val cached = cachedApps
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return cached
        }
        val apps = withContext(Dispatchers.IO) { queryApps(context) }
        cachedApps = apps
        cachedAt = System.currentTimeMillis()
        return apps
    }

    private fun queryApps(context: Context): List<AppInfo> {
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
            .sortedWith(compareBy(LABEL_ORDER) { it.label })
    }
}
