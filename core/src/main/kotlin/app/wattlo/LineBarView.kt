package app.wattlo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * What a [LineBarView] shows: one dash per half hour in [roles], the first [past] of them dark, a white mark at
 * [nowFrac] (0..1 along the bar, -1 for none), hour labels above the bar ([ticks]: position 0..1 and text).
 * With a [placeholder] there is no data: an empty dashed frame with that text in it.
 */
class LineBarData(
    val roles: List<DialModel.Role>,
    val past: Int = 0,
    val nowFrac: Float = -1f,
    val ticks: List<Pair<Float, String>> = emptyList(),
    val placeholder: String? = null,
)

/**
 * The schedule as one line (the same dashes and colours as the day ring, straightened). The bar is [barDp] high
 * and as wide as the view; [labelSp] is the size of the hour labels, which take a band above the bar when there are any.
 */
@SuppressLint("ViewConstructor") // only ever created from code, with its data
class LineBarView(c: Context, private var data: LineBarData, private val barDp: Float, private val labelSp: Float = 11f) : View(c) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.NODATA }
    private val oval = RectF()

    fun setData(d: LineBarData) { data = d; requestLayout(); invalidate() }

    private fun labelBand() = if (data.ticks.isEmpty()) 0f else sp(labelSp) + dp(4f)
    private fun margin() = dp(4f) // room for the mark above and below the bar

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (labelBand() + margin() * 2 + dp(barDp)).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = labelBand() + margin()
        val bottom = top + dp(barDp)
        val d = data
        if (d.ticks.isNotEmpty()) {
            text.textSize = sp(labelSp)
            text.color = Palette.LABEL
            for ((frac, s) in d.ticks) {
                val tw = text.measureText(s)
                val x = (frac * w - tw / 2).coerceIn(0f, w - tw)
                canvas.drawText(s, x, sp(labelSp), text)
            }
        }
        if (d.placeholder != null) {
            frame.strokeWidth = dp(1.5f)
            frame.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
            oval.set(dp(1f), top, w - dp(1f), bottom)
            canvas.drawRoundRect(oval, dp(8f), dp(8f), frame)
            text.textSize = sp(labelSp + 1f)
            text.color = Palette.LABEL
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(d.placeholder, w / 2, (top + bottom) / 2 - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2, text)
            text.textAlign = Paint.Align.LEFT
            return
        }
        val n = d.roles.size
        if (n == 0) return
        val slotW = w / n
        val gap = dp(1.5f)
        val radius = minOf(slotW / 2 - 1f, dp(4f)).coerceAtLeast(1f)
        for (i in 0 until n) {
            fill.color = color(d.roles[i], i < d.past)
            oval.set(i * slotW + gap / 2, top, (i + 1) * slotW - gap / 2, bottom)
            canvas.drawRoundRect(oval, radius, radius, fill)
        }
        if (d.nowFrac >= 0f) {
            val x = (d.nowFrac * w).coerceIn(dp(2f), w - dp(2f))
            fill.color = Palette.WHITE
            fill.strokeWidth = dp(3f)
            fill.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, top - margin(), x, bottom + margin(), fill)
        }
    }

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }
}
