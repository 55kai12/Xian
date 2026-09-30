package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * 应用限额用完后盖在被限制应用上的那层。
 *
 * 和锁机覆盖层同款做法：TYPE_APPLICATION_OVERLAY 全屏遮挡 + ThemeStore.wrap 套用户主题。
 * 次日 0 点用量清零后由 ticker 自动移除，用户不需要任何操作。
 */
@SuppressLint("StaticFieldLeak", "InflateParams")
object AppLimitOverlayController {
    private var overlayView: View? = null
    private var showingPackage: String? = null

    /**
     * 「返回桌面」的宽限期（见 [mayAutoShow]）。给**滞后**留的：桌面的前台信息可能晚 1~2 秒才到
     * （使用记录入库延迟、或窗口事件被厂商过渡层顶掉），这期间读到的前台还是被限应用。
     *
     * 刻意只有 1.5 秒：这窗口里用户若马上切回被限应用，层会晚一拍才盖上 ——
     * 拿「最多 1.5 秒的迟盖」换掉「刚收起又弹回来」，这个代价才划算。
     */
    private const val DISMISS_GRACE_MILLIS = 1_500L

    /** 用户最后一次点「返回桌面」的包名与时刻。 */
    private var dismissedPackage: String? = null
    private var dismissedAt = 0L

    fun isShowing(): Boolean = overlayView != null

    fun show(context: Context, packageName: String) {
        // 同一个应用已经盖着就不用重来，否则每 tick 都会重建一次 view
        if (overlayView != null && showingPackage == packageName) return
        val appContext = context.applicationContext
        hideNow(appContext)

        val pm = appContext.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        val view = LayoutInflater.from(ThemeStore.wrap(appContext))
            .inflate(R.layout.overlay_app_limit, null, false)
        view.findViewById<ImageView>(R.id.limitAppIcon).setImageDrawable(
            runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
        )
        view.findViewById<TextView>(R.id.limitAppName).text =
            appContext.getString(R.string.app_limit_overlay_title, label)
        view.findViewById<View>(R.id.limitBonusButton).setOnClickListener {
            requestBonus(appContext, view, packageName)
        }
        view.findViewById<View>(R.id.limitHomeButton).setOnClickListener {
            goHome(appContext, packageName)
        }
        updateDetail(appContext, view, packageName)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点：这一层只是「看」，被限的应用该一直是那个活跃的应用窗口，
            // 计时与「什么时候该撤层」都靠前台应用判断，别让这层把自己变成前台。
            // 触摸照旧（要屏蔽触摸得用 NOT_TOUCHABLE），返回键 / Home 键直达系统与应用。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 密码面板会带出输入法：让窗口重排，别把卡片挡在键盘后面
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.addView(view, params)
            overlayView = view
            showingPackage = packageName
            // 只有真的盖上去了才算「被锁一次」；AppLimitStats 内部按「应用 × 天」去重
            AppLimitStats.recordLock(appContext, packageName)
        } catch (_: Exception) {
        }
    }

    fun hide(context: Context) {
        if (overlayView == null) return
        hideNow(context.applicationContext)
    }

    /**
     * 「返回桌面」按钮：**先收层，再回桌面**，两件事都得做。
     *
     * ⚠️ 只发 HOME intent 是不够的。这层是 `TYPE_APPLICATION_OVERLAY`，**浮在桌面之上** ——
     * 光回桌面它照样盖着整个屏幕，而它的撤销时机完全交给 2 秒一次的轮询，
     * 用户体感就是「按了返回桌面没反应」。
     *
     * 更关键的是**必须把两个前台缓存一起作废**：系统把「桌面 resumed」写进使用记录有延迟
     * （几百毫秒到几秒），下一拍轮询读到的前台**还是那个被限应用** ⇒
     * `applyOverlay()` 判定「还在被限应用里」⇒ `show()` ⇒ 刚收起的层又被盖回来。
     * 这就是「点了返回桌面，它又弹一次」的来源。
     *
     * 作废之后那一拍前台是「不知道」：只收不盖，也不记账（宁可少记不虚增），
     * 等真实记录落库再按新的前台判定。
     */
    private fun goHome(appContext: Context, packageName: String) {
        hideNow(appContext)
        dismissedPackage = packageName
        dismissedAt = SystemClock.elapsedRealtime()
        // 两条驱动链各有一份前台缓存，谁是当前驱动方都要作废 —— 只清一份会漏。
        AppLimitWatcher.invalidateForeground()
        FocusLockAccessibilityService.invalidateForeground()
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 自动重盖前的闸 —— 只压「刚被主动收起的那一个应用」。
     *
     * 光作废前台缓存还不够：无障碍那条链作废后会**立刻重查一次窗口栈**
     * （[FocusLockAccessibilityService.foregroundPackageForTick]），而那一瞬桌面可能还没接管，
     * 查回来仍是被限应用 ⇒ 层又被盖上。所以这里再留一小段宽限。
     *
     * ⚠️ **到期必须自动失效，不许改成永久**；前台一旦确认换成别的应用也立刻作废 ——
     * 否则用户从桌面点回被限应用时会被误压，等于把这个 bug 修成「永远不拦」，比原样更糟。
     */
    fun mayAutoShow(foreground: String?): Boolean {
        val pkg = dismissedPackage ?: return true
        if (foreground != null && foreground != pkg) {
            dismissedPackage = null      // 用户确实离开了，标记作废
            return true
        }
        return SystemClock.elapsedRealtime() - dismissedAt >= DISMISS_GRACE_MILLIS
    }

    /** 会随时间变的部分：今日可用额度（含加时）与加时按钮。 */
    private fun updateDetail(appContext: Context, view: View, packageName: String) {
        view.findViewById<TextView>(R.id.limitAppDetail).text = appContext.getString(
            R.string.app_limit_overlay_detail,
            AppLimitStore.effectiveLimitMinutes(appContext, packageName)
        )
        val remaining = AppLimitStore.bonusRemaining(appContext)
        val bonusButton = view.findViewById<TextView>(R.id.limitBonusButton)
        bonusButton.visibility = if (remaining > 0) View.VISIBLE else View.GONE
        if (remaining > 0) {
            bonusButton.text = appContext.getString(
                R.string.app_limit_bonus_button,
                AppLimitStore.BONUS_MINUTES,
                remaining
            )
        }
    }

    /**
     * 加时前先过密码（设置里开了「应用限额需要密码」时）。
     *
     * 这一层默认是 `FLAG_NOT_FOCUSABLE`（见 [show] 里的注释），而输密码必须能拿到焦点，
     * 所以只为密码面板临时把焦点放开，收起面板立刻还回去 —— 常态下仍不跟被限应用抢焦点。
     */
    private fun requestBonus(appContext: Context, view: View, packageName: String) {
        if (!LockPin.isRequired(appContext, PinScope.APP_LIMIT)) {
            applyBonus(appContext, view, packageName)
            return
        }
        setFocusable(appContext, true)
        LockPinPanel.show(
            root = view,
            context = appContext,
            scope = PinScope.APP_LIMIT,
            onCancel = { setFocusable(appContext, false) }
        ) {
            setFocusable(appContext, false)
            applyBonus(appContext, view, packageName)
        }
    }

    private fun setFocusable(appContext: Context, focusable: Boolean) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching {
            (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, params)
        }
    }

    private fun applyBonus(appContext: Context, view: View, packageName: String) {
        if (!AppLimitStore.consumeBonus(appContext, packageName)) {
            Toast.makeText(appContext, R.string.app_limit_bonus_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (AppLimitStore.isLocked(appContext, packageName)) {
            // 超得太多，加这一次也不够 —— 留在这一页继续刷新，别假装放行
            updateDetail(appContext, view, packageName)
        } else {
            hideNow(appContext)
        }
    }

    private fun hideNow(appContext: Context) {
        val view = overlayView ?: return
        overlayView = null
        showingPackage = null
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }
}
