package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.os.postDelayed
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.postEvent
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import androidx.core.net.toUri

/**
 * 解析内容生成章节和页面
 */
@Suppress("DEPRECATION", "ConstPropertyName")
object ChapterProvider {
    const val srcReplaceStr = "袮"
    const val srcReplaceChar = '袮'
    const val srcReplacementChar = '祢'
    const val reviewStr = "꧁"
    const val reviewChar = '꧁'
    const val indentChar = " "

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var underlineExtraHeight = 0f
        private set

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    @JvmStatic
    var doublePage = false
        private set

    @JvmStatic
    var visibleRect = RectF()

    private val handler by lazy {
        buildMainHandler()
    }

    private var upViewSizeRunnable: Runnable? = null

    init {
        upStyle()
    }

    fun getTextChapterAsync(
        scope: CoroutineScope,
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {

        val textChapter = TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            chapterSize,
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        ).apply {
            createLayout(scope, book, bookContent)
        }

        return textChapter
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
        }
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        underlineExtraHeight = if (ReadBookConfig.underlineMode != 0) 5f.dpToPx() else 0f
        upLayout()
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(fontPath.toUri(), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, fontPath.toUri()))
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    /**
     * 获取 Paint，使用 Android 原生字重 API
     * textBold: 0-100, 50 为正常 (400), 0 为最细 (100), 100 为最粗 (900)
     */
    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val boldWeight = ReadBookConfig.textBold.coerceIn(0, 100)
        // 映射：0-100 → 100-900 (50→400 正常)
        val mappedWeight = ((boldWeight - 50) * 16 + 400).toInt().coerceIn(100, 900)
        
        val titleFont = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(typeface, mappedWeight, false)
        } else {
            if (mappedWeight >= 700) Typeface.create(typeface, Typeface.BOLD) else normal
        }
        val textFont = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(typeface, mappedWeight, false)
        } else {
            if (mappedWeight >= 700) Typeface.create(typeface, Typeface.BOLD) else normal
        }

        // 标题 Paint
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.textColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        tPaint.isDither = true
        tPaint.isSubpixelText = false
        tPaint.hinting = Paint.HINTING_ON
        if (AppConfig.isEInkMode || (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender)) {
            tPaint.isLinearText = true
        }
        
        // 正文 Paint
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        cPaint.isDither = true
        cPaint.isSubpixelText = false
        cPaint.hinting = Paint.HINTING_ON
        if (AppConfig.isEInkMode || (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender)) {
            cPaint.isLinearText = true
        }
        return Pair(tPaint, cPaint)
    }

    /**
     * 更新 View 尺寸
     */
    fun upViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width != viewWidth || height != viewHeight) {
            if (width == viewWidth) {
                upViewSizeRunnable = handler.postDelayed(300) {
                    upViewSizeRunnable = null
                    notifyViewSizeChange(width, height)
                }
            } else {
                notifyViewSizeChange(width, height)
            }
        } else if (upViewSizeRunnable != null) {
            handler.removeCallbacks(upViewSizeRunnable!!)
            upViewSizeRunnable = null
        }
    }

    private fun notifyViewSizeChange(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /**
     * 更新绘制尺寸
     */
    fun upLayout() {
        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> doublePage = (viewWidth > viewHeight) && ReadBook.pageAnim() != 3
            "3" -> doublePage = (viewWidth > viewHeight || appCtx.isPad) && ReadBook.pageAnim() != 3
        }

        if (viewWidth <= 0 || viewHeight <= 0) return

        paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
        paddingTop = ReadBookConfig.paddingTop.dpToPx()
        paddingRight = ReadBookConfig.paddingRight.dpToPx()
        paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        visibleHeight = viewHeight - paddingTop - paddingBottom - underlineExtraHeight.toInt() - underlineExtraHeight.toInt()
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight

        if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
            AppLog.put("边距设置过大，请重新设置", toast = true)
            setFallbackLayout()
        }

        visibleRect.set(
            paddingLeft.toFloat() - 10,
            paddingTop.toFloat() - 10,
            visibleRight.toFloat() + 10,
            visibleBottom.toFloat() + 10f.dpToPx()
        )
    }

    private fun setFallbackLayout() {
        paddingLeft = 20.dpToPx()
        paddingTop = 5.dpToPx()
        paddingRight = 20.dpToPx()
        paddingBottom = 5.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        visibleHeight = viewHeight - paddingTop - paddingBottom - underlineExtraHeight.toInt() - underlineExtraHeight.toInt()
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight
    }
}