package com.xian.focus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 自定义锁机的运行状态 + 白名单。
 *
 * 关键约定：**白名单是独立设置，不是某次锁机的附属参数**。
 * 早先 `start()` 会把白名单一起写进去，于是只有在「开始锁机」那一刻选择的白名单才会生效 ——
 * 用户改完白名单却没点开始（或只保存了定时时段），一重建视图这份选择就白选了，
 * 界面上看起来就是「白名单莫名其妙没了」。现在读写分成两件事，锁机的起止只记时刻。
 */
object LockMachineController {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_START_AT = "lock_start_at"
    private const val KEY_END_AT = "lock_end_at"
    private const val KEY_WHITELIST = "lock_whitelist"
    private const val KEY_CUSTOM_MINUTES = "lock_custom_minutes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(context: Context, durationMinutes: Int) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_START_AT, now)
            .putLong(KEY_END_AT, now + durationMinutes.toLong() * 60_000L)
            .apply()
    }

    fun stop(context: Context) {
        prefs(context).edit()
            .remove(KEY_START_AT)
            .remove(KEY_END_AT)
            .apply()
    }

    fun isActive(context: Context): Boolean =
        remainingMillis(context) > 0

    fun remainingMillis(context: Context): Long {
        val endAt = prefs(context).getLong(KEY_END_AT, 0L)
        return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 本次锁机的预计结束时刻；跨日锁机靠它才说得清「到明天几点」。 */
    fun endAt(context: Context): Long = prefs(context).getLong(KEY_END_AT, 0L)

    fun endText(context: Context): String {
        val endAt = endAt(context)
        return if (endAt <= 0L) "" else TimeLabels.relative(context, endAt)
    }

    /**
     * 本次锁机实际已锁时长：开始时刻到现在/到结束时刻中较早的一个。
     * 供 LockStats 在锁机结束（stop/到期）时、清掉 prefs 之前调用。
     */
    fun elapsedMillis(context: Context): Long {
        val prefs = prefs(context)
        val startAt = prefs.getLong(KEY_START_AT, 0L)
        if (startAt <= 0L || !isActive(context)) return 0L
        val endAt = prefs(context).getLong(KEY_END_AT, 0L)
        val now = System.currentTimeMillis()
        val effectiveEnd = if (endAt > 0L) minOf(endAt, now) else now
        return (effectiveEnd - startAt).coerceAtLeast(0L)
    }

    fun remainingText(context: Context): String {
        val totalSeconds = (remainingMillis(context) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /**
     * 白名单：用户选完立即落盘，不依附于任何一次锁机。
     * 返回副本，避免调用方拿到的可变集合被改后污染 SharedPreferences 里那份（内存里是同一实例）。
     */
    fun whitelist(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet())

    fun saveWhitelist(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_WHITELIST, HashSet(packages)).apply()
    }

    /**
     * 上次敲过的自定义分钟数（0 = 还没用过）。
     *
     * 锁机页的「自定义分钟数」和番茄钟的自定义锁机弹窗共用这一份记忆 ——
     * 两处问的是同一件事（这次想锁多久），没必要各记一份、更没必要每次重敲。
     * 它是纯输入便利，不参与任何判定，所以不进锁机状态那组 key 的读写路径。
     */
    fun customMinutes(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_MINUTES, 0)

    fun saveCustomMinutes(context: Context, minutes: Int) {
        if (minutes > 0) prefs(context).edit().putInt(KEY_CUSTOM_MINUTES, minutes).apply()
    }

    fun isAllowed(context: Context, packageName: String): Boolean =
        packageName == context.applicationContext.packageName ||
            // 系统必需组件（来电、输入法、状态栏等）永远放行，
            // 否则会盖住来电界面和输入法，导致接不了电话、白名单应用里打不了字。
            SystemAppAllowlist.isEssential(context.applicationContext, packageName) ||
            whitelist(context).contains(packageName)

    /**
     * 前台是不是系统设置。
     *
     * 存在的理由：**系统设置是锁机唯一真正的出口**。别处都拦死了 —— 通知栏里那条
     * 「停止锁机」按钮在 v2.0.79 删了、锁机期间的通知本身也不可见 —— 但用户仍然能从
     * 「设置」里做两件事把锁机拆掉：关掉「显示在其他应用上层」权限、关掉应用通知
     * （后者在部分 ROM 上会让应用连带被限制后台，锁机就起不来了）。
     * 所以锁机生效期间，设置界面要和普通应用一视同仁地被盖住，而且**不给宽限**
     * （见 `LockMachineOverlayController.evaluate` 里的 grace 判定）。
     *
     * 包名用 [Settings.ACTION_SETTINGS] 反查而不是硬编码：各 ROM 的设置包名并不统一，
     * 反查拿到的才是用户这台机器上真正的那一个。查不到时退回常见包名集合。
     */
    fun isSystemSettings(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val resolved = settingsPackage ?: runCatching {
            context.packageManager.resolveActivity(
                Intent(Settings.ACTION_SETTINGS),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()?.also { settingsPackage = it }
        if (resolved != null) return resolved == packageName
        // 反查失败（极少数 ROM）时按已知包名兜底，宁可多盖一个也不要漏
        return packageName in FALLBACK_SETTINGS_PACKAGES
    }

    @Volatile
    private var settingsPackage: String? = null

    private val FALLBACK_SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.vivo.settings",
        "com.oplus.settings",
        "com.coloros.settings",
        "com.huawei.android.settings",
        "com.samsung.android.settings",
        "com.meizu.settings"
    )
}
