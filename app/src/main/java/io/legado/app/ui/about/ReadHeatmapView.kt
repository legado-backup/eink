package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.utils.dpToPx
import java.time.DayOfWeek
import java.time.LocalDate

data class ReadHeatmapCell(
    val date: LocalDate,
    val readTime: Long
)

class ReadHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 柱子内部填充内边距
    private val fillPadding = 3f.dpToPx()
    // 字体大小加大
    private val textSizeTime = 10f.dpToPx()
    private val textSizeWeek = 11f.dpToPx()

    private val textPaintTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textSizeTime
        textAlign = Paint.Align.CENTER
    }

    private val textPaintWeek = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textSizeWeek
        textAlign = Paint.Align.CENTER
    }

    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val barStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dpToPx()
    }

    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private val fillRect = RectF()
    private val weekText = listOf("一","二","三","四","五","六","日")
    private var weekDayList: MutableList<ReadHeatmapCell> = mutableListOf()
    private val MAX_DAY_MS = 24 * 60 * 60 * 1000L

    init {
        initEmptyWeekList()
    }

    private fun initEmptyWeekList() {
        weekDayList.clear()
        val today = LocalDate.now()
        val monday = when (today.dayOfWeek) {
            DayOfWeek.MONDAY -> today
            DayOfWeek.TUESDAY -> today.minusDays(1)
            DayOfWeek.WEDNESDAY -> today.minusDays(2)
            DayOfWeek.THURSDAY -> today.minusDays(3)
            DayOfWeek.FRIDAY -> today.minusDays(4)
            DayOfWeek.SATURDAY -> today.minusDays(5)
            DayOfWeek.SUNDAY -> today.minusDays(6)
        }
        for (i in 0..6) {
            val date = monday.plusDays(i.toLong())
            weekDayList.add(ReadHeatmapCell(date, 0L))
        }
    }

    fun submit(allCellList: List<ReadHeatmapCell>, accentColor: Int, bgColor: Int) {
        barFillPaint.color = accentColor
        barBgPaint.color = bgColor
        // 关键：不重新initEmptyWeekList！保留原有日期，只赋值时长
        val dateTimeMap = allCellList.associate { it.date to it.readTime }
        weekDayList.forEachIndexed { idx, cell ->
            weekDayList[idx] = cell.copy(readTime = dateTimeMap[cell.date] ?: 0L)
        }
        invalidate()
    }

    private fun timeToHourStr(ms: Long): String {
        val hour = ms / 1000f / 60f / 60f
        return String.format("%.1fH", hour)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val weekCount = 7
        val padLeft = 2f.dpToPx()
        val padRight = 2f.dpToPx()
        val padTop = 20f.dpToPx()
        val padBottom = 20f.dpToPx()

        val contentWidth = width - padLeft - padRight
        val contentHeight = height - padTop - padBottom
        val columnWidth = contentWidth / weekCount
        val barWidth = columnWidth * 0.4f
        val radius = 3f.dpToPx()

        for (i in 0 until weekCount) {
            val item = weekDayList[i]
            val centerX = padLeft + columnWidth * i + columnWidth / 2f

            val barLeft = centerX - barWidth / 2f
            val barRight = centerX + barWidth / 2f
            val barTop = padTop
            val barBottom = padTop + contentHeight

            // 1. 画外层边框背景
            barRect.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(barRect, radius, radius, barBgPaint)
            canvas.drawRoundRect(barRect, radius, radius, barStrokePaint)

            // 2. 修复填充溢出，不改动日期逻辑
            if (item.readTime > 0) {
                val ratio = (item.readTime.toFloat() / MAX_DAY_MS.toFloat()).coerceAtMost(1f)
                val usableHeight = (barBottom - barTop) - fillPadding * 2
                val fillH = usableHeight * ratio
                val fillTop = barBottom - fillPadding - fillH

                fillRect.set(
                    barLeft + fillPadding,
                    fillTop,
                    barRight - fillPadding,
                    barBottom - fillPadding
                )
                canvas.drawRoundRect(fillRect, radius, radius, barFillPaint)
            }

            // 时长文字
            val timeText = timeToHourStr(item.readTime)
            val textY = barTop - 8f.dpToPx()
            canvas.drawText(timeText, centerX, textY, textPaintTime)

            // 周几文字
            canvas.drawText(weekText[i], centerX, height - 8f.dpToPx(), textPaintWeek)
        }
    }
}