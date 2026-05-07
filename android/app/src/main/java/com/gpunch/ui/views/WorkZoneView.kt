package com.gpunch.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class WorkZoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 115, 232)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 26, 115, 232)
        style = Paint.Style.FILL
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 26, 115, 232)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 33, 36)
        style = Paint.Style.FILL
    }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 168, 83)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(95, 99, 104)
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 33, 36)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(95, 99, 104)
        textSize = 25f
        textAlign = Paint.Align.CENTER
    }

    private var distanceMeters = 0.0
    private var radiusMeters = 0.0
    private var inside = false
    private var hasMetrics = false
    private var inactive = false
    private var pulse = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setMetrics(distance: Double?, radius: Double?) {
        inactive = false
        hasMetrics = distance != null && radius != null && radius > 0
        if (hasMetrics) {
            distanceMeters = distance ?: 0.0
            radiusMeters = radius ?: 0.0
            inside = distanceMeters <= radiusMeters
            userPaint.color = if (inside) Color.rgb(52, 168, 83) else Color.rgb(234, 67, 53)
        }
        invalidate()
    }

    fun setInactive() {
        inactive = true
        hasMetrics = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.43f
        val zoneRadius = min(w, h) * 0.25f
        val bounds = RectF(cx - zoneRadius, cy - zoneRadius, cx + zoneRadius, cy + zoneRadius)

        canvas.drawCircle(cx, cy, zoneRadius, zoneFillPaint)
        canvas.drawCircle(cx, cy, zoneRadius + pulse * 24f, pulsePaint)
        canvas.drawOval(bounds, zonePaint)
        canvas.drawCircle(cx, cy, 8f, centerPaint)

        if (inactive) {
            canvas.drawText("Geofence inactive", cx, h - 58f, textPaint)
            canvas.drawText("Punch controls remain available", cx, h - 24f, subTextPaint)
            return
        }

        if (!hasMetrics) {
            canvas.drawText("Waiting for zone data", cx, h - 58f, textPaint)
            canvas.drawText("Location and geofence will appear here", cx, h - 24f, subTextPaint)
            return
        }

        val ratio = (distanceMeters / radiusMeters).coerceAtMost(1.35)
        val angle = Math.toRadians(-38.0)
        val userDistance = (zoneRadius * ratio).toFloat()
        val ux = cx + cos(angle).toFloat() * userDistance
        val uy = cy + sin(angle).toFloat() * userDistance

        canvas.drawLine(cx, cy, ux, uy, linePaint)
        canvas.drawCircle(ux, uy, 14f, userPaint)

        val primary = if (inside) "Inside work zone" else "Outside work zone"
        val secondary = if (inside) {
            "${distanceMeters.toInt()}m from center / ${(radiusMeters - distanceMeters).toInt().coerceAtLeast(0)}m margin"
        } else {
            "${distanceMeters.toInt()}m from center / ${(distanceMeters - radiusMeters).toInt()}m beyond"
        }
        canvas.drawText(primary, cx, h - 58f, textPaint)
        canvas.drawText(secondary, cx, h - 24f, subTextPaint)
    }
}
