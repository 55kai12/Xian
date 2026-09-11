package com.xian.focus

import android.content.Context
import java.security.MessageDigest

object AppLockStore {
    private const val PREFS_NAME = "app_lock_prefs"
    private const val KEY_PIN_HASH = "pin_hash"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN_HASH, sha256(pin)).apply()
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_PIN_HASH).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) == sha256(pin)

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
