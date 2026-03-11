package com.control.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI function-calling tool definitions for the agent.
 * Each tool is a JSON object matching the OpenAI tools API format.
 */
object ToolDefinitions {

    val AGENT_TOOLS: JsonArray = buildJsonArray {
        add(buildTool(
            name = "tap_element",
            description = "点击编号元素。优先使用此操作，最准确。",
            required = listOf("element"),
            properties = {
                put("element", buildJsonObject {
                    put("type", "integer")
                    put("description", "元素编号（如UI列表中的 #3 则填 3）")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "input_element",
            description = "在编号输入框中输入文字（自动点击获焦再输入）。",
            required = listOf("element", "text"),
            properties = {
                put("element", buildJsonObject {
                    put("type", "integer")
                    put("description", "输入框元素编号")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "要输入的文字")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "tap",
            description = "点击截图上的指定坐标。当目标不在元素列表中时使用。坐标基于截图像素。",
            required = listOf("x", "y"),
            properties = {
                put("x", buildJsonObject {
                    put("type", "integer")
                    put("description", "X坐标（截图像素）")
                })
                put("y", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y坐标（截图像素）")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "swipe",
            description = "从一个坐标滑动到另一个坐标。坐标基于截图像素。",
            required = listOf("startX", "startY", "endX", "endY"),
            properties = {
                put("startX", buildJsonObject {
                    put("type", "integer")
                    put("description", "起始X坐标")
                })
                put("startY", buildJsonObject {
                    put("type", "integer")
                    put("description", "起始Y坐标")
                })
                put("endX", buildJsonObject {
                    put("type", "integer")
                    put("description", "终点X坐标")
                })
                put("endY", buildJsonObject {
                    put("type", "integer")
                    put("description", "终点Y坐标")
                })
                put("duration", buildJsonObject {
                    put("type", "integer")
                    put("description", "滑动时长（毫秒），默认300")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "launch_app",
            description = "通过包名启动应用。打开App请用此工具，不要点桌面图标。",
            required = listOf("package_name"),
            properties = {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "应用包名，如 com.tencent.mm")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "press_back",
            description = "按安卓返回键。",
            required = emptyList(),
            properties = {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "press_home",
            description = "按安卓Home键。",
            required = emptyList(),
            properties = {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "scroll_down",
            description = "向下滚动屏幕。",
            required = emptyList(),
            properties = {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "scroll_up",
            description = "向上滚动屏幕。",
            required = emptyList(),
            properties = {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "input_text",
            description = "直接输入文字（假设输入框已获焦）。如果需要先点击输入框，请用 input_element。",
            required = listOf("text"),
            properties = {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "要输入的文字")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "key_event",
            description = "发送Android按键事件。",
            required = listOf("keyCode"),
            properties = {
                put("keyCode", buildJsonObject {
                    put("type", "integer")
                    put("description", "Android KeyEvent代码")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "adb_shell",
            description = """执行任意ADB shell命令，非常强大。能用adb直接完成的事就不要绕弯点UI。
例如: 打开URL用 am start -a android.intent.action.VIEW -d "https://..."
拨打电话用 am start -a android.intent.action.CALL -d tel:10086
查看当前Activity用 dumpsys activity activities | grep mResumedActivity""",
            required = listOf("command"),
            properties = {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "要执行的shell命令")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "zoom_region",
            description = "放大截图的某个区域以看清细节。屏幕被分为9个区域（1-9，从左到右从上到下排列：第1行123，第2行456，第3行789）。可以连续放大直到看清目标。放大后可以用 tap_region 点击该区域内的子区域。",
            required = listOf("region"),
            properties = {
                put("region", buildJsonObject {
                    put("type", "integer")
                    put("description", "区域编号 1-9")
                })
            }
        ))

        add(buildTool(
            name = "tap_region",
            description = "点击当前视图中某个区域的正中央。屏幕/放大区域被分为9个子区域（1-9，从左到右从上到下：第1行123，第2行456，第3行789）。先用 zoom_region 放大到目标区域，再用此工具精确点击。",
            required = listOf("region"),
            properties = {
                put("region", buildJsonObject {
                    put("type", "integer")
                    put("description", "要点击的区域编号 1-9")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "wait",
            description = "等待指定时间，让页面加载或动画完成。",
            required = emptyList(),
            properties = {
                put("duration", buildJsonObject {
                    put("type", "integer")
                    put("description", "等待时长（毫秒），默认1000")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "操作说明")
                })
            }
        ))

        add(buildTool(
            name = "complete",
            description = "任务完成或无法完成时调用此工具。调用后任务结束。",
            required = listOf("message"),
            properties = {
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "给用户的结果消息")
                })
                put("success", buildJsonObject {
                    put("type", "boolean")
                    put("description", "是否成功完成，默认true")
                })
            }
        ))

        add(buildTool(
            name = "load_skills",
            description = "加载指定应用的使用技巧和操作经验。在进入某个App操作前调用，获取之前学到的操作方法。",
            required = listOf("package_name"),
            properties = {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "应用包名")
                })
            }
        ))

        add(buildTool(
            name = "save_skill",
            description = "保存一个应用的操作技巧，供下次操作该App时参考。当你发现了有效的操作方法、解决了一个问题、或学到了某个App的特殊操作方式时调用。",
            required = listOf("package_name", "app_name", "tip"),
            properties = {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "应用包名")
                })
                put("app_name", buildJsonObject {
                    put("type", "string")
                    put("description", "应用名称（如'微信'）")
                })
                put("tip", buildJsonObject {
                    put("type", "string")
                    put("description", "操作技巧描述，应该具体且可复用")
                })
            }
        ))

        add(buildTool(
            name = "detect_current_app",
            description = "检测当前前台运行的App包名。用于确认当前处于哪个应用。",
            required = emptyList(),
            properties = {}
        ))
    }

    /**
     * Build a single tool definition in OpenAI function-calling format.
     */
    private fun buildTool(
        name: String,
        description: String,
        required: List<String>,
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject(properties))
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray {
                        required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            })
        })
    }
}
