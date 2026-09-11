package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class FocusLockAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        if (LockMachineController.isActive(applicationContext)) {
            if (LockMachineController.isAllowed(applicationContext, packageName)) {
                if (LockMachineOverlayController.isShowing()) {
                    LockMachineOverlayController.hide(applicationContext)
                }
            } else {
                LockMachineOverlayController.show(applicationContext)
            }
            return
        } else if (LockMachineOverlayController.isShowing()) {
            LockMachineOverlayController.hide(applicationContext)
        }

        if (!LockController.isLockActive) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(applicationContext)
            }
            return
        }
        if (packageName == applicationContext.packageName) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(applicationContext)
            }
        } else {
            LockOverlayController.show(applicationContext)
        }
    }

    override fun onInterrupt() = Unit
}
