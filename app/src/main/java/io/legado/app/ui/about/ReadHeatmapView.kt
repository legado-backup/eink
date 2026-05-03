package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.utils.dpToPx
import java.time.LocalDate
import kotlin.math.ceil

data class ReadHeatmapCell(
    val date: LocalDate,
    val readTime: Long
)

class ReadHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokeBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f.dpToPx()
    }
    private val fillBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val cellRect = RectF()
    private val cellGap = 3f.dpToPx()
    private val rowCount = 7
    private val cornerRadius = 3f.dpToPx()
    // 上下左右统一1dp边距
    private val padding = 1f.dpToPx()

    private var cells: List<ReadHeatmapCell> = emptyList()

    fun submit(
        entries: List<ReadHeatmapCell>,
        accentColor: Int,
        surfaceColor: Int
    ) {
        cells = entries
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty()) return

        val columns = ceil(cells.size / rowCount.toFloat()).toInt().coerceAtLeast(1)

        // 扣除四边1dp边距
        val availWidth = width - padding * 2
        val availHeight = height - padding * 2

        // 宽高分别计算格子尺寸，取最小值防止溢出
        val cellSizeByWidth = (availWidth - cellGap * (columns - 1)) / columns
        val cellSizeByHeight = (availHeight - cellGap * (rowCount - 1)) / rowCount
        val cellSize = minOf(cellSizeByWidth, cellSizeByHeight)

        // 整体网格宽高
        val totalGridWidth = cellSize * columns + cellGap * (columns - 1)
        val totalGridHeight = cellSize * rowCount + cellGap * (rowCount - 1)

        // 整体居中
        val startX = (width - totalGridWidth) / 2f
        val startY = (height - totalGridHeight) / 2f

        cells.forEachIndexed { index, cell ->
            val col = index / rowCount
            val row = index % rowCount

            val left = startX + col * (cellSize + cellGap)
            val top = startY + row * (cellSize + cellGap)

            cellRect.set(left, top, left + cellSize, top + cellSize)

            if (cell.readTime <= 0L) {
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, fillWhitePaint)
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, strokeBlackPaint)
            } else {
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, fillBlackPaint)
            }
        }
    }
}