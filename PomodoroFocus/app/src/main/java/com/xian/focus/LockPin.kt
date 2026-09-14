package com.xian.focus

import android.content.Context

/**
 * 密码锁要管哪一处。
 *
 * 两处共用**同一套 4 位密码**，各自一个开关 —— 想只用在其中一处就只拨一个。
 */
enum class PinScope { EXIT_LOCK, APP_LIMIT }

/**
 * 密码锁：一套 4 位数字密码，可选择用在哪些地方（见 [PinScope]）。
 *
 * 和 [AppLockStore] 那套「打开贤要密码」故意分开存：那个一旦设了，每次进贤都被拦；
 * 这一套只管锁机退出与应用限额加时。两件事，别互相牵连。
 *
 * 存储挂在 `lock_machine_prefs`（锁机设置那一份）上，不另开 prefs 文件：
 * 清数据与备份导出都是按文件名走的，少一个文件就少一处会漏的地方；
 * 顺带这份数据不参与备份导出，密码哈希不会跟着导出文件流出去。
 */
object LockPin {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_PIN_HASH = "exit_pin_hash"

    /**
     * 键名是 v2.0.44 留下的（当时密码只用在退出锁机），现在它表示「退出锁机」这个作用域。
     * **别顺手改名**：老用户的开关注销，密码就白设了。
     */
    private const val KEY_EXIT_LOCK = "exit_pin_enabled"
    private const val KEY_APP_LIMIT = "app_limit_pin_enabled"

    /** 统一 4 位数字，跟打开贤的那个密码保持一致，省得用户记两套规则。 */
    const val PIN_LENGTH = 4

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPin(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    /**
     * 这个场合要不要输密码：**设过密码** 且 **该场合的开关开着**。
     *
     * 只有开关没有密码等于没锁 —— 这种状态绝不能装成「已启用」，
     * 否则用户以为锁上了，实际长按就能退。
     */
    fun isRequired(context: Context, scope: PinScope): Boolean =
        hasPin(context) && prefs(context).getBoolean(keyOf(scope), false)

    /** 关开关**不清密码** —— 下次想开回来不用重新设一遍。 */
    fun setRequired(context: Context, scope: PinScope, required: Boolean) {
        prefs(context).edit().putBoolean(keyOf(scope), required).apply()
    }

    /**
     * 只设密码，**不动任何作用域开关**：拨开关的那一方（[LockSettingsFragment]）知道
     * 用户拨的是哪一个，由它把那个作用域打开。这里顺手打开某个的话，
     * 用户为了 A 处设的密码会悄悄把 B 处也锁上 —— 而用户根本没拨过 B。
     */
    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN_HASH, PinHash.sha256(pin)).apply()
    }

    fun check(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) == PinHash.sha256(pin)

    private fun keyOf(scope: PinScope) = when (scope) {
        PinScope.EXIT_LOCK -> KEY_EXIT_LOCK
        PinScope.APP_LIMIT -> KEY_APP_LIMIT
    }
}
