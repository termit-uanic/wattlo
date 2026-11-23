package app.wattlo.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.wattlo.DialModel
import app.wattlo.Palette
import app.wattlo.Schedule

/**
 * A day as a horizontal bar of half-hour dashes (the same colours as the ring), for the widgets: drawn into a bitmap
 * that the ImageView stretches to its width. The current moment is a white line, past dashes are dimmed, and the
 * hours 00 06 12 18 24 can be written above.
 */
object WidgetBar {
    private const val PAD = 4
    private const val BAR = 44
    private const val TICKS = 28

    fun render(
        flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, nowMinute: Int,
        yellowAsOutage: Boolean, ticks: Boolean, width: Int = 780,
    ): Bitmap {
        val tickH = if (ticks) TICKS else 0
        val h = tickH + BAR + 2 * PAD
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val w = width.toFloat()
        val slotW = w / Schedule.SLOTS
        val gap = 2f
        val top = (PAD + tickH).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF()
        val radius = minOf(slotW / 2 - 1f, 8f)
        for (i in 0 until Schedule.SLOTS) {
            paint.color = color(DialModel.roleOf(flags, maybe, i, yellowAsOutage), DialModel.isPast(i, curSlot))
            r.set(i * slotW + gap / 2, top, (i + 1) * slotW - gap / 2, top + BAR)
            cv.drawRoundRect(r, radius, radius, paint)
        }
        if (nowMinute >= 0) {
            val x = (nowMinute / 1440f * w).coerceIn(3f, w - 3f)
            paint.color = Palette.WHITE
            paint.strokeWidth = 5f
            paint.strokeCap = Paint.Cap.ROUND
            cv.drawLine(x, top - PAD, x, top + BAR + PAD, paint)
        }
        if (ticks) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Palette.LABEL }
            for (hour in 0..24 step 6) {
                val label = "%02d".format(hour)
                val tw = text.measureText(label)
                val x = (hour / 24f * w - tw / 2).coerceIn(0f, w - tw)
                cv.drawText(label, x, tickH - 6f, text)
            }
        }
        return bmp
    }

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }
}
