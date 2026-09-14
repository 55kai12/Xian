package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class FocusLockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 每秒都要问一次屏幕亮没亮，取一次就缓存住，省掉跨进程查询。 */
    private val powerManager: PowerManager? by lazy {
        getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /** 当前前台应用（系统组件除外），供应用限额计时用。 */
    private var foregroundPackage: String? = null

    /** [foregroundPackage] 最后一次被「窗口事件」确认的时刻（单调时钟）。 */
    private var foregroundConfirmedAt = 0L

    private var lastTickAt = 0L

    /** 不足一秒的零头攒着下次一起算：每秒丢几百毫秒，一小时就能少记十几分钟。 */
    private var pendingMillis = 0L

    /** 上次主动查窗口栈的时刻，避免每秒都去翻一遍。 */
    private var lastWindowQueryAt = 0L

    private val limitTicker = object : Runnable {
        override fun run() {
            // 必须兜住异常：tick 里崩一下，下面这行 postDelayed 就不会执行，计时从此永久停摆
            runCatching { tickAppLimit(applicationContext) }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastTickAt = 0L
        pendingMillis = 0L
        foregroundConfirmedAt = 0L
        handler.removeCallbacks(limitTicker)
        handler.post(limitTicker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(limitTicker)
        foregroundPackage = null
        AppLimitOverlayController.hide(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(limitTicker)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val context = applicationContext

        // 状态栏 / 通知栏：下拉时它会拿到焦点窗口，但这不是「用户切到了别的应用」。
        // 必须直接返回、保持覆盖层原样 —— 否则一下拉通知栏，锁机界面就被当成「切走了」撤掉，
        // 而状态栏收起时 systemui 不会补发窗口状态变化事件，界面也就一直回不来。
        if (packageName in SYSTEM_UI_PACKAGES) return

        // 其他系统组件（输入法、来电界面）不算「在用某个应用」，
        // 保留上一个真实前台应用 —— 否则一打字限额计时就断了。
        if (packageName == context.packageName && AppLimitOverlayController.isShowing()) {
            // 限额层是本进程的悬浮窗，它一抢到焦点，系统发来的事件包名也是本应用。
            // 若把它当成「用户切到了贤」，下一秒 tick 就会判定被限应用已经退出，
            // 把刚盖上的那层撤掉 —— 表现就是「额度用完了只闪一下，然后接着玩」，
            // 并且被污染的前台记录还有 60 秒信任期，这段时间既不记账也不弹窗。
            // 所以这里清掉缓存、放开限频，让 tick 自己查一次窗口栈：
            // 层还盖着时查不到活跃的应用窗口（悬浮层属于 TYPE_SYSTEM），
            // 沿用上次的前台应用、层保持不动；用户真切走了才会查到别的应用。
            foregroundPackage = null
            foregroundConfirmedAt = 0L
            lastWindowQueryAt = 0L
        } else if (!SystemAppAllowlist.isEssential(context, packageName)) {
            foregroundPackage = packageName
            foregroundConfirmedAt = SystemClock.elapsedRealtime()
        }

        if (LockMachineController.isActive(context)) {
            if (LockMachineController.isAllowed(context, packageName)) {
                if (LockMachineOverlayController.isShowing()) {
                    LockMachineOverlayController.hide(context)
                }
            } else {
                LockMachineOverlayController.show(context)
            }
            return
        } else if (LockMachineOverlayController.isShowing()) {
            LockMachineOverlayController.hide(context)
        }

        // 番茄钟锁机状态改由持久化数据推导，进程被杀后重新拉起依然生效
        if (!LockController.isLockActive(context)) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
            return
        }
        val isSelf = packageName == context.packageName
        // 系统必需组件（来电界面、输入法、状态栏）必须放行，
        // 否则锁机期间接不了电话，白名单应用里也无法输入文字。
        val isSystemEssential = SystemAppAllowlist.isEssential(context, packageName)
        if (isSelf || isSystemEssential) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
        } else {
            LockOverlayController.show(context)
        }
    }

    /**
     * 每秒把这段时间算到当前前台应用头上，累计超限就盖一层锁。
     *
     * 用的是单调时钟（elapsedRealtime）而不是墙上时间：系统时间被 NTP 校准往回拨时，
     * 差值为负会让这一秒凭空消失，长期累计就是「用了一小时只记了半小时」。
     */
    private fun tickAppLimit(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val interactive = powerManager?.isInteractive == true

        // 三种情况都只对齐时间、不记账，并且零头一并清掉：
        // ① 服务刚连上；② 息屏（否则揣兜里放一晚，第二天额度莫名其妙就满了）；
        // ③ 中间隔了一大段 —— 系统休眠或服务被冻结期间没人能证明用户在玩手机，宁可少记不可虚增。
        if (lastTickAt == 0L || !interactive || now - lastTickAt > MAX_GAP_MILLIS) {
            lastTickAt = now
            pendingMillis = 0L
            return
        }

        pendingMillis += now - lastTickAt
        lastTickAt = now

        val packageName = foregroundPackageForTick() ?: return
        val deltaSeconds = pendingMillis / 1_000L
        if (deltaSeconds <= 0L) return
        pendingMillis -= deltaSeconds * 1_000L

        if (AppLimitStore.isLocked(context, packageName)) {
            // 锁机覆盖层优先级更高，不跟它抢同一块屏
            if (!LockMachineController.isActive(context) && !LockOverlayController.isShowing()) {
                AppLimitOverlayController.show(context, packageName)
            }
            return
        }
        if (AppLimitOverlayController.isShowing()) {
            AppLimitOverlayController.hide(context)
        }
        // 锁机期间本来也玩不了，不记账
        if (!LockMachineController.isActive(context)) {
            AppLimitStore.addSeconds(context, packageName, deltaSeconds)
        }
    }

    /**
     * 计时用的前台应用。
     *
     * 正常路径是窗口事件把 [foregroundPackage] 喂好，直接用、零成本。但事件并不可靠：
     * 服务被系统重启后缓存会清空，而用户如果一直待在同一个应用里没切换过，事件永远不会补发 ——
     * 结果就是「用了一整天，一秒都没记上」。所以缓存过期或为空时，自己读一次窗口栈，不依赖任何事件。
     */
    private fun foregroundPackageForTick(): String? {
        val cached = foregroundPackage
        val fresh = cached != null &&
            SystemClock.elapsedRealtime() - foregroundConfirmedAt < EVENT_TRUST_MILLIS
        if (fresh) return cached

        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowQueryAt < WINDOW_QUERY_INTERVAL_MILLIS) return cached
        lastWindowQueryAt = now

        val queried = queryForegroundPackage() ?: return cached
        foregroundPackage = queried
        foregroundConfirmedAt = now
        return queried
    }

    /** 问一句窗口栈里正在显示的应用是谁；拿不到就返回 null，不影响事件驱动的正常路径。 */
    private fun queryForegroundPackage(): String? = runCatching {
        windows.orEmpty()
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root
            ?.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() && it !in SYSTEM_UI_PACKAGES }
    }.getOrNull()

    override fun onInterrupt() = Unit

    companion object {
        private const val TICK_MILLIS = 1_000L

        /** 两次 tick 之间隔了这么久，说明中间系统休眠或被冻结过，这段时间不补记。 */
        private const val MAX_GAP_MILLIS = 10_000L

        /** 窗口事件超过这么久没更新，就不再信任缓存的前台应用，改为主动查窗口。 */
        private const val EVENT_TRUST_MILLIS = 60_000L

        /** 主动查窗口的最小间隔 —— 别每秒都去翻窗口栈。 */
        private const val WINDOW_QUERY_INTERVAL_MILLIS = 5_000L

        /**
         * 状态栏 / 通知栏的包名。它们拿到焦点窗口只说明「系统 UI 露出来了」，
         * 不代表用户离开了当前应用，覆盖层必须保持原样。
         */
        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui"
        )
    }
}
