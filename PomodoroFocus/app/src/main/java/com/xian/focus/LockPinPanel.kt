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

        // 悬浮窗拿不到 Activity 那套输入法联动，键盘得自己叫
        input.requestFocus()
        input.post {
            runCatching {
                (root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
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
        val input = root.findViewById<EditText>(R.id.exitPinInput) ?: return
        runCatching {
            (root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
    }
}
