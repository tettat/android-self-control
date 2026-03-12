package com.control.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiTreeAccessibilityService : AccessibilityService() {

    data class AccessibilityNodeSnapshot(
        val packageName: String,
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val className: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class AccessibilityTreeSnapshot(
        val packageName: String,
        val nodes: List<AccessibilityNodeSnapshot>
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    private fun captureSnapshotInternal(): AccessibilityTreeSnapshot? {
        val root = rootInActiveWindow ?: return null
        return try {
            val packageName = root.packageName?.toString().orEmpty()
            val nodes = mutableListOf<AccessibilityNodeSnapshot>()
            collectNodes(root, nodes)
            if (nodes.isEmpty()) {
                null
            } else {
                AccessibilityTreeSnapshot(
                    packageName = packageName,
                    nodes = nodes
                )
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeSnapshot>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out += AccessibilityNodeSnapshot(
            packageName = node.packageName?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            focusable = node.isFocusable,
            focused = node.isFocused,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectNodes(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun setTextOnFocusedInputInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = findEditableTarget(root) ?: return false
            try {
                if (!target.isFocused) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                if (!target.isFocused) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }

                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findEditableTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (isEditableCandidate(focused)) {
                return focused
            }
            focused.recycle()
        }

        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { focused ->
            if (isEditableCandidate(focused)) {
                return focused
            }
            focused.recycle()
        }

        return findFirstEditableDescendant(root)
    }

    private fun findFirstEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableCandidate(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                val match = findFirstEditableDescendant(child)
                if (match != null) {
                    return match
                }
            } finally {
                child.recycle()
            }
        }

        return null
    }

    private fun isEditableCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEnabled &&
            node.isVisibleToUser &&
            (node.isEditable || className.endsWith("EditText") || className.endsWith("TextView") && node.isFocusable)
    }

    companion object {
        @Volatile
        private var instance: UiTreeAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun captureSnapshot(): AccessibilityTreeSnapshot? = instance?.captureSnapshotInternal()

        fun setTextOnFocusedInput(text: String): Result<String> = runCatching {
            val service = instance ?: error("Accessibility service is not connected")
            check(service.setTextOnFocusedInputInternal(text)) {
                "No editable focused input is available for accessibility text injection"
            }
            "input via accessibility: $text"
        }

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val component = ComponentName(context, UiTreeAccessibilityService::class.java).flattenToString()
            return enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
