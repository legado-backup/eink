package io.legado.app.help

import android.graphics.Paint
import io.legado.app.utils.objectpool.BaseSafeObjectPool

object PaintPool : BaseSafeObjectPool<Paint>(8) {

    private val emptyPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = false      // E-Ink: 禁用子像素
    }

    override fun create(): Paint = Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = false      // E-Ink: 禁用子像素
    }

    override fun recycle(target: Paint) {
        target.set(emptyPaint)
        super.recycle(target)
    }

}
