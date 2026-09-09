package io.legado.app.help.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import java.security.MessageDigest

/**
 * E-ink 256 级灰度抖动 Transformation
 * 所有通过 Glide 加载的图片都会自动应用此抖动算法
 */
class EinkDitherTransformation : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        // 开关关闭时直接返回原图
        if (!AppConfig.einkDitherImage) {
            return toTransform
        }
        return BookCover.applyEink256(toTransform)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("eink_dither".toByteArray())
    }

    override fun equals(other: Any?): Boolean {
        return other is EinkDitherTransformation
    }

    override fun hashCode(): Int {
        return "eink_dither".hashCode()
    }
}