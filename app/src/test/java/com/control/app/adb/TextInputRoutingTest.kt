package com.control.app.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputRoutingTest {

    @Test
    fun asciiTextUsesShellInputWhenNoSpecialUnicodeSupportIsNeeded() {
        val routes = planTextInputRoutes(
            text = "hello world",
            capabilities = TextInputCapabilities(
                hasAdbKeyboard = false,
                hasAccessibilityService = true
            )
        )

        assertEquals(listOf(TextInputRoute.ASCII_SHELL), routes)
    }

    @Test
    fun unicodeTextPrefersAccessibilityBeforeClipboard() {
        val routes = planTextInputRoutes(
            text = "你好，世界",
            capabilities = TextInputCapabilities(
                hasAdbKeyboard = false,
                hasAccessibilityService = true
            )
        )

        assertEquals(
            listOf(TextInputRoute.ACCESSIBILITY, TextInputRoute.CLIPBOARD),
            routes
        )
    }

    @Test
    fun unicodeTextFallsBackToClipboardWithoutAccessibility() {
        val routes = planTextInputRoutes(
            text = "你好，世界",
            capabilities = TextInputCapabilities(
                hasAdbKeyboard = false,
                hasAccessibilityService = false
            )
        )

        assertEquals(listOf(TextInputRoute.CLIPBOARD), routes)
    }

    @Test
    fun adbKeyboardTakesPriorityForUnicodeText() {
        val routes = planTextInputRoutes(
            text = "你好，世界",
            capabilities = TextInputCapabilities(
                hasAdbKeyboard = true,
                hasAccessibilityService = true
            )
        )

        assertEquals(listOf(TextInputRoute.ADB_KEYBOARD), routes)
    }
}
