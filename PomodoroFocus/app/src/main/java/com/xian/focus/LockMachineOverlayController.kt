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

@SuppressLint("StaticFieldLeak", "InflateParams")
object LockMachineOverlayController {
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastShowAt = 0L
    private var lastHideAt = 0L
    private const val DEBOUNCE_MILLIS = 350L
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
            handler.postDelayed(this, 1_000L)
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
        view.findViewById<Button>(R.id.exitLockButton).setOnLongClickListener {
            LockMachineService.stop(applicationContext)
            hide(applicationContext)
            true
        }
        // alpha 先归零再 addView，否则会闪一帧全不透明
        view.alpha = 0f
        try {
            windowManager.addView(view, params)
            overlayView = view
            lastShowAt = System.currentTimeMillis()
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
        handler.removeCallbacks(tickRunnable)
        val applicationContext = context.applicationContext
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun updateContent(context: Context) {
        val view = overlayView ?: return
        view.findViewById<FlipClockView>(R.id.remainingText)
            .setDisplay(LockMachineController.remainingText(context))

        val container = view.findViewById<LinearLayout>(R.id.whitelistContainer)
        val emptyView = view.findViewById<TextView>(R.id.emptyWhitelistText)
        val scrollView = view.findViewById<ScrollView>(R.id.whitelistScroll)
        container.removeAllViews()

        val whitelist = LockMachineController.whitelist(context).toList()
        if (whitelist.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        scrollView.visibility = View.VISIBLE

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
}
