package com.control.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

internal class GestureCueOverlayView(context: Context) : View(context) {

    private sealed interface Cue {
        data class Tap(val x: Float, val y: Float) : Cue
        data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : Cue
        data class Sequence(val points: List<Pair<Float, Float>>) : Cue
    }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 152, 0)
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private var cue: Cue? = null
    private var cueProgress = 0f
    private var fadeProgress = 0f
    private var animator: ValueAnimator? = null

    fun showTapCue(x: Float, y: Float, holdMs: Long, fadeMs: Long) {
        startCue(Cue.Tap(x, y), holdMs, fadeMs)
    }

    fun showSwipeCue(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdMs: Long,
        fadeMs: Long
    ) {
        startCue(Cue.Swipe(startX, startY, endX, endY), holdMs, fadeMs)
    }

    fun showSequenceCue(points: List<Pair<Float, Float>>, holdMs: Long, fadeMs: Long) {
        startCue(Cue.Sequence(points), holdMs, fadeMs)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentCue = cue ?: return
        val alphaScale = 1f - fadeProgress
        if (alphaScale <= 0f) return

        when (currentCue) {
            is Cue.Tap -> drawTapCue(canvas, currentCue.x, currentCue.y, alphaScale)
            is Cue.Swipe -> drawSwipeCue(
                canvas = canvas,
                points = listOf(
                    currentCue.startX to currentCue.startY,
                    currentCue.endX to currentCue.endY
                ),
                alphaScale = alphaScale,
                drawNumbers = false
            )
            is Cue.Sequence -> drawSwipeCue(
                canvas = canvas,
                points = currentCue.points,
                alphaScale = alphaScale,
                drawNumbers = true
            )
        }
    }

    private fun startCue(nextCue: Cue, holdMs: Long, fadeMs: Long) {
        animator?.cancel()
        cue = nextCue
        cueProgress = 0f
        fadeProgress = 0f
        alpha = 1f
        visibility = View.VISIBLE

        val safeHoldMs = holdMs.coerceAtLeast(300L)
        val safeFadeMs = fadeMs.coerceAtLeast(200L)
        val totalMs = safeHoldMs + safeFadeMs
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalMs
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                val overall = valueAnimator.animatedFraction
                val holdFraction = safeHoldMs.toFloat() / totalMs.toFloat()
                cueProgress = if (holdFraction <= 0f) 1f else (overall / holdFraction).coerceIn(0f, 1f)
                fadeProgress = if (overall <= holdFraction) {
                    0f
                } else {
                    ((overall - holdFraction) / (1f - holdFraction)).coerceIn(0f, 1f)
                }
                invalidate()
            }
            doOnEnd {
                cue = null
                cueProgress = 0f
                fadeProgress = 0f
                visibility = View.GONE
                invalidate()
            }
            start()
        }
    }

    private fun drawTapCue(canvas: Canvas, x: Float, y: Float, alphaScale: Float) {
        haloPaint.color = Color.argb((60 * alphaScale).toInt(), 255, 213, 79)
        val pulse = 0.55f + 0.45f * cueProgress
        canvas.drawCircle(x, y, 64f * density * pulse, haloPaint)

        ringPaint.color = Color.argb((255 * alphaScale).toInt(), 255, 202, 40)
        canvas.drawCircle(x, y, 22f * density, ringPaint)
        canvas.drawCircle(x, y, 46f * density * pulse, ringPaint)

        fillPaint.color = Color.argb((235 * alphaScale).toInt(), 255, 152, 0)
        canvas.drawCircle(x, y, 8f * density, fillPaint)
        canvas.drawLine(x - 28f * density, y, x + 28f * density, y, ringPaint)
        canvas.drawLine(x, y - 28f * density, x, y + 28f * density, ringPaint)
    }

    private fun drawSwipeCue(
        canvas: Canvas,
        points: List<Pair<Float, Float>>,
        alphaScale: Float,
        drawNumbers: Boolean
    ) {
        if (points.size < 2) return

        linePaint.color = Color.argb((225 * alphaScale).toInt(), 255, 193, 7)
        ringPaint.color = Color.argb((255 * alphaScale).toInt(), 255, 238, 88)
        fillPaint.color = Color.argb((220 * alphaScale).toInt(), 255, 152, 0)
        haloPaint.color = Color.argb((55 * alphaScale).toInt(), 255, 193, 7)

        points.zipWithNext().forEach { (start, end) ->
            canvas.drawLine(start.first, start.second, end.first, end.second, linePaint)
        }

        points.forEachIndexed { index, (x, y) ->
            val haloRadius = if (index == points.lastIndex) 34f * density else 24f * density
            canvas.drawCircle(x, y, haloRadius, haloPaint)
            canvas.drawCircle(x, y, 16f * density, fillPaint)
            canvas.drawCircle(x, y, 22f * density, ringPaint)
            if (drawNumbers) {
                val baseline = y - (numberPaint.descent() + numberPaint.ascent()) / 2f
                canvas.drawText((index + 1).toString(), x, baseline, numberPaint)
            }
        }

        val tail = points[points.lastIndex - 1]
        val head = points.last()
        val angle = kotlin.math.atan2(head.second - tail.second, head.first - tail.first)
        val arrowLength = 20f * density
        val spread = 0.6f
        canvas.drawLine(
            head.first,
            head.second,
            head.first - arrowLength * kotlin.math.cos(angle - spread).toFloat(),
            head.second - arrowLength * kotlin.math.sin(angle - spread).toFloat(),
            linePaint
        )
        canvas.drawLine(
            head.first,
            head.second,
            head.first - arrowLength * kotlin.math.cos(angle + spread).toFloat(),
            head.second - arrowLength * kotlin.math.sin(angle + spread).toFloat(),
            linePaint
        )
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) = Unit
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
            override fun onAnimationCancel(animation: android.animation.Animator) = block()
            override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
        })
    }
}
