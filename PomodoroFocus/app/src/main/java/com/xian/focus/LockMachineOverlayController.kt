package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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

    /** 退出冷静期截止时刻；0 表示弹窗没开着。 */
    private var cooldownEndsAt = 0L

    /** 白名单文件夹展开状态；悬浮窗每次重建都回到收起。 */
    private var whitelistExpanded = false

    /**
     * 落在桌面上之后，先宽限这么久不盖。
     *
     * 桌面是「过渡态」，不是「用户跑别处玩去了」。手势导航下从屏幕边缘往里滑就是返回，
     * 用户在白名单应用的首页再滑一次，整个应用就退到桌面了 —— 而他本意往往只是返回上一页。
     * 这一刻立刻盖上来，体验就是「轻轻碰一下就被锁住」，还得展开白名单重新点一次才回得去。
     *
     * 宽限期内回到任何白名单应用都完全不盖；点开别的应用仍然立刻盖（那时前台已不是桌面）。
     */
    private const val HOME_GRACE_MILLIS = 2_500L

    /** 系统桌面（launcher）包名，懒查一次；查不到为 null，此时不做宽限（按原样立刻盖）。 */
    private var homePackage: String? = null

    /** 落在桌面上的起始时刻；0 表示当前不在桌面上。 */
    private var onHomeSince = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            val view = overlayView ?: return
            val context = view.context.applicationContext
            if (!LockMachineController.isActive(context)) {
                hide(context, force = true)
                return
            }
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
            handler.postDelayed(this, 1_000L)
        }
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
            hide(applicationContext, force = true)
            return
        }
        // 退出流程进行中不对账：冷静期弹窗 / 密码面板开着时用户正在跟这层交互，
        // 输入法一弹出来前台就变成输入法，而输入法属于永远放行的系统组件 ——
        // 少了这道闸，用户刚长按完「退出锁机」，整层就连着面板一起被撤掉。
        if (cooldownEndsAt > 0L) return
        if (foreground != null && LockMachineController.isAllowed(applicationContext, foreground)) {
            onHomeSince = 0L
            hide(applicationContext, force = true)
            return
        }
        if (foreground != null && isHome(applicationContext, foreground)) {
            val now = System.currentTimeMillis()
            if (onHomeSince == 0L) onHomeSince = now
            if (now - onHomeSince < HOME_GRACE_MILLIS) {
                // 宽限中：层保持让开，用户随时能点回白名单应用
                hide(applicationContext, force = true)
                return
            }
        } else {
            onHomeSince = 0L
        }
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
        if (overlayView != null) {
            updateContent(applicationContext)
            return
        }
        if (System.currentTimeMillis() - lastHideAt < DEBOUNCE_MILLIS) return
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
        val view = overlayView ?: return
        if (!force && System.currentTimeMillis() - lastShowAt < DEBOUNCE_MILLIS) return
        overlayView = null
        lastHideAt = System.currentTimeMillis()
        cooldownEndsAt = 0L
        // 这层没了，列表也跟着没了：下次新建必须重建，别被旧指纹判成「没变」
        whitelistSignature = -1
        handler.removeCallbacks(tickRunnable)
        val applicationContext = context.applicationContext
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
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
