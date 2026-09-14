package com.xian.focus

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * 「退出锁机前先输密码」的面板控制逻辑，两个锁机覆盖层共用
 * （面板本体：res/layout/view_exit_pin_panel.xml，靠 <include> 内嵌进覆盖层）。
 */
@SuppressLint("InflateParams")
object LockExitPinPanel {

    /**
     * 启用了密码就弹面板，输对了才调 [onVerified]；没启用则直接放行。
     *
     * 开关判断收在这里只有一处，调用方不必各自再判一遍 ——
     * 「某条退出路径忘了看开关」正是这类功能最容易出的漏。
     */
    fun show(root: View, context: Context, onVerified: () -> Unit) {
        if (!LockExitPin.isEnabled(context)) {
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

        // 长度只在 LockExitPin 里定一次，XML 不再重复写一个数字
        input.filters = arrayOf(InputFilter.LengthFilter(LockExitPin.PIN_LENGTH))
        input.setText("")
        error?.visibility = View.GONE
        panel.visibility = View.VISIBLE
        panel.findViewById<View>(R.id.exitPinCancel).setOnClickListener { hide(root) }
        panel.findViewById<View>(R.id.exitPinConfirm).setOnClickListener {
            if (LockExitPin.check(context, input.text.toString())) {
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
