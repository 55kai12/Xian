package com.xian.focus

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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

    /**
     * 无障碍服务是否真的拿到了「读窗口」能力。
     *
     * 应用限额的计时兜底（服务被 ROM 重启后自己查一次窗口）依赖 `canRetrieveWindowContent`，
     * 而它是**服务配置**里声明的：覆盖安装 APK 之后系统仍按旧配置派发事件，
     * 必须把开关关掉再打开一次才会重新读配置。用户看不到这个差别，
     * 只会觉得「更新完就不记录了」—— 所以这里把状态查出来，让他能自己解决。
     */
    fun canReadWindows(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val info = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .firstOrNull { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            ?: return false
        return info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
    }

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

    /**
     * 打开系统的「自启动管理」。
     *
     * 各家 ROM 的自启动白名单是自己加的，没有标准 API —— 应用既读不到状态，
     * 也拿不到统一入口，只能一个个试常见机型的管理页；都试不上就退到应用详情页，
     * 至少让用户能顺着「权限 / 后台运行」自己找到。
     */
    fun autostartIntent(context: Context): Intent {
        val candidates = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.asus.mobilemanager/com.asus.mobilemanager.autostart.AutoStartActivity"
        )
        candidates.forEach { flat ->
            val component = ComponentName.unflattenFromString(flat) ?: return@forEach
            val exists = runCatching {
                context.packageManager.getActivityInfo(component, 0)
            }.isSuccess
            if (exists) {
                return Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
