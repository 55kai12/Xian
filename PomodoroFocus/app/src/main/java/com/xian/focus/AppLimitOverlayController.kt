package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView

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
        view.findViewById<TextView>(R.id.limitAppDetail).text = appContext.getString(
            R.string.app_limit_overlay_detail,
            AppLimitStore.limitMinutes(appContext, packageName)
        )
        view.findViewById<View>(R.id.limitHomeButton).setOnClickListener {
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

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
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.addView(view, params)
            overlayView = view
            showingPackage = packageName
        } catch (_: Exception) {
        }
    }

    fun hide(context: Context) {
        if (overlayView == null) return
        hideNow(context.applicationContext)
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
