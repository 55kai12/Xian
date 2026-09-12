package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.xian.focus.service.FocusTimerService
import dagger.hilt.android.EntryPointAccessors

@SuppressLint("StaticFieldLeak", "InflateParams")
object LockOverlayController {
    private var overlayView: View? = null

    fun isShowing(): Boolean = overlayView != null

    @Suppress("DEPRECATION")
    fun show(context: Context) {
        if (overlayView != null) return
        val applicationContext = context.applicationContext
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 同 LockMachineOverlayController：悬浮窗拿不到 Activity 主题，套上用户当前色相
        val view = LayoutInflater.from(ThemeStore.wrap(applicationContext))
            .inflate(R.layout.overlay_lock, null, false)
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
        view.findViewById<Button>(R.id.exitLockButton).setOnLongClickListener {
            exitLock(applicationContext)
            true
        }
        // alpha 先归零再 addView，否则会闪一帧全不透明
        view.alpha = 0f
        try {
            windowManager.addView(view, params)
            overlayView = view
            view.animate().alpha(1f).setDuration(220L).start()
        } catch (_: Exception) {
        }
    }

    fun hide(context: Context) {
        val view = overlayView ?: return
        overlayView = null
        val applicationContext = context.applicationContext
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun exitLock(context: Context) {
        val engine = EntryPointAccessors.fromApplication(
            context,
            LockEntryPoint::class.java
        ).timerEngine()
        engine.setLockMode(false)
        engine.resetTimer()
        FocusTimerService.stop(context)
        hide(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
