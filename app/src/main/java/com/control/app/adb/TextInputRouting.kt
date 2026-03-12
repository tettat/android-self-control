package com.control.app.adb

internal enum class TextInputRoute {
    ADB_KEYBOARD,
    ASCII_SHELL,
    ACCESSIBILITY,
    CLIPBOARD
}

internal data class TextInputCapabilities(
    val hasAdbKeyboard: Boolean,
    val hasAccessibilityService: Boolean
)

internal fun planTextInputRoutes(
    text: String,
    capabilities: TextInputCapabilities
): List<TextInputRoute> {
    if (text.isEmpty()) return emptyList()

    if (capabilities.hasAdbKeyboard) {
        return listOf(TextInputRoute.ADB_KEYBOARD)
    }

    if (text.isAsciiOnly()) {
        return listOf(TextInputRoute.ASCII_SHELL)
    }

    if (capabilities.hasAccessibilityService) {
        return listOf(TextInputRoute.ACCESSIBILITY, TextInputRoute.CLIPBOARD)
    }

    return listOf(TextInputRoute.CLIPBOARD)
}

internal fun String.isAsciiOnly(): Boolean = all { it.code in 0..127 }
