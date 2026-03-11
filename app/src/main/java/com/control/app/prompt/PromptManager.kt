package com.control.app.prompt

import android.content.Context
import android.content.SharedPreferences

class PromptManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("prompts", Context.MODE_PRIVATE)

    fun getPrompt(key: String): String {
        return prefs.getString(key, null) ?: DefaultPrompts.ALL_DEFAULTS[key] ?: ""
    }

    fun setPrompt(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun resetPrompt(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun resetAllPrompts() {
        prefs.edit().clear().apply()
    }

    fun isCustomized(key: String): Boolean {
        return prefs.contains(key)
    }

    fun getAllPromptKeys(): List<String> {
        return DefaultPrompts.ALL_DEFAULTS.keys.toList()
    }

    /**
     * Build the initial user message for a new task.
     * Used only for the first message in a tool-calling conversation.
     */
    fun buildActionPrompt(
        userCommand: String,
        screenshotWidth: Int,
        screenshotHeight: Int,
        uiTree: String? = null
    ): String {
        val template = getPrompt(DefaultPrompts.PROMPT_KEY_ACTION)
        val uiSection = if (uiTree != null) {
            "## 屏幕元素（#N = 可交互元素编号，用 tap_element/input_element 操作）\n$uiTree"
        } else {
            "## 屏幕元素\n控件树不可用，请根据截图用 tap(x,y) 操作（坐标基于截图像素）"
        }
        return template
            .replace("{user_command}", userCommand)
            .replace("{screenshot_width}", screenshotWidth.toString())
            .replace("{screenshot_height}", screenshotHeight.toString())
            .replace("{ui_tree_section}", uiSection)
    }

    /**
     * Build a follow-up user message appended after tool execution with a fresh screenshot.
     */
    fun buildFollowUpPrompt(
        screenshotWidth: Int,
        screenshotHeight: Int,
        uiTree: String? = null
    ): String {
        val uiSection = if (uiTree != null) {
            "## 屏幕元素（#N = 可交互元素编号，用 tap_element/input_element 操作）\n$uiTree"
        } else {
            "## 屏幕元素\n控件树不可用，请根据截图用 tap(x,y) 操作（坐标基于截图像素）"
        }
        return buildString {
            appendLine("以上是操作后的屏幕截图。")
            appendLine("截图分辨率: ${screenshotWidth}x${screenshotHeight}")
            appendLine()
            append(uiSection)
        }
    }
}
