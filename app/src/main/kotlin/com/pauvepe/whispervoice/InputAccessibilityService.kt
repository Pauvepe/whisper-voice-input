package com.pauvepe.whispervoice

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InputAccessibilityService : AccessibilityService() {

    companion object {
        var instance: InputAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No active event handling needed
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun insertText(text: String) {
        val node = findFocusedInputNode() ?: return

        val current = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                current + text
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
    }

    private fun findFocusedInputNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
}
