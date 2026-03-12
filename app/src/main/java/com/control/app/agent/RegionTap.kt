package com.control.app.agent

import java.util.Locale
import kotlin.math.roundToInt

internal enum class RegionAnchor(
    val apiName: String,
    val xFraction: Float,
    val yFraction: Float,
    private val aliases: Set<String>
) {
    TOP_LEFT(
        apiName = "top_left",
        xFraction = 1f / 6f,
        yFraction = 1f / 6f,
        aliases = setOf("left_top", "upper_left", "左上", "左上角")
    ),
    TOP(
        apiName = "top",
        xFraction = 0.5f,
        yFraction = 1f / 6f,
        aliases = setOf("upper", "顶部", "上方", "上边")
    ),
    TOP_RIGHT(
        apiName = "top_right",
        xFraction = 5f / 6f,
        yFraction = 1f / 6f,
        aliases = setOf("right_top", "upper_right", "右上", "右上角")
    ),
    LEFT(
        apiName = "left",
        xFraction = 1f / 6f,
        yFraction = 0.5f,
        aliases = setOf("靠左", "左侧", "左边", "最左")
    ),
    CENTER(
        apiName = "center",
        xFraction = 0.5f,
        yFraction = 0.5f,
        aliases = setOf("centre", "middle", "中央", "中心", "中间", "正中", "居中")
    ),
    RIGHT(
        apiName = "right",
        xFraction = 5f / 6f,
        yFraction = 0.5f,
        aliases = setOf("靠右", "右侧", "右边", "最右")
    ),
    BOTTOM_LEFT(
        apiName = "bottom_left",
        xFraction = 1f / 6f,
        yFraction = 5f / 6f,
        aliases = setOf("left_bottom", "lower_left", "左下", "左下角")
    ),
    BOTTOM(
        apiName = "bottom",
        xFraction = 0.5f,
        yFraction = 5f / 6f,
        aliases = setOf("lower", "底部", "下方", "下边", "底边")
    ),
    BOTTOM_RIGHT(
        apiName = "bottom_right",
        xFraction = 5f / 6f,
        yFraction = 5f / 6f,
        aliases = setOf("right_bottom", "lower_right", "右下", "右下角")
    );

    companion object {
        val apiNames: List<String> = entries.map { it.apiName }

        fun fromInput(raw: String?): RegionAnchor? {
            val normalized = normalizeAnchor(raw) ?: return null
            return entries.firstOrNull { anchor ->
                normalized == anchor.apiName || normalized in anchor.aliases
            }
        }

        private fun normalizeAnchor(raw: String?): String? = raw
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?.takeIf { it.isNotEmpty() }
    }
}

internal object RegionTapResolver {
    fun resolveAnchor(
        explicitAnchor: String?,
        description: String?
    ): RegionAnchor {
        return RegionAnchor.fromInput(explicitAnchor)
            ?: inferFromDescription(description)
            ?: RegionAnchor.CENTER
    }

    fun inferFromDescription(description: String?): RegionAnchor? {
        val text = description
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        if (containsAny(text, "右下角", "右下", "bottom right", "bottom-right", "lower right")) {
            return RegionAnchor.BOTTOM_RIGHT
        }
        if (containsAny(text, "左下角", "左下", "bottom left", "bottom-left", "lower left")) {
            return RegionAnchor.BOTTOM_LEFT
        }
        if (containsAny(text, "右上角", "右上", "top right", "top-right", "upper right")) {
            return RegionAnchor.TOP_RIGHT
        }
        if (containsAny(text, "左上角", "左上", "top left", "top-left", "upper left")) {
            return RegionAnchor.TOP_LEFT
        }

        val mentionsBottom = containsAny(text, "底部", "下方", "下边", "底边", "bottom", "lower")
        val mentionsTop = containsAny(text, "顶部", "上方", "上边", "top", "upper")
        val mentionsLeft = containsAny(text, "左侧", "左边", "靠左", "最左", "left")
        val mentionsRight = containsAny(text, "右侧", "右边", "靠右", "最右", "right")
        val mentionsCenter = containsAny(text, "中心", "中央", "中间", "正中", "居中", "center", "centre", "middle")

        return when {
            mentionsBottom && mentionsRight -> RegionAnchor.BOTTOM_RIGHT
            mentionsBottom && mentionsLeft -> RegionAnchor.BOTTOM_LEFT
            mentionsTop && mentionsRight -> RegionAnchor.TOP_RIGHT
            mentionsTop && mentionsLeft -> RegionAnchor.TOP_LEFT
            mentionsBottom -> RegionAnchor.BOTTOM
            mentionsTop -> RegionAnchor.TOP
            mentionsLeft -> RegionAnchor.LEFT
            mentionsRight -> RegionAnchor.RIGHT
            mentionsCenter -> RegionAnchor.CENTER
            else -> null
        }
    }

    fun calculateTapPoint(
        zone: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        anchor: RegionAnchor = RegionAnchor.CENTER
    ): Pair<Int, Int> {
        require(zone in 1..9) { "zone must be 1-9, got $zone" }

        val col = (zone - 1) % 3
        val row = (zone - 1) / 3
        val cellWidth = viewportWidth / 3f
        val cellHeight = viewportHeight / 3f
        val cellLeft = offsetX + cellWidth * col
        val cellTop = offsetY + cellHeight * row
        val x = (cellLeft + cellWidth * anchor.xFraction).roundToInt()
        val y = (cellTop + cellHeight * anchor.yFraction).roundToInt()
        return Pair(x, y)
    }

    private fun containsAny(text: String, vararg candidates: String): Boolean {
        return candidates.any { candidate -> text.contains(candidate) }
    }
}
