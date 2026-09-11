package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class FocusLockAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val context = applicationContext

        if (LockMachineController.isActive(context)) {
            if (LockMachineController.isAllowed(context, packageName)) {
                if (LockMachineOverlayController.isShowing()) {
                    LockMachineOverlayController.hide(context)
                }
            } else {
                LockMachineOverlayController.show(context)
            }
            return
        } else if (LockMachineOverlayController.isShowing()) {
            LockMachineOverlayController.hide(context)
        }

        // 番茄钟锁机状态改由持久化数据推导，进程被杀后重新拉起依然生效
        if (!LockController.isLockActive(context)) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
            return
        }
        val isSelf = packageName == context.packageName
        // 系统必需组件（来电界面、输入法、状态栏）必须放行，
        // 否则锁机期间接不了电话，白名单应用里也无法输入文字。
        val isSystemEssential = SystemAppAllowlist.isEssential(context, packageName)
        if (isSelf || isSystemEssential) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
        } else {
            LockOverlayController.show(context)
        }
    }

    override fun onInterrupt() = Unit
}
