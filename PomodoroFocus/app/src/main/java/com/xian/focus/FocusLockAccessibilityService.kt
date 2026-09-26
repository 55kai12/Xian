package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference

class FocusLockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 每秒都要问一次屏幕亮没亮，取一次就缓存住，省掉跨进程查询。 */
    private val powerManager: PowerManager? by lazy {
        getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * 当前前台应用（系统组件除外）。供应用限额计时用，同时通过 [ForegroundApp] 对外共享 ——
     * 锁机的起锁路径（定时到点、开机自启、时段自检）手上没有前台信息，
     * 而「现在这个应用在白名单里吗」正是它们盖屏前唯一该问的问题。
     */
    private var foregroundPackage: String?
        get() = ForegroundApp.packageName
        set(value) {
            // 走 mark 而不是直接赋值：赋值时刻也要一起记下来，锁机那边靠它判断这个值还新不新鲜
            ForegroundApp.mark(value)
        }

    /** [foregroundPackage] 最后一次被「窗口事件」确认的时刻（单调时钟）。 */
    private var foregroundConfirmedAt = 0L

    private var lastTickAt = 0L

    /** 不足一秒的零头攒着下次一起算：每秒丢几百毫秒，一小时就能少记十几分钟。 */
    private var pendingMillis = 0L

    /** 上次主动查窗口栈的时刻，避免每秒都去翻一遍。 */
    private var lastWindowQueryAt = 0L

    /** 上次收起通知面板的时刻；给 [collapseNotificationShade] 节流，用户拖拽时事件连发，别一下收十次。 */
    private var lastShadeCollapseAt = 0L

    private val limitTicker = object : Runnable {
        override fun run() {
            // 必须兜住异常：tick 里崩一下，下面这行 postDelayed 就不会执行，计时从此永久停摆
            runCatching { tickAppLimit(applicationContext) }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 登记自己，让锁机层能借去查窗口栈（弱引用，服务销毁后自然失效）
        instance = WeakReference(this)
        // 覆盖安装 APK 之后，系统仍按**旧的**服务配置派发事件，「读窗口内容」这项能力
        // 要用户把无障碍开关关掉再打开一次才会重新生效 —— 用户看不见这个差别，
        // 只会觉得「更新完就不记录了」。这里在运行时把 flag 补上
        // （capabilities 由系统按 XML 授予、运行时改不了，只能补 flag），
        // 能免掉一部分机型上的那次隐形操作。
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            setServiceInfo(info)
        }
        // 系统重新拉起无障碍服务（进程被清后它常会这么做）时，顺手把守护服务也带回来：
        // 那是记账和拦截真正依赖的东西，它没了光有无障碍也不够。
        GuardService.sync(applicationContext)
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
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        // 只收「窗口状态变化」。
        // ⚠️ `TYPE_WINDOWS_CHANGED`（窗口层级变动）**已经不再收**：它对着每一次层级变动上报
        // —— 输入法弹出/收起、应用内弹个对话框、分屏拖一下都会来一发，而面板判据分不出
        // 「这是下拉通知栏」还是「用户正在打字」，判错的代价是按一次返回键退掉底下的应用。
        // 当初收它是想兜「ROM 靠撑大窗口来展开面板、不发状态变化」的机型，但那条路本来
        // 就没生效过（收到后立刻被下面的 return 挡掉）。面板展开必然拿到焦点、必发状态变化。
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val context = applicationContext
        // 锁机期间不许下拉通知栏（v2.1.0 恢复启用）。做法是「面板一露头就替用户把它收回去」
        // 的事后拦截：悬浮窗（TYPE_APPLICATION_OVERLAY）在系统状态栏面前没有优先级，
        // 没有 root 就拦不住「下拉」这个动作本身，只能做到「露头即收、点不了」。
        //
        // ⚠️ v2.0.99 曾把这条整段停用 —— 前五轮（.80~.82 判据恒假 → .83 删判据 → .94 收窄
        // 触发源 → .95 加几何 → .97 换类名 → .98 加跃变）全都栽在同一处：**动作用的是
        // `GLOBAL_ACTION_BACK`，不可撤销**，判据一旦误命中就把用户自己的界面退掉
        // （在手机锁屏上输不进密码、按音量键把底下应用退掉）。而通知面板 / 系统锁屏 /
        // 音量面板在无障碍里完全同型，判据在无 root 下没有可靠解。
        //
        // **v2.1.0 换了动作，不再换判据**：改用 `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`
        // （API 31+），它只有一个语义「把通知面板收起来」，面板没展开时是 no-op，
        // 不碰返回键、不碰任何应用。判据误命中的代价从「退掉用户界面」降到「什么都不发生」。
        // 理由与取舍写在 `collapseNotificationShade` 函数体开头 —— 动它之前先读那段。
        if (LockMachineController.isActive(context)) {
            collapseNotificationShade()
        }
        val packageName = event.packageName?.toString() ?: return

        // [诊断] 抓「左滑返回那一下，系统报的前台包名是什么」。
        Log.d(
            "XianLock",
            "win pkg=$packageName cls=${event.className?.toString()?.substringAfterLast('.')}" +
                " lock=${LockMachineController.isActive(context)}" +
                " overlay=${LockMachineOverlayController.isShowing()}" +
                " cached=${ForegroundApp.packageName}"
        )

        // 状态栏 / 通知栏：下拉时它会拿到焦点窗口，但这不是「用户切到了别的应用」。
        // 必须直接返回、保持覆盖层原样 —— 否则一下拉通知栏，锁机界面就被当成「切走了」撤掉，
        // 而状态栏收起时 systemui 不会补发窗口状态变化事件，界面也就一直回不来。
        if (packageName in SYSTEM_UI_PACKAGES) return

        // 其他系统组件（输入法、来电界面）不算「在用某个应用」，
        // 保留上一个真实前台应用 —— 否则一打字限额计时就断了。
        if (packageName == context.packageName && selfOverlayShowing()) {
            // 本进程的悬浮层**哪一个**都算：限额层和锁机层都跑在这个进程里，
            // 谁抢到焦点，系统发来的事件包名都是本应用。
            //
            // 限额层：若把它当成「用户切到了贤」，下一秒 tick 就会判定被限应用已经退出，
            // 把刚盖上的那层撤掉 —— 表现就是「额度用完了只闪一下，然后接着玩」。
            //
            // 锁机层（v2.0.59 补）：污染更糟。锁机层的每秒自检会读这里写下的值，
            // 而「贤自己」在所有判断里都算放行 —— 于是刚 addView 上去的那一瞬间，
            // 系统为我们补的自事件就把锁机层判成「该让开」，层立刻被自己撤掉。
            // 用户看到的是锁机一闪而过、切到别的应用也不盖。
            //
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
            val selfEvent = packageName == context.packageName
            // 覆盖层自己抢焦点发来的事件说明不了「用户现在在哪个应用」：包名必然是本应用，
            // 而「刚 addView 补发的自事件」和「用户真切回了贤」在包名上分不出来。
            // 走下面的放行分支只会把刚盖上的层撤掉（自事件迟到超过防抖窗口就露馅），
            // 所以这里直接不作数 —— 交给锁机层自己每秒的判定，那条路读系统使用记录，
            // 不受这个事件影响，一秒内就会给出正确答案。
            if (selfEvent && selfOverlayShowing()) return
            // 放行还是该盖，统一交给锁机层自己的判定入口 ——
            // 那里还管着「落在桌面上先宽限一会儿」这一条：手势导航下从屏幕边缘往里滑
            // 太容易把白名单应用整个退出到桌面，立刻盖上来会把用户堵在里面。
            LockMachineOverlayController.evaluate(context, packageName)
            return
        } else if (LockMachineOverlayController.isShowing()) {
            // 锁机已经结束了，层必须立刻收 —— 这里的判断比防抖可靠
            LockMachineOverlayController.hide(context, force = true)
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
        // 记账主路径是守护服务里的 AppLimitWatcher（读系统使用记录，进程被冻结也能补算）。
        // 它活着时由它驱动，这里不插手。
        if (AppLimitWatcher.isRunning()) return
        // 守护服务被 ROM 清掉时，从这里继续驱动**同一个**记账器 —— 它是游标式的，
        // 谁驱动都不会重复计，而这条比下面那条老路（依赖窗口事件）可靠得多。
        // 少了这一段，守护服务一被清就只能退回到不可靠的窗口事件，表现就是「删了后台就失效」。
        if (AppLimitWatcher.hasUsageAccess(context)) {
            AppLimitWatcher.tick(context)
            return
        }
        // 连「使用情况访问」都没给，才走老路 —— 它是那种情况下唯一还能用的依靠，不能删。
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

    /**
     * 锁机期间把下拉出来的通知面板收回去（**v2.1.0 恢复启用**）。
     *
     * **这一版为什么能用** —— 换的是动作，不是判据。收起面板改用
     * `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`（API 31+）：它只有一个语义
     * 「把通知面板收起来」，不接受任何别的解释 ——
     *
     * · 面板没展开 ⇒ 系统自己判空，**什么都不做**（no-op）；
     * · 面板展开着 ⇒ 收回去，**不碰底下的应用、不碰输入法、不碰锁机自己弹的密码面板**。
     *
     * 于是「判据判错了」的代价从「替用户按一次**不可撤销**的返回键」直接降到零。
     * 而 .80 到 .99 六次翻车的根因正是那个代价：用户在**手机锁屏上输不进密码**、
     * **按一下音量键就把底下的应用退掉**。
     *
     * ⚠️ **绝不许改回 `GLOBAL_ACTION_BACK`**（含「用返回键收面板」的任何变体）。
     * 判据这一侧是没有解的：通知面板 / 系统锁屏 / 音量面板在无障碍里**完全同型**
     * （systemui + `TYPE_SYSTEM` + 贴顶全屏），`NotificationShadeWindowView` 在部分 ROM 上
     * **折叠着也报满屏**，而 keyguard 与通知面板从 Android 10 起**共用同一个窗口**、
     * 类名也分不开 —— 几何面 / 类名面 / 焦点面（v2.0.83 证伪）**都没有区分度**。
     * 只要动作还是返回键，误命中就必然再发生一次。完整复盘见 `docs/DETAILS.md`「禁下拉」。
     *
     * 下面三道闸只负责「别在不该动的时候动」，全部是「拿不准就放过」：
     */
    private fun collapseNotificationShade() {
        // 闸 0（API 门槛）：`DISMISS_NOTIFICATION_SHADE` 是 API 31（Android 12）才有的常量。
        // 更低版本上这个值没有定义，传过去轻则无效、重则被当成未知动作 —— 索性不动手，
        // 这类机器上就是不做这条功能（失败方向仍是「不拦」，不是「乱按」）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val now = SystemClock.elapsedRealtime()
        // 节流：用户按住往下拖时窗口事件是连发的。动作本身无害，这里纯粹是省电省日志。
        if (now - lastShadeCollapseAt < SHADE_COLLAPSE_INTERVAL_MILLIS) return

        // 闸 1：息屏 / 停在手机锁屏上 —— 一律不动。
        // 手机锁屏与通知面板同型，而这里更多出于「别去碰 keyguard」的谨慎：
        // 系统锁屏有它自己的面板，不该由我们去收。
        if (ForegroundApp.screenUnavailable(applicationContext)) return

        // 闸 2：锁机层没盖着 —— 用户此刻没被锁在任何界面上（在白名单应用里、或在桌面）。
        // 这时候他想看一眼通知就让他看，谈不上绕过一个根本没盖着的锁。
        if (!LockMachineOverlayController.isShowing()) return

        // 闸 3：看到输入法窗口就不动（v2.0.94 起）。锁机页自己会弹密码键盘，
        // 用户在这种状态下还去下拉面板的情况很少见；少收这一次，也好过在别人打字时插手。
        if (imeWindowShowing()) return

        lastShadeCollapseAt = now
        Log.d("XianLock", "shadeCollapse <- 调 DISMISS_NOTIFICATION_SHADE 收面板")
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }
    /** 现在有没有输入法窗口开着（含第三方输入法的横向窗）。 */
    private fun imeWindowShowing(): Boolean = runCatching {
        windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrElse { false }

    /** 本进程的悬浮层（限额层或锁机层）是不是正显示着 —— 它们抢焦点时的包名都是本应用。 */
    private fun selfOverlayShowing(): Boolean =
        AppLimitOverlayController.isShowing() || LockMachineOverlayController.isShowing()

    override fun onInterrupt() = Unit

    companion object {
        private const val TICK_MILLIS = 1_000L

        /** 两次 tick 之间隔了这么久，说明中间系统休眠或被冻结过，这段时间不补记。 */
        private const val MAX_GAP_MILLIS = 10_000L

        /** 窗口事件超过这么久没更新，就不再信任缓存的前台应用，改为主动查窗口。 */
        private const val EVENT_TRUST_MILLIS = 60_000L

        /** 主动查窗口的最小间隔 —— 别每秒都去翻窗口栈。 */
        private const val WINDOW_QUERY_INTERVAL_MILLIS = 5_000L

        /** 收起通知面板的最小间隔：用户按住往下拖时事件是连发的，别一下调一串。 */
        private const val SHADE_COLLAPSE_INTERVAL_MILLIS = 800L

        /**
         * 「闪一下的系统层」包名 —— 它们拿到焦点窗口只说明系统 UI 露出来了，
         * **不代表用户离开了当前应用**。这些事件必须直接丢掉：既不写前台应用缓存，
         * 也不参与「该不该盖锁机层」的判定。
         *
         * 两类：
         * 1. 状态栏 / 通知栏。下拉时它拿到焦点，收起时还不会补发窗口事件 ——
         *    当成「切走了」会把锁机界面撤掉，而且再也回不来。
         * 2. 厂商手势导航的过渡层，即各家自己的 UpSlide。**vivo 上实测到的元凶**：
         *    从屏幕边缘往里滑（返回手势）时 `com.vivo.upslide` 会抢一下焦点，
         *    无障碍事件报出的包名就是它。它既不在白名单、也不是系统必需组件，
         *    于是被当成「用户切到了别的应用」，等一秒确认期过后整屏锁上 ——
         *    而用户从头到尾都在微信里没动过（系统使用记录里它根本不是 Activity，
         *    微信一直是 RESUMED）。表现就是「左滑返回就弹锁机，太敏感」。
         *
         * 忽略它是安全的：用户真的滑走时，目标应用（或桌面）会补发自己的窗口事件，
         * 前台照样能跟上。手势层只是中间闪过的那一帧。
         */
        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            // 厂商手势导航过渡层
            "com.vivo.upslide",
            "com.oplus.upslide",
            "com.oppo.upslide",
            "com.huawei.upslide",
            "com.hihonor.upslide",
            "com.miui.upslide",
            "com.samsung.android.upslide"
        )

        /** 活着的无障碍服务实例；窗口栈查询要借它（弱引用，服务销毁后自然失效）。 */
        private var instance: WeakReference<FocusLockAccessibilityService>? = null

        /**
         * 借无障碍服务的窗口栈查一次真实前台应用；服务不在或查不到返回 null。
         *
         * 这是**免权限**的前台感知源：只读无障碍自己拿到的窗口列表（TYPE_APPLICATION 且活跃），
         * 不碰「使用情况访问」。锁机层盖屏前的最后否决走这里 —— 手机没授 usage 权限时，
         * 「系统使用记录」这条兜底路是断的，否决就永远不会发生，误锁照旧。
         */
        fun foregroundFromWindowStack(): String? =
            instance?.get()?.runCatching { queryForegroundPackage() }?.getOrNull()

        /**
         * 当前有没有输入法窗口开着（含第三方输入法的横向窗口）。
         *
         * 供锁机层判断「用户正在打字」用。写进 `SystemAppAllowlist` 那条路只能按**包名**认
         * 输入法，而 IME 窗口本身不是 Activity、`ForegroundApp.resolve()` 那两路感知都看不见它；
         * 只有无障碍的窗口栈能直接问到 `TYPE_INPUT_METHOD` 的窗口。
         *
         * 服务不在（无障碍没开 / 被 ROM 清掉）时返回 false —— 调用方按「没有输入法」处理，
         * 与改动前的行为一致，不会因为拿不到就把锁机放开。
         */
        fun isInputMethodWindowShowing(): Boolean =
            instance?.get()?.runCatching {
                windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }?.getOrNull() ?: false

        /**
         * 供锁机层每秒的自检兜底调用（见 `LockMachineOverlayController.tickRunnable`）。
         *
         * 主驱动是上面的窗口事件，但面板停住之后可能不再产生新事件，光靠事件会漏一次。
         * 服务不在（无障碍没开 / 被 ROM 清掉）时什么都不做，不影响别的判定。
         */
        fun collapseShadeIfNeeded() {
            instance?.get()?.runCatching { collapseNotificationShade() }
        }
    }
}
