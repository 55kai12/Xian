package com.xian.focus

import java.security.MessageDigest

/**
 * PIN 的哈希。
 *
 * 两个 PIN 共用这一份实现：打开「贤」的密码（[AppLockStore]）和锁机/限额的密码（[LockPin]）。
 * 各写一遍 MessageDigest 迟早会在某次改动里跑偏，两边校验结果就对不上了。
 */
object PinHash {
    fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
