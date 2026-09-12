package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xian.focus.databinding.ItemWhitelistAppBinding
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

    private val tickRunnable = object : Runnable {
        override fun run() {
            val view = overlayView ?: return
            val context = view.context.applicationContext
            if (!LockMachineController.isActive(context)) {
                hide(context)
                return
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
        view.findViewById<TextView>(R.id.exitQuotaText).text =
            context.getString(
                R.string.exit_quota_format,
                LockExitQuota.remaining(context),
                LockExitQuota.MONTHLY_LIMIT
            )
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
        view.findViewById<Button>(R.id.exitLockButton).setOnClickListener {
            Toast.makeText(applicationContext, R.string.long_press_required, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.backToAppButton).setOnClickListener {
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
            if (LockExitQuota.canExit(applicationContext)) {
                LockMachineService.stop(applicationContext)
                hide(applicationContext)
            } else {
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(
                        R.string.exit_quota_exhausted,
                        LockExitQuota.MONTHLY_LIMIT
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
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
            view.animate().alpha(1f).setDuration(220L).start()
            updateContent(applicationContext)
            handler.post(tickRunnable)
        } catch (_: Exception) {
        }
    }

    fun hide(context: Context) {
        val view = overlayView ?: return
        if (System.currentTimeMillis() - lastShowAt < DEBOUNCE_MILLIS) return
        overlayView = null
        lastHideAt = System.currentTimeMillis()
        cooldownEndsAt = 0L
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

    private fun updateContent(context: Context) {
        val view = overlayView ?: return
        view.findViewById<FlipClockView>(R.id.remainingText)
            .setDisplay(LockMachineController.remainingText(context))
        updateClockText(context, view)

        val container = view.findViewById<LinearLayout>(R.id.whitelistContainer)
        val folderRow = view.findViewById<View>(R.id.whitelistFolderRow)
        val folderCount = view.findViewById<TextView>(R.id.whitelistFolderCount)
        val emptyView = view.findViewById<TextView>(R.id.emptyWhitelistText)
        val scrollView = view.findViewById<ScrollView>(R.id.whitelistScroll)
        container.removeAllViews()

        val whitelist = LockMachineController.whitelist(context).toList()
        if (whitelist.isEmpty()) {
            folderRow.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            return
        }
        folderRow.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        folderCount.text = context.getString(R.string.whitelist_count_format, whitelist.size)
        applyWhitelistVisibility(view)

        val packageManager = context.packageManager
        val inflater = LayoutInflater.from(view.context)
        whitelist.forEach { packageName ->
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return@forEach
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: Exception) {
                null
            }
            val row = ItemWhitelistAppBinding.inflate(inflater, container, false)
            row.whitelistAppName.text =
                appInfo?.loadLabel(packageManager)?.toString() ?: packageName
            appInfo?.loadIcon(packageManager)?.let { row.whitelistAppIcon.setImageDrawable(it) }
            row.root.setOnClickListener {
                try {
                    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
            container.addView(row.root)
        }
    }

    private fun applyWhitelistVisibility(view: View) {
        view.findViewById<ScrollView>(R.id.whitelistScroll).visibility =
            if (whitelistExpanded) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.whitelistFolderArrow).text =
            if (whitelistExpanded) "▴" else "▾"
    }
}
