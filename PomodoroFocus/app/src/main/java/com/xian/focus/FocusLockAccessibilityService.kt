package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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

    /** 上次收起通知面板的时刻；给 [collapseNotificationShade] 节流，别拖一下就按十次返回。 */
    private var lastShadeCollapseAt = 0L

    /**
     * 上一次采样时，通知面板窗口是不是「贴顶、横跨整屏、高过屏高四分之一」的展开形态。
     *
     * ⚠️ **这个值单独没有任何意义** —— 它只用来回答「这一次是不是刚变的」。
     * 见 [collapseNotificationShade]：判据是「由未展开**变成**展开」，不是「现在是展开」。
     * 原因见那个函数的长注释（v2.0.98 的根因）。
     */
    private var shadeExpandedAtLastProbe = false

    /** 上一次采样的时刻（单调时钟）。隔太久说明状态不可信，那时一律不按返回。 */
    private var shadeProbedAt = 0L

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
        // 锁机期间不许下拉通知栏：面板一露头就替用户按一次返回，把它收回去（函数自带节流）。
        // 这是**事后**拦截 —— 悬浮窗（TYPE_APPLICATION_OVERLAY）在系统状态栏面前没有任何
        // 优先级，没有 root 就拦不住「下拉」这个动作本身，只能做到「露头即收、点不了」。
        //
        // ⚠️ 这条路**已经修到第四轮**（v2.0.80~.82 判据恒假、从没生效 → .83 删判据
        // → .94 收窄成只在窗口状态变化时试收 → .95 给几何判据补了「贴顶 + 全宽」），
        // 每一轮都是「换个姿势误伤」。这一轮误伤的是**系统锁屏（输不了手机密码）**和
        // **音量面板（一按音量键就退掉应用）**。判据在 v2.0.97 被重做，理由写在
        // `collapseNotificationShade` 的注释里 —— **动这个函数之前先读那段。**
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
     * 锁机期间把下拉出来的通知面板收回去。
     *
     * v2.0.98 起是**五道闸**，全过才动 `GLOBAL_ACTION_BACK`：
     * 0. **刚刚由未展开变成展开**（v2.0.98 新增，最要紧的一条）—— 见最后一段；
     * 1. 屏幕正在用（没息屏、没停在锁屏）—— [ForegroundApp.screenUnavailable]；
     * 2. 锁机层正盖着 —— 层不在说明用户此刻没被锁着，谈不上「防绕过」；
     * 3. 没有输入法窗口（v2.0.94 起，用户正在打字时绝不动）；
     * 4. 有一个 systemui 的 `TYPE_SYSTEM` 窗口**既贴顶、又横跨整屏、又高过屏高四分之一**，
     *    而且它的根节点类名命中 [SHADE_CLASS_HINTS]。
     *
     * ⚠️ **闸 0 的来由（v2.0.98，第五轮）**：闸 1~4 全都过了，用户仍然报「在系统应用 /
     * 锁机页上操作时被退回上一层」。往回推只剩一种可能 —— **闸 4 那组几何判据在这台机器上
     * 恒成立**（`NotificationShadeWindowView` 在部分 ROM 上折叠着也报满屏，正是 v2.0.95
     * 注释里那句「个别 ROM 折叠时也报满屏」的真身）。于是只要闸 1~3 成立
     * （= 用户正在操作锁机界面：层盖着、屏幕可用、没弹输入法），**任何一次窗口状态变化
     * 都会替用户按一次返回** —— 弹密码面板、弹确认框、按音量键、点开通知…
     * 表现就是「锁机时到处乱返回」。结论：**「现在是展开」这个形态判断在这台机器上没有
     * 区分度**，改用「**刚刚发生的变化**」：折叠态与展开态若报告同一套边界，跃变永远不发生，
     * 这条功能静默失效，但绝不误按（失败方向仍是「不按」，见下）。
     *
     * ⚠️ **为什么闸 1 和闸 4 非有不可** —— 这条路修到第五轮了，每轮都栽在同一件事上：
     * **无障碍眼里的 `TYPE_SYSTEM` 是个大杂烩**。通知面板、系统锁屏、音量面板
     * 在无障碍里全都是「systemui 的 `TYPE_SYSTEM` 窗口」，**几何也一模一样**：
     *
     * | 窗口 | 无障碍类型 | 包名 | 几何 | 谁挡它 |
     * | --- | --- | --- | --- | --- |
     * | 通知面板（展开） | `TYPE_SYSTEM` | systemui | 全屏贴顶 | 应当放行 |
     * | 系统锁屏（keyguard） | `TYPE_SYSTEM` | systemui | 全屏贴顶 | **闸 1** |
     * | 音量面板 | `TYPE_SYSTEM` | systemui | 全屏贴顶 | **闸 4 的类名** |
     *
     * v2.0.95 只补了「贴顶 + 全宽」两条**几何**判据，挡不住这两个 —— 它俩本来就满足。
     * 于是 `GLOBAL_ACTION_BACK` 落到了绝不该落的地方：用户在**手机锁屏上输密码**时被替按
     * 返回（密码界面退掉、**手机进不去**），以及**按一下音量键**就被判成「面板展开」、
     * 底下的应用被退掉。**几何这条路已经走到头了**，所以这一轮换成「类名 + 屏幕状态」。
     *
     * ⚠️ **但也不能反过来只看类名**：Android 10 起 keyguard 与通知面板**共用同一个窗口**
     * （`NotificationShadeWindowView`，keyguard 是它的一部分），类名分不开；而且这个窗口
     * **一直存在**（平时就一条状态栏那么高）。所以分工是：
     * **类名管「这是不是那个窗口」，几何管「它是不是正展开着」**，缺一不可。
     *
     * ⚠️ **失败方向必须是「不按」**。`GLOBAL_ACTION_BACK` 不可撤销，判错的代价直接落在
     * 用户脸上；漏拦的代价只是这条防绕过暂时失效（用户能下拉看一眼通知）。
     * 所以任何一道闸拿不准都放过 —— 这是硬要求，改判据时别动摇。
     *
     * ⚠️ 存档一笔：v2.0.83 删掉的第三条判据「它拿着焦点」（`window.isFocused`）是**错的** ——
     * 锁机层自己是 `TYPE_APPLICATION_OVERLAY` 且可获焦，用户下拉时焦点并不转给面板
     * （真机 `dumpsys window` 实证：面板 `mHasSurface=true` 展开着，`mCurrentFocus` 仍是
     * `com.xian.focus type=2038`）。那条判据恒为 false，函数每次都直接返回 ——
     * v2.0.80 ~ 2.0.82 的「禁下拉」**一次都没生效过**。**别再把它加回来。**
     */
    private fun collapseNotificationShade() {
        val now = SystemClock.elapsedRealtime()

        // ⚠️ **先采样，不管这次动不动手**：下面那条判据完全建立在「刚刚由未展开变成展开」上，
        // 状态一陈旧它就没有意义，所以每次进来先把状态刷新成当前值，再决定动不动手。
        val expandedNow = isShadeExpandedNow()
        val expandedBefore = shadeExpandedAtLastProbe
        val probedAtBefore = shadeProbedAt
        shadeExpandedAtLastProbe = expandedNow
        shadeProbedAt = now

        // 闸 0（v2.0.98 新增，也是最要紧的一条）：**必须是刚刚变的**，不是「现在是展开的」。
        // 见函数注释最后一段：那个窗口在部分 ROM 上**折叠着也报全屏**，
        // 「一直如此」于是被当成了「展开了」，后面几道闸一过就按返回。
        val justExpanded = expandedNow && !expandedBefore &&
            now - probedAtBefore < SHADE_PROBE_TRUST_MILLIS
        if (!justExpanded) return

        // 节流：用户按住往下拖时事件是连发的，别一下按出一串返回。
        if (now - lastShadeCollapseAt < SHADE_COLLAPSE_INTERVAL_MILLIS) return

        // 闸 1：息屏 / 锁屏还没解开 —— 一律不动。
        // 系统锁屏与通知面板在无障碍里长得一模一样（见上面的表），少了这一条，
        // 用户在自己的锁屏上输手机密码时就会被替按返回键，表现就是「密码输不进去、手机进不去」。
        if (ForegroundApp.screenUnavailable(applicationContext)) return

        // 闸 2：锁机层没盖着 —— 用户此刻没被锁在任何界面上，这时候按返回只会退掉
        // 他自己正在用的应用，纯亏。
        if (!LockMachineOverlayController.isShowing()) return

        // 闸 3：看到输入法窗口就一律不动（v2.0.94 起）。
        //
        // 用户正在打字时按返回键是最糟的误判 —— 会连输入法带界面一起退掉。而输入法
        // （含搜狗/讯飞这类第三方）几乎不会和「下拉通知面板」同时出现：手感上，
        // 面板一拉开输入法就收了。所以这里一旦看到 IME 窗口，宁可这一轮不拦面板，
        // 也不冒「把用户正在打的字退没」的风险。
        if (imeWindowShowing()) return

        lastShadeCollapseAt = now
        Log.d("XianLock", "shadeCollapse <- 面板刚展开，替用户按一次返回")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 采样：现在有没有一个 systemui 的 `TYPE_SYSTEM` 窗口**既贴顶、又横跨整屏、
     * 又高过屏高四分之一**，而且它的根节点类名命中 [SHADE_CLASS_HINTS]。
     *
     * ⚠️ **这是「采样」，不是判据**。单独拿它当判据就等于「一直按返回」——
     * 必须和上一次采样比出「由假变真」才算数，见 [collapseNotificationShade] 的注释。
     */
    private fun isShadeExpandedNow(): Boolean = runCatching {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        windows.orEmpty().any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            // 节点的 bounds 只能写进传进去的 Rect —— AccessibilityNodeInfo 没有无参 getter
            val bounds = Rect()
            val root = window.root
            val pkg = root?.packageName?.toString()
            if (root != null) {
                // 拿得到节点就顺带核包名：同 ROM 里过路的系统面板不止通知栏
                // （vivo 的侧滑返回层 com.vivo.upslide 也是 TYPE_SYSTEM），别认错人。
                if (!pkg.isNullOrBlank() && !pkg.contains("systemui")) return@any false
                root.getBoundsInScreen(bounds)
            } else {
                // 拿不到节点就退到窗口自己的 bounds —— 同一块屏，量出来的高度一样。
                window.getBoundsInScreen(bounds)
            }
            // ⚠️ 四条**必须同时成立**。前三条（几何）管「它是不是展开着」，
            // 第四条（类名）管「它是不是那个窗口」—— 缺一不可：
            // · 只有几何 ⇒ 音量面板和系统锁屏**全都满足**（vivo 把音量面板做成全屏窗口，
            //   锁屏本来就是全屏贴顶），于是「按一下音量键」被判成「面板展开了」，
            //   接着按返回、**把底下的应用退掉**（v2.0.94 / v2.0.95 用户报过两次）。
            // · 只有类名 ⇒ 那个窗口**一直存在**，等于「一直按返回」。
            val className = root?.className?.toString()
            val tall = bounds.height() * 4 > screenHeight
            val atTop = bounds.top <= 0
            val fullWidth = bounds.width() * 20 >= screenWidth * 19
            // 这一条挡的正是音量面板：它的类名是 `VolumeDialog*`，不含通知面板的特征串。
            val isShade = isShadeClass(className)
            // [诊断] 逐窗记一条（判据不成立也记）：将来「为什么没拦到」或「为什么误按了」
            // 都能从这行直接读出各窗口的类名、位置和大小，不用再靠猜。
            Log.d(
                "XianLock",
                "shadeProbe cls=$className box=(${bounds.left},${bounds.top}," +
                    "${bounds.right},${bounds.bottom}) screen=${screenWidth}x$screenHeight " +
                    "tall=$tall top=$atTop full=$fullWidth shade=$isShade"
            )
            tall && atTop && fullWidth && isShade
        }
    }.getOrElse { false }

    /** 现在有没有输入法窗口开着（含第三方输入法的横向窗）。 */
    private fun imeWindowShowing(): Boolean = runCatching {
        windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrElse { false }

    /**
     * 类名里有没有「通知面板」的特征串 —— 用来把音量面板这类系统窗口挡在外头。
     *
     * ⚠️ **不许往里加状态栏 / 通用容器类名**（`StatusBarWindowView`、`FrameLayout`…）：
     * 那个窗口是一直存在的，加进去就等于「一直按返回」。这一列只收下拉面板独有的名字。
     * ⚠️ 认不出来返回 false（=不拦）—— 见 [collapseNotificationShade] 里
     * 「失败方向必须是『不按』」那条。
     */
    private fun isShadeClass(className: String?): Boolean =
        !className.isNullOrBlank() && SHADE_CLASS_HINTS.any { className.contains(it) }

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

        /** 收起通知面板的最小间隔：用户按住往下拖时事件是连发的，别一下按出一串返回。 */
        private const val SHADE_COLLAPSE_INTERVAL_MILLIS = 800L

        /**
         * 「上一次采样」超过这么久就作废 —— 那时不再认为状态变化是「刚刚发生」的。
         *
         * 为什么要这条：判据从「现在是展开」改成「刚刚变成展开」之后，「刚刚」得有依据。
         * 采样在锁机层盖着时由每秒 tick 兜着（见 `LockMachineOverlayController.tickRunnable`），
         * 所以正常情况下这次进来离上次不到一秒。**超过三秒还没采过一次**，说明采样链断了
         * （层没盖着、或者服务刚起来），这时「上一次是未展开」这句话就毫无参考价值，
         * 按「不是刚刚展开」处理 —— 宁可这一轮不拦，也绝不误按返回键。
         */
        private const val SHADE_PROBE_TRUST_MILLIS = 3_000L

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
        /**
         * 通知面板的类名特征串（用 `contains` 匹配 —— ROM 的包名前缀各不相同：
         * 新版是 `com.android.systemui.shade.NotificationShadeWindowView`，
         * 旧版是 `com.android.systemui.statusbar.phone.…`）。
         *
         * 只用来回答「这个窗口是不是通知面板」，**不负责判断它有没有展开**（那是几何的活）。
         * ⚠️ **不许再加状态栏 / 通用容器类名**（`StatusBarWindowView`、`FrameLayout`…）：
         * 那个窗口是一直存在的，加进去就等于「一直按返回」。
         */
        private val SHADE_CLASS_HINTS = listOf(
            "NotificationShade",
            "NotificationPanel",
            "NotificationStackScroll",
            "ShadeHeader"
        )

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
