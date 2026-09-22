package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * 「要密码」的那个面板，三个场合共用：两个锁机覆盖层（长按退出）与应用限额覆盖层（额度用完后加时）。
 *
 * 面板本体：res/layout/view_exit_pin_panel.xml（**文件名的 exit 是 v2.0.44 留下的**，
 * 里面的 id 也还是 `exitPin*`，现在它已经是通用的密码面板了），靠 <include> 内嵌进覆盖层。
 *
 * 面板自己不认识「退出」「加时」这些事：输对了就回调，干什么由调用方决定；
 * 标题 / 说明 / 按钮文案按 [PinScope] 换一套。
 */
@SuppressLint("InflateParams")
object LockPinPanel {

    /**
     * 这个场合启用了密码就弹面板，输对了才调 [onVerified]；没启用则直接放行。
     *
     * 开关判断收在这里只有一处，调用方不必各自再判一遍 ——
     * 「某条退出路径忘了看开关」正是这类功能最容易出的漏。
     *
     * [onCancel] 给调用方收拾「为输密码而临时改过的窗口状态」用（如应用限额层临时开放焦点）。
     */
    fun show(
        root: View,
        context: Context,
        scope: PinScope,
        onCancel: () -> Unit = {},
        onVerified: () -> Unit
    ) {
        if (!LockPin.isRequired(context, scope)) {
            onVerified()
            return
        }
        val panel = root.findViewById<View>(R.id.exitPinOverlay)
        val input = root.findViewById<EditText>(R.id.exitPinInput)
        if (panel == null || input == null) {
            // 面板没内嵌进来就当没这功能，别把用户困在锁机里出不去
            onVerified()
            return
        }
        val error = root.findViewById<TextView>(R.id.exitPinError)

        // 长度只在 LockPin 里定一次，XML 不再重复写一个数字
        input.filters = arrayOf(InputFilter.LengthFilter(LockPin.PIN_LENGTH))
        input.setText("")
        error?.visibility = View.GONE
        applyScopeText(panel, scope)
        panel.visibility = View.VISIBLE
        panel.findViewById<View>(R.id.exitPinCancel).setOnClickListener {
            hide(root)
            onCancel()
        }
        panel.findViewById<View>(R.id.exitPinConfirm).setOnClickListener {
            if (LockPin.check(context, input.text.toString())) {
                hide(root)
                onVerified()
            } else {
                error?.visibility = View.VISIBLE
                input.setText("")
            }
        }

        // 悬浮窗拿不到 Activity 那套输入法联动，键盘得自己叫。
        //
        // ⚠️ v2.0.94：`SHOW_IMPLICIT` 对**非 Activity 的悬浮窗**不可靠 —— 它按「用户是否
        // 有输入意图」来判断，而浮窗里 `requestFocus()` 不一定被系统认成输入意图，
        // 国产 ROM 上多半直接不弹（用户以为密码框坏了）。改 `SHOW_FORCED`：明说「就要弹」。
        // 再补一次 `restartInput`，让输入法重新拿一次焦点窗口 —— 浮窗的 windowToken
        // 是刚刚才 addView 上去的，输入法可能还挂着上一个应用的连接，不重启就不认。
        input.requestFocus()
        input.post {
            runCatching {
                val imm = root.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
                imm.restartInput(input)
            }
        }
        liftPanelAboveKeyboard(root, panel)
    }

    /**
     * 键盘弹起来时把整块面板往上顶，别让「确认 / 取消」被键盘压住。
     *
     * 为什么不能只靠窗口的 `SOFT_INPUT_ADJUST_RESIZE`：锁机层的窗口带了
     * `FLAG_LAYOUT_NO_LIMITS`（横屏铺满必需，删不掉），而它让窗口**不受 IME 影响**，
     * ADJUST_RESIZE 于是形同失效。国产 ROM 对浮窗本来也常不回传 IME 高度。
     *
     * 所以自己量：根视图可见高度比总高度矮了一截，那截就是键盘。把它的一半当作
     * 面板上移量 —— 面板是居中的，上移「键盘高度」会让它贴到屏幕顶，上移一半正好
     * 让卡片主体落在键盘上方那块可见区里。
     *
     * 监听器挂在 root 的 viewTreeObserver 上，随面板一起被移除（root 就是覆盖层自己），
     * 不需要额外清理；面板没显示时可见高度不会变化，也就不会误顶。
     */
    private fun liftPanelAboveKeyboard(root: View, panel: View) {
        val card = (panel as? android.view.ViewGroup)?.getChildAt(0) ?: return
        // 每块面板只挂一个监听器：取消/关闭后再打开是同一个 root，重复挂会叠加位移。
        if (root.getTag(R.id.exitPinOverlay) != null) return
        root.setTag(R.id.exitPinOverlay, true)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val keyboard = (root.height - rect.height()).coerceAtLeast(0)
            // 高度变化不足 15% 的不当键盘（状态栏刷新、导航栏切换都会抖一下）
            val shift = if (keyboard * 5 > root.height) keyboard / 2f else 0f
            card.translationY = -shift
        }
    }

    /** 同一块面板三个场合共用，文案按作用域换 —— 在限额层里说「才能退出锁机」是要用户猜。 */
    private fun applyScopeText(panel: View, scope: PinScope) {
        val (titleRes, bodyRes, confirmRes) = when (scope) {
            PinScope.EXIT_LOCK -> Triple(
                R.string.exit_pin_title,
                R.string.exit_pin_body,
                R.string.exit_pin_confirm
            )
            PinScope.APP_LIMIT -> Triple(
                R.string.app_limit_pin_title,
                R.string.app_limit_pin_body,
                R.string.app_limit_pin_confirm
            )
        }
        panel.findViewById<TextView>(R.id.pinPanelTitle).setText(titleRes)
        panel.findViewById<TextView>(R.id.pinPanelBody).setText(bodyRes)
        panel.findViewById<TextView>(R.id.exitPinConfirm).setText(confirmRes)
    }

    fun hide(root: View) {
        val panel = root.findViewById<View>(R.id.exitPinOverlay) ?: return
        panel.visibility = View.GONE
        // 键盘避让的位移要复位 —— 不复位下次打开面板还顶在半空中（见 liftPanelAboveKeyboard）。
        (panel as? android.view.ViewGroup)?.getChildAt(0)?.translationY = 0f
        val input = root.findViewById<EditText>(R.id.exitPinInput) ?: return
        runCatching {
            (root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
    }
}
