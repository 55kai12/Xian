package com.xian.focus

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * 锁机期间也绝不能挡住的系统组件。
 *
 * 锁机遮罩是无差别的全屏悬浮窗，旧实现只放行"白名单应用 + 自己"，
 * 于是这些关键界面也被盖住了：
 * - 来电界面 / 通话界面被盖住 → 接不了电话（紧急情况下是安全问题）
 * - 输入法被盖住 → 白名单应用里根本打不了字，白名单形同虚设
 *
 * 这里给出一个最小豁免集合，与用户白名单取并集。
 */
object SystemAppAllowlist {

    private val ESSENTIAL_PACKAGES = setOf(
        // 系统框架本身（运行时权限弹窗、关机/重启菜单等）
        "android",
        // 状态栏 / 通知栏 / 全屏来电提醒 / 锁屏
        "com.android.systemui",
        // 电话与来电
        "com.android.phone",
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        // 权限控制器
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // 常见输入法兜底；正常路径靠系统当前输入法动态识别，这里只防万一
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.baidu.input",
        "com.tencent.qqpinyin",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod.miui",
        "com.netease.nim.input"
    )

    /** 判断某个包名是否属于"即使锁机也必须放行"的系统组件。 */
    fun isEssential(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName in ESSENTIAL_PACKAGES) return true
        return packageName in inputMethodPackages(context)
    }

    /** 动态识别当前输入法：不同机型/用户安装的输入法包名无法穷举。 */
    private fun inputMethodPackages(context: Context): Set<String> {
        val packages = mutableSetOf<String>()
        runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { packages += it }
        }
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            @Suppress("DEPRECATION")
            imm?.enabledInputMethodList?.forEach { info -> packages += info.packageName }
        }
        return packages
    }
}
