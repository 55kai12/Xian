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
            applyBonus(appContext, view, packageName)
        }
        view.findViewById<View>(R.id.limitHomeButton).setOnClickListener {
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
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
