package com.xian.focus

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xian.focus.databinding.ItemOverlayAppGridBinding
import java.util.Calendar

@SuppressLint("StaticFieldLeak", "InflateParams")
object LockMachineOverlayController {
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastShowAt = 0L
    private var lastHideAt = 0L
    private const val DEBOUNCE_MILLIS = 350L

    /**
     * 最近一次拿到的 Context。
     *
     * 层被收起时 `overlayView` 是 null，而 tick 还得靠 Context 去问「锁机还在不在、屏幕可用没」——
     * 没有它就只能在层消失时把 tick 断了，那样息屏期间让开的层**永远盖不回来**。
     */
    private var lastContext: Context? = null

    /** 退出冷静期截止时刻；0 表示弹窗没开着。 */
    private var cooldownEndsAt = 0L

    /** 白名单文件夹展开状态；悬浮窗每次重建都回到收起。 */
    private var whitelistExpanded = false

    /**
     * 检测到「前台不在白名单里」之后，先观察这么久再盖。
     *
     * 手势导航的返回手势不是一次到位：手指从屏幕边缘往里滑的过程中，系统会实时预览
     * 上一屏（另一个应用 / 桌面），这时候收到的窗口事件已经是那个「目标」的包名了 ——
     * 可手指一滑回去、手势取消，用户压根没离开。凭这一瞬间的包名就整屏盖上来，
     * 用户看到的就是「轻轻一划就被锁住」。所以先等一小会儿再确认一次；
     * 中间只要回到了白名单应用，就当作什么都没发生。
     *
     * 2026-09-19 由 1 秒收紧到 300ms：一秒的等待在真机上表现为「切出去之后还看得见
     * 那个应用的界面」，锁机的存在感太弱。300ms 仍长于手势预览的报点间隔，够吃掉
     * 「划一半又滑回去」，但人眼已经跟得上「一离开就盖住」。
     */
    private const val LEAVE_CONFIRM_MILLIS = 300L

    /**
     * 落在桌面上先宽限这么久。
     *
     * 从白名单应用首页滑返回就是直接退到桌面，那确实是离开了，但用户往往只想返回
     * 上一页 —— 0.5 秒够点一下「返回」了，再宽就显得锁机没在管。
     * 宽限期内回到白名单应用完全不盖；点开别的应用仍然立刻盖（那时前台已不是桌面）。
     */
    private const val HOME_GRACE_MILLIS = 500L

    /** 系统桌面（launcher）包名，懒查一次；查不到为 null，此时不做宽限（按原样立刻盖）。 */
    private var homePackage: String? = null

    /** 检测到「前台不在白名单里」的时刻；0 表示当前一切正常。 */
    private var pendingLeaveSince = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } finally {
                // ⚠️ 续排必须放 finally：息屏让开、切进白名单让开这些分支都会提前 return，
                // 而 tick 一旦断掉就再没人检查「现在该不该盖」—— 表现是「锁机莫名失效」。
                // 停只停一种情况：锁机已结束（tick() 里判断后不续），见下面的 keepTicking。
                if (keepTicking) handler.postDelayed(this, 1_000L)
            }
        }
    }

    /** tick 是否继续自续。锁机结束、进程要收工时置 false。 */
    private var keepTicking = false

    private fun tick() {
        // 层可能是 null（息屏让开时收的），所以停不停**不能看层在不在**，
        // 只能看锁机还在不在 —— 锁屏期间无障碍事件也不会来，tick 是唯一能把层盖回来的。
        val view = overlayView
        val context = (view?.context ?: lastContext)?.applicationContext
        if (context == null || !LockMachineController.isActive(context)) {
            keepTicking = false
            if (view != null && context != null) hide(context, force = true)
            return
        }
        if (view == null) {
            // 层不在（多半是息屏/锁屏让开时收的）。屏幕回到可用状态就重新盖 ——
            // 仍走 evaluate，否则用户正用着白名单应用也会被盖。
            if (!ForegroundApp.screenUnavailable(context)) {
                evaluate(context, ForegroundApp.resolve(context))
            }
            return
        }
        // 锁屏 / 息屏：层必须让开（它画得在锁屏之上，不让开就是「输不了手机密码」）。
        // 排在下面那道退出流程闸门**之前** —— 它更优先。
        if (ForegroundApp.screenUnavailable(context)) {
            hide(context, force = true)
            return
        }
        // 锁机期间不许下拉通知栏：主驱动是无障碍的窗口事件，这里每秒兜一次底 ——
        // 面板停住之后可能不再产生新事件，光靠事件会漏（函数自带节流，不会连按返回）。
        FocusLockAccessibilityService.collapseShadeIfNeeded()
        // 每秒对一次「现在到底该不该盖」。
        //
        // 主驱动是无障碍的窗口事件，但服务被系统重启或 ROM 清掉之后事件就断了；
        // 断了的表现是：用户切进白名单应用，这层既不知道、也没人让它让开，就那么死死盖着 ——
        // 用户看到的就是「明明在白名单里，还是被卡在锁机页面」。所以这里自己看一眼前台是谁，
        // 数据源在无障碍不可用时会退到系统使用记录，不依赖任何服务活着。
        //
        // 退出流程进行中不让开：冷静期弹窗 / 密码面板开着时用户正在跟这层交互，
        // 输入法一弹出来前台就变成输入法，而输入法属于永远放行的系统组件 ——
        // 少了这道闸，用户刚长按完「退出锁机」，整层就直接没了。
        if (cooldownEndsAt <= 0L) {
            val foreground = ForegroundApp.resolve(context)
            if (foreground != null && LockMachineController.isAllowed(context, foreground)) {
                hide(context, force = true)
                return
            }
        }
        view.findViewById<FlipClockView>(R.id.remainingText)
            .setDisplay(LockMachineController.remainingText(context))
        updateClockText(context, view)
        updateCooldownText(view)
    }

    /** 清后台的结果反馈展示这么久，之后按钮文字还原。 */
    private const val CLEAR_FEEDBACK_MILLIS = 2_000L

    /** 清后台的 2 秒反馈到期：把按钮文字换回原标签（层已经没了就什么都不做）。 */
    private val restoreClearButtonLabel = Runnable {
        overlayView?.findViewById<Button>(R.id.clearBackgroundButton)
            ?.setText(R.string.clear_background_apps)
    }

    /** 当前时间 + 本月剩余退出额度，跟倒计时一起每秒刷新。 */
    private fun updateClockText(context: Context, view: View) {
        val calendar = Calendar.getInstance()
        val now = "%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        view.findViewById<TextView>(R.id.currentTimeText).text =
            context.getString(R.string.current_time_format, now)
        val quota = LockExitQuota.remaining(context)
        // 「剩余 0 / 2」太含蓄：额度用完时用户会以为是显示问题，长按、点确认，什么都没发生 ——
        // 那就是「卡住」。所以用完时直接把话说满，让他知道锁机会一直撑到结束。
        view.findViewById<TextView>(R.id.exitQuotaText).text = if (quota > 0) {
            context.getString(R.string.exit_quota_format, quota, LockExitQuota.MONTHLY_LIMIT)
        } else {
            context.getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT)
        }
    }

    /** 冷静期倒计时：走完前「确认退出」保持禁用。 */
    private fun updateCooldownText(view: View) {
        if (cooldownEndsAt <= 0L) return
        val remainSeconds = ((cooldownEndsAt - System.currentTimeMillis()) / 1000L).toInt()
        val cooldownText = view.findViewById<TextView>(R.id.cooldownText)
        val confirmButton = view.findViewById<Button>(R.id.confirmExitButton)
        if (remainSeconds > 0) {
            cooldownText.text = view.context.getString(R.string.exit_confirm_cooldown, remainSeconds)
            confirmButton.isEnabled = false
        } else {
            cooldownText.setText(R.string.exit_confirm_ready)
            confirmButton.isEnabled = true
        }
    }

    fun isShowing(): Boolean = overlayView != null

    /**
     * 「现在到底该不该盖」的唯一判定入口，窗口事件与每秒对账两路共用。
     *
     * 起锁是无条件的：定时时段到点、开机自启、保存时段后自检，任何一条都可能在用户
     * 正用着白名单应用时把锁打开。所以这里先问一句当前前台应用是谁 —— 在白名单里
     * （或就是贤自己、或来电/输入法等系统组件）就一个像素都不盖，否则才 show()。
     * [foreground] 为 null 表示**不知道**，按「该锁」处理：漏锁比误锁严重得多。
     *
     * 判定用的 [ForegroundApp.resolve] 在无障碍事件不可靠时会退回系统使用记录，
     * 所以「无障碍没开」不再等于「白名单失效」。
     */
        fun evaluate(context: Context, foreground: String?) {
        val applicationContext = context.applicationContext
        if (!LockMachineController.isActive(applicationContext)) {
            pendingLeaveSince = 0L
            hide(applicationContext, force = true)
            return
        }
        // 锁屏 / 息屏时**一律让开**，这是唯一一条比「在白名单里」还靠前的规则。
        //
        // 锁机层画得在锁屏之上，而锁屏不是一个 Activity —— 两路前台感知都看不见它，
        // 于是「不在白名单里」恒成立、层就赖在锁屏上。用户按电源键亮屏看到的是锁机页
        // 而不是自己的锁屏，**密码键盘出不来、手机进不去**。这是误锁里最严重的一种：
        // 锁住的是手机本身。解开锁屏后下一次 tick 会重新按前台决定要不要盖，不会漏锁。
        if (ForegroundApp.screenUnavailable(applicationContext)) {
            pendingLeaveSince = 0L
            // [诊断]
            Log.d("XianLock", "eval -> 屏幕不可用（锁屏/息屏），让开")
            hide(applicationContext, force = true)
            return
        }
        // 退出流程进行中不对账：冷静期弹窗 / 密码面板开着时用户正在跟这层交互，
        // 输入法一弹出来前台就变成输入法，而输入法属于永远放行的系统组件 ——
        // 少了这道闸，用户刚长按完「退出锁机」，整层就连着面板一起被撤掉。
        if (cooldownEndsAt > 0L) {
            // [诊断]
            Log.d("XianLock", "eval fg=$foreground -> cooldown, skip")
            return
        }
        // 在白名单里（含贤自己、来电/输入法等系统组件）—— 立刻让开，这条不能有任何延迟
        if (foreground != null && LockMachineController.isAllowed(applicationContext, foreground)) {
            pendingLeaveSince = 0L
            // [诊断]
            Log.d("XianLock", "eval fg=$foreground -> ALLOWED, hide (overlay was ${isShowing()})")
            hide(applicationContext, force = true)
            return
        }
        // 不在白名单里：先让这个状态稳一会儿再盖。
        // 这期间**什么都不做**（层是隐藏的就让它隐藏，是显示的就让它显示）——
        // 「手势返回滑到一半又滑回去」那种一瞬就报出别的包名的情况，在这里就被吃掉了。
        val now = System.currentTimeMillis()
        if (pendingLeaveSince == 0L) pendingLeaveSince = now
        val grace = when {
            foreground != null && isHome(applicationContext, foreground) -> HOME_GRACE_MILLIS
            // 系统设置**零宽限**：它是锁机唯一真正的出口 —— 关「显示在其他应用上层」权限、
            // 关应用通知，两下就能把锁机拆了。普通应用给 0.3 秒是防手势误判，设置不需要防
            // （用户不可能「滑到一半又滑回去」还正好停在设置上），所以点进去的瞬间就压住。
            foreground != null &&
                LockMachineController.isSystemSettings(applicationContext, foreground) -> 0L
            else -> LEAVE_CONFIRM_MILLIS
        }
        if (now - pendingLeaveSince < grace) {
            // [诊断]
            Log.d(
                "XianLock",
                "eval fg=$foreground -> WAIT ${now - pendingLeaveSince}/$grace" +
                    " overlay=${isShowing()}"
            )
            return
        }
        // 走到这里说明「不在白名单」已经持续够久了，但**触发它的那个包名本身未必可信**。
        //
        // 除用户真实的切换之外的另一种可能是：某个一闪而过、抢了一下焦点的系统层报了它的包名。
        // 手势过渡层已经在 SYSTEM_UI_PACKAGES 里忽略过一批（vivo upslide），但**包名黑名单补不完**
        // —— 横屏下又会冒出新的（旋转相关的系统层、厂商游戏助手之类），而且这类层出现时
        // 用户往往压根没离开白名单应用。表现就是「我明明在用白名单里的应用，横屏就被盖了」。
        //
        // 所以真盖之前再问一次**免疫源**：它只看真正的应用窗口，上面那类浮层在它眼里
        // 根本不存在，而用户真正切走的应用会立刻成为活跃的应用窗口。
        // 只要它说现在的前台在放行名单里，就按「没离开」处理。
        //
        // 两个源按可靠性排：
        // ① 无障碍窗口栈 —— 免权限，只认 TYPE_APPLICATION 的活跃窗口，浮层天然免疫；
        //    但它依赖无障碍服务活着，服务被 ROM 清掉时拿不到（返回 null，不否决）。
        // ② 系统使用记录 —— 只记 Activity 的 RESUMED，同样免疫浮层，也不依赖服务；
        //    但要「使用情况访问」权限，用户没授时永远拿不到。
        // 两路都 null ⇒ 不否决，行为与改动前完全一致（漏锁比误锁…这里反过来了：
        // 误锁的代价是被堵在锁机页，漏盖的代价最多晚一两秒，所以宁可多问一层）。
        val resumed = FocusLockAccessibilityService.foregroundFromWindowStack()
            ?: runCatching { UsageForeground.lastResumed(applicationContext) }.getOrNull()
        if (resumed != null && LockMachineController.isAllowed(applicationContext, resumed)) {
            // [诊断]
            Log.d("XianLock", "eval fg=$foreground -> 否决盖屏（免疫源=$resumed 在放行名单里）")
            pendingLeaveSince = 0L
            // 万一这一层已经因为同一个假前台盖上去了，也得撤掉 —— 否则它会一直挂着出不来
            // （没盖着时 hide 自己会立刻返回，不用先判 isShowing）
            hide(applicationContext, force = true)
            return
        }
        // [诊断]
        Log.d("XianLock", "eval fg=$foreground -> SHOW (overlay was ${isShowing()})")
        show(applicationContext)
    }

    /** 前台是不是系统桌面。桌面包名拿不到时返回 false —— 不做宽限，按原样立刻盖。 */
    private fun isHome(context: Context, packageName: String): Boolean {
        val home = homePackage ?: runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()?.also { homePackage = it }
        return home == packageName
    }

    /** 供起锁路径调用（见 `LockMachineService`）：自己看一眼前台是谁，然后走 [evaluate]。 */
    fun sync(context: Context) {
        val applicationContext = context.applicationContext
        if (!LockMachineController.isActive(applicationContext)) {
            hide(applicationContext, force = true)
            return
        }
        evaluate(applicationContext, ForegroundApp.resolve(applicationContext))
    }

    @Suppress("DEPRECATION")
    fun show(context: Context) {
        val applicationContext = context.applicationContext
        // tick 靠它问「锁机还在不在、屏幕可用没」，层没有时也不能丢 —— 见 lastContext 注释
        lastContext = applicationContext
        if (overlayView != null) {
            updateContent(applicationContext)
            return
        }
        if (System.currentTimeMillis() - lastHideAt < DEBOUNCE_MILLIS) {
            // [诊断]
            Log.d("XianLock", "show -> 被防抖吞掉（距上次 hide ${System.currentTimeMillis() - lastHideAt}ms）")
            return
        }
        // [诊断]
        Log.d("XianLock", "show -> addView 锁机层")
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 悬浮窗只能靠 application context 渲染，拿到的是清单里的默认主题；
        // 套一层用户当前主题，铺底的主色才跟设置里选的一致。
        val view = LayoutInflater.from(ThemeStore.wrap(applicationContext))
            .inflate(R.layout.overlay_lock_machine, null, false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 锁机层永远按竖屏呈现，横屏也不例外。
        //
        // 这层的版式是竖排、固定高度堆叠的（标题 → 时钟 → 文案 → 白名单 → 三个按钮），
        // 竖屏下正好一屏放下；横屏可用高度只剩三分之一，内容从「白名单」往下全部溢出到
        // 屏幕之外 —— 实测横屏时整层只剩 4 行文字可见，「回贤 / 清后台 / 长按退出」三个按钮
        // 一个都 dump 不到（在 y > 屏幕高度 处）。而浮窗不是 ScrollView，用户滑也滑不动，
        // 表现就是「横屏被锁住之后，退不出去」。
        //
        // 给窗口而不是给 Activity 指定方向：WMS 计算屏幕方向时会优先采用非 Activity 窗口
        // 的 screenOrientation（getOrientationFromWindowsLocked），所以这一行会让锁机期间
        // 整块屏幕回到竖屏，锁机页按它本来的竖排版式铺满 —— 用户要的就是「锁机是竖屏的」。
        // 层移除后这个请求随之消失，屏幕方向回到由前台应用决定。
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // 退出密码面板会带出输入法：让窗口重排，别把卡片挡在键盘后面
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        view.findViewById<Button>(R.id.exitLockButton).setOnClickListener {
            Toast.makeText(applicationContext, R.string.long_press_required, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.backToAppButton).setOnClickListener {
            // 「回贤」是用户明确的意图，不必等任何前台感知：贤自己永远在放行名单里，
            // 感知失灵时（无障碍没开、服务被 ROM 清掉）那条路永远不会来，
            // 用户就只能对着一层盖在自己应用上的遮罩发愣 —— 那就是「卡住」。
            ForegroundApp.mark(applicationContext.packageName)
            hide(applicationContext, force = true)
            try {
                applicationContext.startActivity(
                    Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            } catch (_: Exception) {
            }
        }
        // 锁机期间一键清后台：把第三方应用全踢掉（贤自己和系统应用不动）。
        view.findViewById<Button>(R.id.clearBackgroundButton).setOnClickListener {
            clearBackgroundApps(applicationContext)
            val feedback = applicationContext.getString(R.string.cleared_background_apps)
            Toast.makeText(applicationContext, feedback, Toast.LENGTH_SHORT).show()
            // 结果反馈必须画在锁机层自己身上：锁机时贤没有任何前台 Activity，
            // 是后台应用 —— vivo 这类 ROM 会静默丢弃后台 Toast，用户点完什么都
            // 看不到，还以为没清。把按钮文字临时换成结果，2 秒后还原。
            (it as Button).text = feedback
            handler.removeCallbacks(restoreClearButtonLabel)
            handler.postDelayed(restoreClearButtonLabel, CLEAR_FEEDBACK_MILLIS)
        }
        // 长按退出 → 先过 30 秒冷静期弹窗；冷静期满且本月额度没用完才真的退
        view.findViewById<Button>(R.id.exitLockButton).setOnLongClickListener {
            showExitConfirm(view)
            true
        }
        view.findViewById<Button>(R.id.cancelExitButton).setOnClickListener {
            hideExitConfirm(view)
        }
        view.findViewById<Button>(R.id.confirmExitButton).setOnClickListener {
            if (System.currentTimeMillis() < cooldownEndsAt) return@setOnClickListener
            // 密码锁夹在冷静期之后、真退出之前：冷静期拦冲动，密码拦「我自己」
            LockPinPanel.show(view, applicationContext, PinScope.EXIT_LOCK) { exitNow(applicationContext) }
        }
        view.findViewById<View>(R.id.whitelistFolderRow).setOnClickListener {
            whitelistExpanded = !whitelistExpanded
            applyWhitelistVisibility(view)
        }

        // alpha 先归零再 addView，否则会闪一帧全不透明
        view.alpha = 0f
        try {
            windowManager.addView(view, params)
            overlayView = view
            lastShowAt = System.currentTimeMillis()
            cooldownEndsAt = 0L
            whitelistExpanded = false
            // 文案只在「新建这一层」时取一次：放进 updateContent 会在每次应用切换事件里重抽，
            // 用户会看到文案一秒一跳。
            LockQuotes.applyTo(view, applicationContext)
            view.animate().alpha(1f).setDuration(220L).start()
            updateContent(applicationContext)
            keepTicking = true
            handler.post(tickRunnable)
        } catch (_: Exception) {
        }
    }

    /**
     * 收起覆盖层。
     *
     * [force] 给「调用方已经确定该收起来」的场景用（用户切进了白名单应用、锁机结束）——
     * 那种判断比防抖可靠，不能被吞掉。防抖只服务于一个目的：刚 addView 的那一瞬间，
     * 系统会为我们自己的悬浮窗补一个窗口事件，那个自事件会把层当成「切到了贤」而撤掉。
     */
    fun hide(context: Context, force: Boolean = false) {
        lastContext = context.applicationContext
        val view = overlayView ?: return
        if (!force && System.currentTimeMillis() - lastShowAt < DEBOUNCE_MILLIS) return
        // [诊断]
        Log.d("XianLock", "hide -> removeView 锁机层 (force=$force)")
        overlayView = null
        lastHideAt = System.currentTimeMillis()
        cooldownEndsAt = 0L
        // 这层没了，列表也跟着没了：下次新建必须重建，别被旧指纹判成「没变」
        whitelistSignature = -1
        // ⚠️ **不要** removeCallbacks(tickRunnable)：层被收起不等于锁机结束 ——
        // 息屏/锁屏让开、切进白名单让开都会走到这里，而那两种情况下 tick 是唯一
        // 会把层盖回来的东西。让它自己按 keepTicking 决定停不停（锁机结束那一刻才停）。
        handler.removeCallbacks(restoreClearButtonLabel)
        val applicationContext = context.applicationContext
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    /**
     * 清掉后台的第三方应用，返回清掉的数量。
     *
     * 用 [ActivityManager.killBackgroundProcesses]：这是不需要系统权限、普通应用唯一能用的
     * 「清后台」API。⚠️ Android 8 之后它只能清掉应用的**缓存进程**，常驻服务 / 推送拉起的
     * 进程杀不掉，重开只是冷启 —— 这是系统层面的限制，第三方「一键清理」类应用效果也一样，
     * 想真杀得系统签名或用 shizuku 那类提权。
     *
     * 两类一律跳过：
     *  · 贤自己 —— 锁机层挂在本应用的前台服务上，把自己杀掉锁机就断了；
     *  · 系统应用 —— 清掉 systemui 会连状态栏和手势一起搞坏，桌面进程也在其中。
     */
    /**
     * 锁机期间一键清后台：把第三方应用整个踢一遍（贤自己和系统应用不动）。
     *
     * **刻意不返回数量** —— `killBackgroundProcesses` 不回传任何结果，而"后台到底有几个应用"
     * 在 Android 上也问不到：`getRunningAppProcesses()` 从 5.1 起只返回调用者自己的进程，
     * 使用记录要 `PACKAGE_USAGE_STATS` 权限，`/proc` 读不到别的 uid。
     * 早先这里返回的是**遍历过的应用个数**（≈ 用户装了多少个三方应用），跟真正清掉几个毫无关系 ——
     * 后台只有几个也会报「已清理 100 多个」。宁可只说"已清理"，不报一个编出来的数。
     */
    private fun clearBackgroundApps(context: Context) {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val self = context.packageName
        for (app in context.packageManager.getInstalledApplications(0)) {
            if (app.packageName == self) continue
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            activityManager.killBackgroundProcesses(app.packageName)
        }
    }

    private fun showExitConfirm(view: View) {
        cooldownEndsAt = System.currentTimeMillis() + LockExitQuota.COOLDOWN_SECONDS * 1000L
        updateCooldownText(view)
        view.findViewById<View>(R.id.exitConfirmOverlay).visibility = View.VISIBLE
    }

    private fun hideExitConfirm(view: View) {
        cooldownEndsAt = 0L
        view.findViewById<View>(R.id.exitConfirmOverlay).visibility = View.GONE
    }

    /** 冷静期已过、密码也输对了，才真的退出 —— 最后还要过本月退出额度这一关。 */
    private fun exitNow(context: Context) {
        if (LockExitQuota.canExit(context)) {
            LockMachineService.stop(context)
            hide(context)
            return
        }
        // 额度用完是最容易让人以为「坏了」的一步：只弹一个 Toast 的话，用户点完
        // 「确认退出」看到的就是弹窗原样还在、什么也没发生 —— 也就是「卡住」。
        // 把话写在弹窗里，并停掉冷静期的每秒刷新（否则下一秒就被倒计时覆盖回去）。
        cooldownEndsAt = 0L
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.cooldownText).text =
            context.getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT)
        view.findViewById<Button>(R.id.confirmExitButton).isEnabled = false
    }

    /**
     * 白名单内容指纹（排序后的包名集合的 hashCode）；-1 表示当前这层还没建过列表。
     *
     * `show()` 在「层已经存在」时只调 `updateContent()`，而它会被**每一次切换应用事件**触发。
     * 每建一次列表就是 `removeAllViews()` + 每行重新 `loadLabel`/`loadIcon`，全在主线程上。
     * 不拦的话，用户展开白名单的那一刻正好有事件进来，列表就被推倒重来：闪烁 + 滚动归位 ——
     * 这就是「打开白名单一点都不流畅」。
     */
    private var whitelistSignature = -1

    private fun updateContent(context: Context) {
        val view = overlayView ?: return
        view.findViewById<FlipClockView>(R.id.remainingText)
            .setDisplay(LockMachineController.remainingText(context))
        updateClockText(context, view)
        rebuildWhitelistIfChanged(context, view)
    }

    /** 白名单内容没变就一个像素都不动；变了（或这层是新建的）才重建适配器。 */
    private fun rebuildWhitelistIfChanged(context: Context, view: View) {
        val grid = view.findViewById<RecyclerView>(R.id.whitelistGrid)
        val folderRow = view.findViewById<View>(R.id.whitelistFolderRow)
        val folderCount = view.findViewById<TextView>(R.id.whitelistFolderCount)
        val emptyView = view.findViewById<TextView>(R.id.emptyWhitelistText)

        val whitelist = LockMachineController.whitelist(context).toList()
        if (whitelist.sorted().hashCode() == whitelistSignature) return
        whitelistSignature = whitelist.sorted().hashCode()
        if (whitelist.isEmpty()) {
            folderRow.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            grid.visibility = View.GONE
            return
        }
        folderRow.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        folderCount.text = context.getString(R.string.whitelist_count_format, whitelist.size)
        applyWhitelistVisibility(view)

        val packageManager = context.packageManager
        // 白名单是 Set，直接遍历顺序随机；按应用名（中文拼音）排一遍，跟选择器里的顺序保持一致。
        val apps = whitelist.mapNotNull { packageName ->
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@mapNotNull null
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: Exception) {
                null
            }
            Triple(packageName, launchIntent, appInfo)
        }.sortedWith(
            compareBy(WhitelistAppPicker.LABEL_ORDER) {
                it.third?.loadLabel(packageManager)?.toString() ?: it.first
            }
        ).map { (packageName, launchIntent, appInfo) ->
            OverlayApp(
                packageName = packageName,
                launchIntent = launchIntent,
                label = appInfo?.loadLabel(packageManager)?.toString() ?: packageName,
                icon = appInfo?.let { runCatching { it.loadIcon(packageManager) }.getOrNull() }
            )
        }
        grid.layoutManager = GridLayoutManager(view.context, AppGridAdapter.SPAN)
        grid.adapter = OverlayAppAdapter(apps)
    }

    private fun applyWhitelistVisibility(view: View) {
        val grid = view.findViewById<RecyclerView>(R.id.whitelistGrid)
        val arrow = view.findViewById<TextView>(R.id.whitelistFolderArrow)
        if (whitelistExpanded) {
            // 展开加一点淡入 + 上移：网格高度是 0dp+weight，直接 VISIBLE 会「啪」地把整块
            // 区域瞬间顶满，看着就是「不流畅」。收起不做动画 —— 收起来用户视线已经离开。
            grid.visibility = View.VISIBLE
            grid.alpha = 0f
            grid.translationY = -8f * view.resources.displayMetrics.density
            grid.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        } else {
            grid.visibility = View.GONE
        }
        arrow.text = if (whitelistExpanded) "▴" else "▾"
    }
}

/** 锁机层白名单里的一格：点一下就启动它。 */
private data class OverlayApp(
    val packageName: String,
    val launchIntent: Intent,
    val label: String,
    val icon: Drawable?
)

/**
 * 锁机层的白名单网格适配器。
 *
 * 不复用设置页那个 [AppGridAdapter]：那个管的是「勾选」，这里管的是「点一下启动应用」，
 * 台账不一样，硬凑成一个类反而两边都要加开关。
 */
private class OverlayAppAdapter(private val apps: List<OverlayApp>) :
    RecyclerView.Adapter<OverlayAppAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemOverlayAppGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(apps[position])

    override fun getItemCount(): Int = apps.size

    class Holder(private val binding: ItemOverlayAppGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: OverlayApp) {
            binding.overlayAppName.text = app.label
            binding.overlayAppIcon.setImageDrawable(app.icon)
            binding.root.setOnClickListener {
                val context = binding.root.context
                // 点白名单里的应用 = 用户明确要去用它。这一下必须让开，绝不能等前台感知 ——
                // 感知断了（无障碍没开 / 服务被 ROM 清掉 / 使用记录没授权）时它永远不会来，
                // 用户就会看着应用启动了、遮罩还盖在上面，也就是「明明在白名单里却被卡住」。
                // mark 是给感知补一条「我确定用户去了这个包」：tick 和守护服务的每秒自检
                // 都读它，所以遮罩不会在下一秒又被盖回来。
                ForegroundApp.mark(app.packageName)
                LockMachineOverlayController.hide(context, force = true)
                try {
                    context.startActivity(
                        app.launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
