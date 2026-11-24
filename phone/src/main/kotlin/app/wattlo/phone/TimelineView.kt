package app.wattlo.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import app.wattlo.DialModel
import app.wattlo.Palette
import app.wattlo.Schedule

/**
 * One day of the [TimelineView]: [roles] are the 48 half-hour slots (null: the day is not known, a dashed frame with
 * [placeholder] is drawn), [curSlot] the current one for today (-1: nothing is over), [nowMinute] for the white line.
 */
class TimelineColumn(
    val title: String,
    val summary: String,
    val summaryColor: Int,
    val roles: List<DialModel.Role>?,
    val curSlot: Int,
    val nowMinute: Int,
    val placeholder: String,
)

/**
 * Days side by side as vertical strips with the hours down the left, like a calendar: power is green, an outage is a
 * red block with its time written in it, what is over is dimmed and a white line marks the present.
 */
@SuppressLint("ViewConstructor") // only ever created from code, with its data
class TimelineView(c: Context, private var columns: List<TimelineColumn>) : View(c) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.NODATA }
    private val rect = RectF()

    fun setColumns(c: List<TimelineColumn>) { columns = c; invalidate() }

    private val axisW get() = dp(30f)
    private val headH get() = sp(15f) + sp(13f) + dp(14f)
    private val trackH get() = dp(SLOT_DP) * Schedule.SLOTS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), (headH + trackH + dp(8f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val top = headH
        val slotH = dp(SLOT_DP)
        label.textSize = sp(11f)
        label.color = Palette.LABEL
        label.textAlign = Paint.Align.RIGHT
        label.typeface = Typeface.DEFAULT
        for (h in 0..24 step 3) {
            canvas.drawText("%02d".format(h), axisW - dp(6f), top + h * 2 * slotH + sp(4f), label)
        }
        // a day that is not known yet (tomorrow's schedule comes late and is short-lived) is a narrow, quiet strip:
        // it should not take as much room as a day with something in it
        val gap = dp(10f)
        val weights = columns.map { if (it.roles == null) UNKNOWN_WEIGHT else 1f }
        val avail = width - axisW - gap * (columns.size - 1)
        var left = axisW
        for ((i, col) in columns.withIndex()) {
            val colW = avail * weights[i] / weights.sum()
            drawHead(canvas, col, left, colW)
            drawTrack(canvas, col, left, top, colW, slotH)
            left += colW + gap
        }
    }

    private fun drawHead(canvas: Canvas, col: TimelineColumn, left: Float, colW: Float) {
        label.textAlign = Paint.Align.LEFT
        label.typeface = Typeface.DEFAULT_BOLD
        label.textSize = sp(15f)
        label.color = if (col.roles == null) Palette.LABEL else Palette.WHITE
        canvas.drawText(col.title, left, sp(15f), label)
        if (col.roles == null) return // the frame below says it, in less room
        label.textSize = sp(13f)
        label.color = col.summaryColor
        canvas.drawText(col.summary, left, sp(15f) + sp(13f) + dp(4f), label)
    }

    private fun drawTrack(canvas: Canvas, col: TimelineColumn, left: Float, top: Float, colW: Float, slotH: Float) {
        val bottom = top + slotH * Schedule.SLOTS
        val radius = dp(10f)
        val roles = col.roles
        if (roles == null) {
            // no schedule yet: an empty frame that says so, instead of a strip of nothing
            frame.strokeWidth = dp(1f)
            frame.color = Palette.NODATA_DIM
            frame.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
            rect.set(left + dp(1f), top, left + colW - dp(1f), bottom)
            canvas.drawRoundRect(rect, radius, radius, frame)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f); color = Palette.LABEL }
            val w = (colW - dp(16f)).toInt()
            val layout = StaticLayout.Builder.obtain(col.placeholder, 0, col.placeholder.length, tp, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.save()
            canvas.translate(left + dp(8f), top + (bottom - top) / 2 - layout.height / 2)
            layout.draw(canvas)
            canvas.restore()
            return
        }
        canvas.save()
        rect.set(left, top, left + colW, bottom)
        val clip = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        // runs of the same role are one block
        var i = 0
        while (i < roles.size) {
            var j = i
            while (j < roles.size && roles[j] == roles[i]) j++
            fill.color = when (roles[i]) {
                DialModel.Role.RED -> Palette.RED
                DialModel.Role.AMBER -> Palette.AMBER
                DialModel.Role.GREEN -> Palette.GREEN
                DialModel.Role.NO_DATA -> Palette.NODATA
            }
            canvas.drawRect(left, top + i * slotH, left + colW, top + j * slotH, fill)
            // the time of every block that is long enough, power and outage alike; in a block that the present line
            // crosses, the text goes to the longer side so that the line does not run through it
            val labelSlots = roles[i] != DialModel.Role.NO_DATA && j - i >= 3
            if (labelSlots) {
                val cur = col.curSlot
                val mid = when {
                    cur in (i + 1) until j -> if (j - cur >= cur - i) (cur + j) / 2f else (i + cur) / 2f
                    else -> (i + j) / 2f
                }
                val room = if (cur in (i + 1) until j) maxOf(j - cur, cur - i) else j - i
                if (room >= 3) {
                    label.textAlign = Paint.Align.CENTER
                    label.typeface = Typeface.DEFAULT_BOLD
                    label.textSize = sp(12f)
                    label.color = if (roles[i] == DialModel.Role.GREEN) 0xFF0B2A14.toInt() else 0xFF2A0507.toInt()
                    val text = Schedule.slotTime(i) + "–" + (if (j == Schedule.SLOTS) "24:00" else Schedule.slotTime(j))
                    canvas.drawText(text, left + colW / 2, top + mid * slotH - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
                }
            }
            i = j
        }
        if (col.curSlot >= 0) {
            fill.color = 0x88000000.toInt()
            canvas.drawRect(left, top, left + colW, top + col.curSlot * slotH, fill)
        }
        canvas.restore()
        if (col.nowMinute >= 0) {
            val y = top + col.nowMinute / 30f * slotH
            fill.color = Palette.WHITE
            fill.strokeWidth = dp(3f)
            fill.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(left - dp(4f), y, left + colW + dp(4f), y, fill)
        }
    }

    private companion object {
        /** Height of one half hour: 48 of them make the strip about 370 dp high. */
        const val SLOT_DP = 7.7f

        /** The width of a day that is not known yet, as a share of the width of a known one. */
        const val UNKNOWN_WEIGHT = 0.42f
    }
}
