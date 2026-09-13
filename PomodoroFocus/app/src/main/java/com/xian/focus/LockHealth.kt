package com.xian.focus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 锁机的运行前提自检。
 *
 * 锁机依赖四件事：无障碍服务、悬浮窗、通知、电池优化白名单。
 * 任何一项没开，锁机都会「静默失效」—— 用户开了开关却发现不锁，
 * 又看不出哪里不对。所以把前提条件显式列出来，并给出跳转。
 */
object LockHealth {

    data class Item(val labelRes: Int, val granted: Boolean, val settingsIntent: Intent)

    fun check(context: Context): List<Item> = listOf(
        Item(R.string.health_accessibility, isAccessibilityOn(context), accessibilityIntent()),
        Item(R.string.health_overlay, Settings.canDrawOverlays(context), overlayIntent(context)),
        Item(
            R.string.health_notification,
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            notificationIntent(context)
        ),
        Item(R.string.health_battery, isIgnoringBatteryOptimizations(context), batteryIntent())
    )

    /** 无障碍服务是否已启用：在系统的已启用服务列表里找自己。应用限额也靠它计时，故对外可见。 */
    fun isAccessibilityOn(context: Context): Boolean {
        val expected = ComponentName(context, FocusLockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** 无障碍设置页 —— 掉线提醒的跳转目标也用它。 */
    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun overlayIntent(context: Context) = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    private fun notificationIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * 打开电池优化设置列表，而不是直接弹「忽略优化」申请框 ——
     * 后者需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限（应用商店受限），
     * 走列表页零权限且各机型都能进。
     */
    private fun batteryIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
