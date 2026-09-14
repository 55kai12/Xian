package com.xian.focus

import android.content.Context

/**
 * 「退出锁机」要输的密码。
 *
 * 和 [AppLockStore] 那套「打开贤要密码」故意分开存：那个一旦设了，每次进贤都被拦；
 * 这个只在长按退出锁机的那一步用。两件事，别互相牵连。
 *
 * 存储挂在 `lock_machine_prefs`（锁机设置那一份）上，不另开 prefs 文件：
 * 清数据与备份导出都是按文件名走的，少一个文件就少一处会漏的地方；
 * 顺带这份数据不参与备份导出，密码哈希不会跟着导出文件流出去。
 */
object LockExitPin {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_PIN_HASH = "exit_pin_hash"
    private const val KEY_ENABLED = "exit_pin_enabled"

    /** 统一 4 位数字，跟打开贤的那个密码保持一致，省得用户记两套规则。 */
    const val PIN_LENGTH = 4

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 开关开着**且**确实设过密码才算启用。
     * 只有开关没有密码等于没锁 —— 这种状态绝不能装成「已启用」，
     * 否则用户以为锁上了，实际长按就能退。
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false) && hasPin(context)

    fun hasPin(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    /** 设密码即启用：用户肯花一步设密码，意图已经够明确了。 */
    fun setPin(context: Context, pin: String) {
        prefs(context).edit()
            .putString(KEY_PIN_HASH, PinHash.sha256(pin))
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    /** 关开关**不清密码** —— 下次想开回来不用重新设一遍。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun check(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) == PinHash.sha256(pin)
}
