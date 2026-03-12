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
     * @param skillNames 已保存技巧列表，每项为 "应用名 (包名)"，用于注入供模型选择是否 load_skills
     */
    fun buildActionPrompt(
        userCommand: String,
        screenshotWidth: Int,
        screenshotHeight: Int,
        uiTree: String? = null,
        skillNames: List<String> = emptyList()
    ): String {
        val template = getPrompt(DefaultPrompts.PROMPT_KEY_ACTION)
        val uiSection = if (uiTree != null) {
            "## 屏幕元素（#N = 可交互元素编号，用 tap_element/input_element 操作）\n$uiTree"
        } else {
            "## 屏幕元素\n控件树不可用，请根据截图用 tap(x,y) 操作（坐标基于截图像素）"
        }
        val skillsSection = formatSkillsSection(skillNames)
        return template
            .replace("{user_command}", userCommand)
            .replace("{screenshot_width}", screenshotWidth.toString())
            .replace("{screenshot_height}", screenshotHeight.toString())
            .replace("{skills_section}", skillsSection)
            .replace("{ui_tree_section}", uiSection)
    }

    private fun formatSkillsSection(skillNames: List<String>): String {
        return if (skillNames.isEmpty()) {
            "## 已保存技巧\n当前无已保存技巧。\n"
        } else {
            buildString {
                appendLine("## 已保存技巧（若与当前小目标或即将进入的应用相关，请先调用 load_skills 加载）")
                skillNames.forEach { appendLine("· $it") }
                appendLine()
            }
        }
    }

    /**
     * Build a follow-up user message appended after tool execution with a fresh screenshot.
     * @param skillNames 已保存技巧列表，每项为 "应用名 (包名)"，每个小目标开始时注入供模型选择是否 load_skills
     */
    fun buildFollowUpPrompt(
        screenshotWidth: Int,
        screenshotHeight: Int,
        uiTree: String? = null,
        skillNames: List<String> = emptyList()
    ): String {
        val uiSection = if (uiTree != null) {
            "## 屏幕元素（#N = 可交互元素编号，用 tap_element/input_element 操作）\n$uiTree"
        } else {
            "## 屏幕元素\n控件树不可用，请根据截图用 tap(x,y) 操作（坐标基于截图像素）"
        }
        val skillsSection = formatSkillsSection(skillNames)
        return buildString {
            appendLine("以上是操作后的屏幕截图。")
            appendLine("截图分辨率: ${screenshotWidth}x${screenshotHeight}")
            appendLine()
            append(skillsSection)
            append(uiSection)
        }
    }
}
