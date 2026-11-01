package com.blockbridge

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/** BlockBridge's dark "night-sky" palette. */
object Palette {
    const val BG = 0xFF0B1020.toInt()
    const val SURFACE = 0xFF131A2E.toInt()
    const val SURFACE2 = 0xFF1C2540.toInt()
    const val STROKE = 0xFF26304D.toInt()
    const val TEXT = 0xFFE8ECF8.toInt()
    const val MUTED = 0xFF8A94B3.toInt()
    const val CYAN = 0xFF22D3EE.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val GOOD = 0xFF34D399.toInt()
    const val BAD = 0xFFF87171.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val DANGER = 0xFFEF4444.toInt()
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}

fun gradientRect(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

/** Adds a touch ripple on top of a background. */
fun ripple(bg: GradientDrawable, radius: Float) =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, roundRect(Color.WHITE, radius))

/** Paints a TextView's text with the cyan → violet brand gradient. */
fun android.widget.TextView.brandGradient() {
    post {
        // span the gradient across the word itself, not the whole view width
        val w = paint.measureText(text.toString()).coerceAtLeast(1f)
        paint.shader = LinearGradient(0f, 0f, w, 0f,
            Palette.CYAN, Palette.VIOLET, Shader.TileMode.CLAMP)
        invalidate()
    }
}

/**
 * The big power button: a spinning cyan/violet ring with radar pulses when protection
 * is on, a calm grey ring when it's off.
 */
class PowerButton(context: Context) : View(context) {
    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var busy = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var spin = 0f
    private var pulse = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val f = it.animatedValue as Float
            spin = f * 360f * (if (busy) 2f else 1f)
            pulse = (f * 2f) % 1f
            if (on || busy) invalidate()
        }
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    init { isClickable = true; isFocusable = true; contentDescription = "Protection on/off" }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.72f
        val stroke = r * 0.09f

        // radar pulses
        if (on) for (k in 0..1) {
            val p = (pulse + k * 0.5f) % 1f
            wave.strokeWidth = context.dp(2f)
            wave.color = Color.argb(((1 - p) * 120).toInt(), 0x22, 0xD3, 0xEE)
            c.drawCircle(cx, cy, r + stroke + p * r * 0.38f, wave)
        }

        // glowing disk
        fill.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.2f,
            if (on) intArrayOf(0xFF1E2A55.toInt(), Palette.SURFACE) else intArrayOf(Palette.SURFACE2, Palette.SURFACE),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)

        // ring
        ring.strokeWidth = stroke
        box.set(cx - r, cy - r, cx + r, cy + r)
        if (on || busy) {
            c.save()
            c.rotate(spin, cx, cy)
            ring.shader = SweepGradient(cx, cy,
                intArrayOf(Palette.CYAN, Palette.VIOLET, 0x008B5CF6, Palette.CYAN), floatArrayOf(0f, 0.45f, 0.75f, 1f))
            c.drawArc(box, 0f, 360f, false, ring)
            c.restore()
        } else {
            ring.shader = null
            ring.color = Palette.STROKE
            c.drawArc(box, 0f, 360f, false, ring)
        }

        // power symbol
        val ir = r * 0.36f
        icon.strokeWidth = r * 0.085f
        icon.shader = if (on) LinearGradient(cx - ir, cy - ir, cx + ir, cy + ir, Palette.CYAN, Palette.VIOLET, Shader.TileMode.CLAMP) else null
        icon.color = Palette.MUTED
        box.set(cx - ir, cy - ir, cx + ir, cy + ir)
        c.drawArc(box, -60f, 300f, false, icon)
        c.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.15f, icon)
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = context.dpi(210)
        setMeasuredDimension(resolveSize(size, w), resolveSize(size, h))
    }
}

/** Live chart of lookups (cyan area) and blocked (red line) over the last minute. */
class Sparkline(context: Context) : View(context) {
    private val total = ArrayDeque<Float>()
    private val blocked = ArrayDeque<Float>()
    private val capacity = 60
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val grid = Paint().apply { color = Palette.STROKE; strokeWidth = 1f }

    fun push(t: Float, b: Float) {
        total.addLast(t); blocked.addLast(b)
        while (total.size > capacity) { total.removeFirst(); blocked.removeFirst() }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) c.drawLine(0f, h * i / 4f, w, h * i / 4f, grid)
        if (total.size < 2) return
        val peak = max(4f, total.maxOrNull() ?: 1f) * 1.15f
        val step = w / (capacity - 1)
        val offset = (capacity - total.size) * step

        fun path(data: List<Float>, closed: Boolean) = Path().apply {
            data.forEachIndexed { i, v ->
                val x = offset + i * step
                val y = h - (v / peak) * h
                if (i == 0) moveTo(x, y) else {
                    val px = offset + (i - 1) * step
                    val py = h - (data[i - 1] / peak) * h
                    cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
                }
            }
            if (closed) { lineTo(offset + (data.size - 1) * step, h); lineTo(offset, h); close() }
        }

        area.shader = LinearGradient(0f, 0f, 0f, h, 0x6622D3EE, 0x0022D3EE, Shader.TileMode.CLAMP)
        c.drawPath(path(total.toList(), true), area)
        line.strokeWidth = context.dp(2f)
        line.color = Palette.CYAN
        c.drawPath(path(total.toList(), false), line)
        line.color = Palette.BAD
        c.drawPath(path(blocked.toList(), false), line)
    }
}
