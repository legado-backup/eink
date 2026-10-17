package io.legado.app.ui.book.read.page.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import android.util.Size
import androidx.core.text.HtmlCompat
import io.legado.app.constant.AppPattern.noWordCountRegex
import io.legado.app.data.appDb
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.atLeastApi28
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceChar
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplacementChar
import io.legado.app.utils.StringUtils
import androidx.core.text.parseAsHtml
import androidx.core.util.component1
import androidx.core.util.component2
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_CHAR
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_STR
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewChar
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import kotlin.math.min

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: ArrayList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
) {

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingRight = ChapterProvider.paddingRight
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val reviewCharWidth by lazy { contentPaint.measureText(srcReplaceStr) * 1.5556f }
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val adaptSpecialStyle = AppConfig.adaptSpecialStyle
    private val pageAnim = book.getPageAnim()

    private var pendingTextPage = TextPage()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)

    private val bgBitmapCache = ConcurrentHashMap<String, Bitmap>()

    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            launch {
                val bookSource = book.getBookSource() ?: return@launch
                BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
            }
            getTextChapter(book, bookChapter, displayTitle, bookContent)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    private fun onPageCompleted() {
        val textPage = pendingTextPage
        textPage.index = textPages.size
        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
    ) {
        val contents = bookContent.textList
        val imageStyle = book.getImageStyle()
        val isSingleImageStyle = imageStyle.equals(Book.imgStyleSingle, true)

        val customTitleHtml = appCtx.getPrefString("book_title_html", "")

        if (titleMode != 2 || bookChapter.isVolume || contents.isEmpty()) {
            val useCustomTitle = !customTitleHtml.isNullOrBlank() &&
                    awaitTitleDataReady(displayTitle)

            var firstLine = true
            displayTitle.splitNotBlank("\n").forEach { text ->
                if (useCustomTitle && customTitleHtml != null) {
                    val ok = try {
                        val html = applyTitlePlaceholders(
                            customTitleHtml, text, book, bookChapter
                        )
                        setTitleAsCssBitmap(book, html, contents.isEmpty())
                        true
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        AppLog.put("自定义标题渲染失败，退化为普通标题\n${e.localizedMessage}", e)
                        false
                    }
                    if (ok) {
                        if (pendingTextPage.lines.isNotEmpty()) {
                            pendingTextPage.lines.last().isParagraphEnd = true
                        }
                        stringBuilder.append("\n")
                        return@forEach
                    }
                }

                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                val titleImg = if (firstLine) {
                    firstLine = false
                    bookChapter.imgUrl
                } else {
                    null
                }
                val imgText = if (titleImg.isNullOrEmpty()) {
                    null
                } else {
                    val urlMatcher = paramPattern.matcher(titleImg)
                    var click: String? = null
                    var style: String? = null
                    var imgSize = ImageProvider.getImageSize(book, titleImg, ReadBook.bookSource)
                    if (urlMatcher.find()) {
                        var width: String? = null
                        val urlOptionStr = titleImg.substring(urlMatcher.end())
                        GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
                            ?.let { map ->
                                map.forEach { (key, value) ->
                                    when (key) {
                                        "style" -> style = value
                                        "width" -> width = value
                                        "click" -> click = value
                                    }
                                }
                            }
                        width?.let {
                            if (width.endsWith("%")) {
                                width.dropLast(1).toIntOrNull()?.let { percentage ->
                                    val imgWidth = visibleWidth * percentage / 100
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                                }
                            } else {
                                width.toIntOrNull()?.let { width ->
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(width, sizeHeight * width / sizeWidth)
                                }
                            }
                        }
                    }
                    if (style == null) {
                        style = if (imgSize.width < 80 && imgSize.height < 80) {
                            "text"
                        } else {
                            imageStyle
                        }
                    }
                    when (style) {
                        "text" -> {
                            srcList.add(titleImg)
                            clickList.add(click)
                            srcReplaceChar
                        }
                        "TEXT" -> {
                            srcList.add(titleImg)
                            clickList.add(click)
                            reviewChar
                        }
                        else -> {
                            setTypeImage(
                                book,
                                titleImg,
                                contentPaintTextHeight,
                                style,
                                imgSize,
                                click
                            )
                            null
                        }
                    }
                }
                setTypeText(
                    book,
                    if (imgText != null) text + imgText else text,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = clickList,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                )
                if (pendingTextPage.lines.isNotEmpty()) {
                    pendingTextPage.lines.last().isParagraphEnd = true
                }
                stringBuilder.append("\n")
            }
            durY += titleBottomSpacing

            if (isSingleImageStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                prepareNextPageIfNeed()
            }
        }

        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        val sb = StringBuffer()
        var isSetTypedImage = false
        var wordCount = 0
        contents.forEach { content ->
            currentCoroutineContext().ensureActive()
            if (adaptSpecialStyle) {
                val text = content.trim()
                if (text == "[newpage]") {
                    prepareNextPageIfNeed()
                    return@forEach
                } else if (text.startsWith("<usehtml>")) {
                    val endInt = text.lastIndexOf("<")
                    if (endInt > 9) {
                        setTypeHtml(imageStyle, book, text.substring(9, endInt))
                        return@forEach
                    }
                }
            }
            var text = content.replace(srcReplaceChar, srcReplacementChar)
            if (isTextImageStyle) {
                val srcList = LinkedList<String>()
                sb.setLength(0)
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let { src ->
                        srcList.add(src)
                        matcher.appendReplacement(sb, srcReplaceStr)
                    }
                }
                matcher.appendTail(sb)
                text = sb.toString()
                wordCount += text.replace(noWordCountRegex, "").length
                setTypeText(
                    book,
                    text,
                    contentPaint,
                    contentPaintTextHeight,
                    contentPaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = null
                )
            } else {
                if (isSingleImageStyle && isSetTypedImage) {
                    isSetTypedImage = false
                    prepareNextPageIfNeed()
                }
                var start = 0
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                sb.setLength(0)
                var isFirstLine = true
                if (content.contains("<img")) {
                    val matcher = AppPattern.imgPattern.matcher(text)
                    while (matcher.find()) {
                        currentCoroutineContext().ensureActive()
                        val imgSrc = matcher.group(1)!!
                        var style: String? = null
                        var click: String? = null
                        var imgSize = ImageProvider.getImageSize(book, imgSrc, ReadBook.bookSource)
                        val urlMatcher = paramPattern.matcher(imgSrc)
                        if (urlMatcher.find()) {
                            var width: String? = null
                            val urlOptionStr = imgSrc.substring(urlMatcher.end())
                            GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let { map ->
                                map.forEach { (key, value) ->
                                    when (key) {
                                        "style" -> style = value
                                        "width" -> width = value
                                        "click" -> click = value
                                    }
                                }
                            }
                            width?.let {
                                if (width.endsWith("%")) {
                                    width.dropLast(1).toIntOrNull()?.let { percentage ->
                                        val imgWidth = visibleWidth * percentage / 100
                                        val (sizeHeight, sizeWidth) = imgSize
                                        imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                                    }
                                } else {
                                    width.toIntOrNull()?.let { width ->
                                        val (sizeHeight, sizeWidth) = imgSize
                                        imgSize = Size(width, sizeHeight * width / sizeWidth)
                                    }
                                }
                            }
                        }
                        if (style == null) {
                            style = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        if (start < matcher.start()) {
                            sb.append(text.subSequence(start, matcher.start()))
                        }
                        when (style) {
                            "TEXT" -> {
                                sb.append(reviewChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            "text" -> {
                                sb.append(srcReplaceChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            else -> {
                                val textBefore = sb.toString()
                                if (textBefore.isNotBlank()) {
                                    wordCount += textBefore.replace(noWordCountRegex, "").length
                                    setTypeText(
                                        book,
                                        sb.toString(),
                                        contentPaint,
                                        contentPaintTextHeight,
                                        contentPaintFontMetrics,
                                        "TEXT",
                                        isFirstLine = isFirstLine,
                                        srcList = srcList,
                                        clickList = clickList
                                    )
                                    sb.setLength(0)
                                    isFirstLine = false
                                }
                                setTypeImage(
                                    book,
                                    imgSrc,
                                    contentPaintTextHeight,
                                    style,
                                    imgSize,
                                    click
                                )
                                isSetTypedImage = true
                            }
                        }
                        start = matcher.end()
                    }
                }
                if (start < content.length) {
                    if (isSingleImageStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        prepareNextPageIfNeed()
                    }
                    val textAfter = content.subSequence(start, content.length)
                    sb.append(textAfter)
                }
                text = sb.toString()
                if (text.isNotBlank()) {
                    wordCount += text.replace(noWordCountRegex, "").length
                    setTypeText(
                        book,
                        text,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        "TEXT",
                        isFirstLine = isFirstLine,
                        srcList = srcList,
                        clickList = clickList
                    )
                }
            }
            if (pendingTextPage.lines.isNotEmpty()) {
                pendingTextPage.lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")
        }
        val chapterWordCount = StringUtils.wordCountFormat(wordCount.toString())
        bookChapter.wordCount = chapterWordCount
        appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, chapterWordCount)
        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        currentCoroutineContext().ensureActive()
        onPageCompleted()
        onCompleted()
    }

    // ============================================================
    // 数据就绪等待（无限等待）
    // ============================================================

    private suspend fun awaitTitleDataReady(displayTitle: String): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isTitleDataReady(displayTitle)) return true
            delay(50L)
        }
    }

    private fun isTitleDataReady(displayTitle: String): Boolean {
        val bookName: String? = book.name
        if (bookName.isNullOrBlank()) return false
        if (bookChapter.index < 0) return false
        if (displayTitle.isBlank()) return false
        return true
    }

    // ============================================================
    // 占位符系统
    // ============================================================

    private val titlePlaceholderRegex = Regex(
        """\{(title|count|index|bookName)(?::([^{}]+))?\}"""
    )

    private fun applyTitlePlaceholders(
        html: String,
        titleText: String,
        book: Book,
        chapter: BookChapter,
    ): String {
        return try {
            titlePlaceholderRegex.replace(html) { m ->
                val key = m.groupValues.getOrNull(1) ?: ""
                val ops = m.groupValues.getOrNull(2) ?: ""
                val raw: String = when (key) {
                    "title" -> titleText
                    "count" -> chapter.wordCount ?: ""
                    "index" -> (chapter.index + 1).toString()
                    "bookName" -> (book.name ?: "")
                    else -> ""
                }
                applyPlaceholderOps(raw, ops)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("占位符替换失败: ${e.localizedMessage}", e)
            html
        }
    }

    private fun applyPlaceholderOps(value: String, ops: String): String {
        if (ops.isBlank()) return value
        var result: String = value
        ops.split(":").forEach { rawOp ->
            val op = rawOp.trim()
            if (op.isEmpty()) return@forEach
            result = try {
                when {
                    op == "trim" -> result.trim()
                    op == "upper" -> result.uppercase()
                    op == "lower" -> result.lowercase()

                    // ============ 空格分词 ============
                    op == "beforeSpace" -> {
                        val i = result.indexOf(' ')
                        if (i >= 0) result.substring(0, i) else result
                    }
                    op == "afterSpace" -> {
                        val i = result.indexOf(' ')
                        if (i >= 0) result.substring(i + 1) else result
                    }
                    op == "beforeLastSpace" -> {
                        val i = result.lastIndexOf(' ')
                        if (i >= 0) result.substring(0, i) else result
                    }
                    op == "afterLastSpace" -> {
                        val i = result.lastIndexOf(' ')
                        if (i >= 0) result.substring(i + 1) else result
                    }
                    op == "firstWord" -> {
                        val i = result.indexOf(' ')
                        (if (i >= 0) result.substring(0, i) else result).trim()
                    }
                    op == "lastWord" -> {
                        val i = result.lastIndexOf(' ')
                        (if (i >= 0) result.substring(i + 1) else result).trim()
                    }
                    op == "noSpace" -> result.replace(Regex("""\s+"""), "")

                    // ============ 括号 / 标点 ============
                    op == "noBracket" -> result.replace(
                        Regex("""[（(【\[][^)）\]】]*[)）\]】]"""), ""
                    )
                    op == "noMark" -> result.replace(
                        Regex("""[·・\-—_\s]+"""), ""
                    )

                    // ============ 首尾 N 字 ============
                    op.startsWith("first=") -> {
                        val n = op.removePrefix("first=").toIntOrNull()
                        if (n == null || n <= 0) result else result.take(n)
                    }
                    op.startsWith("last=") -> {
                        val n = op.removePrefix("last=").toIntOrNull()
                        if (n == null || n <= 0) result
                        else if (n >= result.length) result
                        else result.substring(result.length - n)
                    }

                    // ============ 指定分隔符 ============
                    op.startsWith("before=") -> {
                        val sep = op.removePrefix("before=")
                        if (sep.isEmpty()) result
                        else {
                            val i = result.indexOf(sep)
                            if (i >= 0) result.substring(0, i) else result
                        }
                    }
                    op.startsWith("after=") -> {
                        val sep = op.removePrefix("after=")
                        if (sep.isEmpty()) result
                        else {
                            val i = result.indexOf(sep)
                            if (i >= 0) result.substring(i + sep.length) else result
                        }
                    }

                    // ============ 正则 ============
                    op.startsWith("replace=") -> {
                        val body = op.removePrefix("replace=")
                        val idx = body.indexOf("=>")
                        if (idx <= 0) result else {
                            val pattern = body.substring(0, idx)
                            val replacement = body.substring(idx + 2)
                            if (pattern.isEmpty()) result
                            else Regex(pattern).replace(result, replacement)
                        }
                    }
                    op.startsWith("delete=") -> {
                        val pattern = op.removePrefix("delete=")
                        if (pattern.isEmpty()) result
                        else Regex(pattern).replace(result, "")
                    }
                    op.startsWith("sub=") -> {
                        val parts = op.removePrefix("sub=").split(":")
                        if (parts.size != 2) result else {
                            val s = parts[0].toIntOrNull()
                            val e = parts[1].toIntOrNull()
                            if (s == null || e == null ||
                                s !in 0..result.length || e !in s..result.length
                            ) result
                            else result.substring(s, e)
                        }
                    }
                    else -> result
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                result
            }
        }
        return result
    }

    // ============================================================
    // 标题 CSS 数据结构
    // ============================================================

    private enum class HAlign { LEFT, CENTER, RIGHT }

    // ★ 改动 1：HBlock 新增 background / borderRadiusPx / inlineBlock
    private data class HBlock(
        val text: String,
        val fontSizePx: Float,
        val fontWeight: Int,
        val color: Int,
        val align: HAlign,
        val marginTopPx: Float = 0f,
        val marginRightPx: Float = 0f,
        val marginBottomPx: Float = 0f,
        val marginLeftPx: Float = 0f,
        val paddingTopPx: Float = 0f,
        val paddingRightPx: Float = 0f,
        val paddingBottomPx: Float = 0f,
        val paddingLeftPx: Float = 0f,
        val offsetXPx: Float = 0f,
        val offsetYPx: Float = 0f,
        val lineHeightPx: Float = 0f,
        // ★ 新增字段
        val background: Int? = null,          // ARGB 背景色，null 表示不画
        val borderRadiusPx: Float = 0f,       // 圆角半径
        val inlineBlock: Boolean = false,     // 是否紧凑（只包住文字）
    )

    private data class BgPosition(
        val xPercent: Float? = null,
        val yPercent: Float? = null,
        val xPx: Float? = null,
        val yPx: Float? = null,
    )

    private data class TitleCss(
        val bgUrl: String? = null,
        val bgHeightRatio: Float = 0.22f,
        val brighten: Boolean = false,
        val bgPosition: BgPosition = BgPosition(),
        val blocks: List<HBlock> = emptyList(),
    )

    // ============================================================
    // 解析入口
    // ============================================================

    private fun parseTitleCss(html: String, baseSize: Float): TitleCss {
        return try {
            val bgUrl = Regex(
                """background-image\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.trim()

            val bgRatio = Regex(
                """background-size\s*:\s*(?:auto\s+)?(\d+(?:\.\d+)?)%""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(100f) ?: 0.22f

            val brighten = Regex(
                """filter\s*:\s*brightness\s*\(\s*0\s*\)""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(html)

            val bgPosition = Regex(
                """background-position\s*:\s*([^;}"']+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let { parseBgPosition(it) }
                ?: BgPosition()

            val blocks = mutableListOf<HBlock>()
            val hRegex = Regex(
                """<h([1-6])([^>]*)>(.*?)</h\1>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            hRegex.findAll(html).forEach { m ->
                val level = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 3
                val attrs = m.groupValues.getOrNull(2) ?: ""
                val rawText = m.groupValues.getOrNull(3) ?: ""
                val text = rawText.replace(Regex("""<[^>]+>"""), "").trim()
                val style = Regex("""style\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1) ?: ""
                blocks.add(parseHBlock(style, text, baseSize, level))
            }

            TitleCss(
                bgUrl = bgUrl,
                bgHeightRatio = bgRatio,
                brighten = brighten,
                bgPosition = bgPosition,
                blocks = blocks,
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("标题 CSS 解析失败: ${e.localizedMessage}", e)
            TitleCss()
        }
    }

    private fun parseHBlock(style: String, text: String, baseSize: Float, level: Int): HBlock {
        val defaultEm = when (level) {
            1 -> 2.0f
            2 -> 1.6f
            3 -> 1.4f
            4 -> 1.1f
            5 -> 1.0f
            6 -> 0.9f
            else -> 1.2f
        }
        val fontSizePx = parseSizeProp(style, "font-size", baseSize)
            ?: (baseSize * defaultEm)

        val fontWeight = Regex(
            """(?:^|[;\s])font-weight\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (level <= 3) 600 else 200

        // ★ 改动 2：color 支持 #hex 和 rgba()
        val color = Regex(
            """(?:^|[;\s])color\s*:\s*([^;]+?)\s*(?:;|$)""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)?.trim()?.let { parseColor(style) }
            ?: Color.BLACK

        val align = when (
            Regex(
                """(?:^|[;\s])text-align\s*:\s*(left|center|right)""",
                RegexOption.IGNORE_CASE
            ).find(style)?.groupValues?.getOrNull(1)?.lowercase()
        ) {
            "left" -> HAlign.LEFT
            "right" -> HAlign.RIGHT
            else -> HAlign.CENTER
        }

        val marginBox = parseBoxProp(style, "margin", baseSize)
        val marginTopPx = parseSizeProp(style, "margin-top", baseSize) ?: marginBox[0]
        val marginRightPx = parseSizeProp(style, "margin-right", baseSize) ?: marginBox[1]
        val marginBottomPx = parseSizeProp(style, "margin-bottom", baseSize) ?: marginBox[2]
        val marginLeftPx = parseSizeProp(style, "margin-left", baseSize) ?: marginBox[3]

        val paddingBox = parseBoxProp(style, "padding", baseSize)
        val paddingTopPx = parseSizeProp(style, "padding-top", baseSize) ?: paddingBox[0]
        val paddingRightPx = parseSizeProp(style, "padding-right", baseSize) ?: paddingBox[1]
        val paddingBottomPx = parseSizeProp(style, "padding-bottom", baseSize) ?: paddingBox[2]
        val paddingLeftPx = parseSizeProp(style, "padding-left", baseSize) ?: paddingBox[3]

        var offsetXPx = parseSizeProp(style, "left", baseSize) ?: 0f
        var offsetYPx = parseSizeProp(style, "top", baseSize) ?: 0f
        Regex(
            """transform\s*:\s*translate\(\s*(-?[\d.]+)(em|px)?\s*,\s*(-?[\d.]+)(em|px)?\s*\)""",
            RegexOption.IGNORE_CASE
        ).find(style)?.let { m ->
            offsetXPx = sizeValueToPx(
                m.groupValues.getOrNull(1) ?: "0",
                m.groupValues.getOrNull(2) ?: "",
                baseSize
            )
            offsetYPx = sizeValueToPx(
                m.groupValues.getOrNull(3) ?: "0",
                m.groupValues.getOrNull(4) ?: "",
                baseSize
            )
        }

        val lineHeightPx = parseSizeProp(style, "line-height", baseSize) ?: 0f

        // ★ 改动 3：解析 background / border-radius / display:inline-block
        // background: 同时匹配 background 和 background-color
        // 只认纯色（#hex 或 rgb()/rgba()），不认渐变等复杂值
        val background: Int? = Regex(
            """(?:^|[;\s])background(?:-color)?\s*:\s*(#[0-9a-fA-F]{3,8}|rgba?\([^)]+\))""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)?.trim()?.let { parseColorHex(it) }

        val borderRadiusPx = parseSizeProp(style, "border-radius", baseSize) ?: 0f

        val inlineBlock = Regex(
            """(?:^|[;\s])display\s*:\s*inline(?:-block)?""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(style)

        return HBlock(
            text = text,
            fontSizePx = fontSizePx.coerceAtLeast(1f),
            fontWeight = fontWeight.coerceIn(100, 900),
            color = color,
            align = align,
            marginTopPx = marginTopPx,
            marginRightPx = marginRightPx,
            marginBottomPx = marginBottomPx,
            marginLeftPx = marginLeftPx,
            paddingTopPx = paddingTopPx,
            paddingRightPx = paddingRightPx,
            paddingBottomPx = paddingBottomPx,
            paddingLeftPx = paddingLeftPx,
            offsetXPx = offsetXPx,
            offsetYPx = offsetYPx,
            lineHeightPx = lineHeightPx,
            // ★ 新增字段
            background = background,
            borderRadiusPx = borderRadiusPx,
            inlineBlock = inlineBlock,
        )
    }

    private fun parseBgPosition(value: String): BgPosition {
        val tokens = value.trim()
            .split(Regex("""[\s,]+"""))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return BgPosition()

        var xPercent: Float? = null
        var yPercent: Float? = null
        var xPx: Float? = null
        var yPx: Float? = null

        fun applyToken(tok: String, isX: Boolean) {
            val lower = tok.lowercase()
            when (lower) {
                "center" -> if (isX) xPercent = 0.5f else yPercent = 0.5f
                "left" -> xPercent = 0f
                "right" -> xPercent = 1f
                "top" -> yPercent = 0f
                "bottom" -> yPercent = 1f
                else -> {
                    if (tok.endsWith("%")) {
                        tok.dropLast(1).toFloatOrNull()?.let {
                            if (isX) xPercent = it / 100f else yPercent = it / 100f
                        }
                    } else if (tok.endsWith("px")) {
                        tok.dropLast(2).toFloatOrNull()?.let {
                            if (isX) xPx = it else yPx = it
                        }
                    }
                }
            }
        }

        if (tokens.size == 1) {
            val t = tokens[0].lowercase()
            when (t) {
                "center" -> { xPercent = 0.5f; yPercent = 0.5f }
                "left" -> { xPercent = 0f; yPercent = 0.5f }
                "right" -> { xPercent = 1f; yPercent = 0.5f }
                "top" -> { xPercent = 0.5f; yPercent = 0f }
                "bottom" -> { xPercent = 0.5f; yPercent = 1f }
                else -> applyToken(tokens[0], true)
            }
        } else {
            val first = tokens[0].lowercase()
            val firstIsVertical = first == "top" || first == "bottom"
            if (firstIsVertical) {
                applyToken(tokens[0], false)
                applyToken(tokens[1], true)
            } else {
                applyToken(tokens[0], true)
                applyToken(tokens[1], false)
            }
        }

        return BgPosition(xPercent, yPercent, xPx, yPx)
    }

    // ============================================================
    // 解析辅助函数
    // ============================================================

    private fun parseSizeProp(style: String, prop: String, baseSize: Float): Float? {
        return try {
            val regex = Regex(
                """(?:^|[;\s])${Regex.escape(prop)}\s*:\s*(-?[\d.]+)(em|px)?""",
                RegexOption.IGNORE_CASE
            )
            val m = regex.find(style) ?: return null
            sizeValueToPx(
                m.groupValues.getOrNull(1) ?: "0",
                m.groupValues.getOrNull(2) ?: "",
                baseSize
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseBoxProp(style: String, prop: String, baseSize: Float): FloatArray {
        return try {
            val regex = Regex(
                """(?:^|[;\s])${Regex.escape(prop)}\s*:\s*""" +
                    """(-?[\d.]+)(em|px)?(?:\s+(-?[\d.]+)(em|px)?)?""" +
                    """(?:\s+(-?[\d.]+)(em|px)?)?(?:\s+(-?[\d.]+)(em|px)?)?""",
                RegexOption.IGNORE_CASE
            )
            val m = regex.find(style) ?: return floatArrayOf(0f, 0f, 0f, 0f)
            val values = mutableListOf<Float>()
            for (i in 0..3) {
                val idx = 1 + i * 2
                if (idx + 1 >= m.groupValues.size || m.groupValues[idx].isEmpty()) break
                values.add(
                    sizeValueToPx(
                        m.groupValues[idx],
                        m.groupValues.getOrNull(idx + 1) ?: "",
                        baseSize
                    )
                )
            }
            when (values.size) {
                0 -> floatArrayOf(0f, 0f, 0f, 0f)
                1 -> floatArrayOf(values[0], values[0], values[0], values[0])
                2 -> floatArrayOf(values[0], values[1], values[0], values[1])
                3 -> floatArrayOf(values[0], values[1], values[2], values[1])
                4 -> floatArrayOf(values[0], values[1], values[2], values[3])
                else -> floatArrayOf(0f, 0f, 0f, 0f)
            }
        } catch (_: Throwable) {
            floatArrayOf(0f, 0f, 0f, 0f)
        }
    }

    private fun sizeValueToPx(numStr: String, unit: String, baseSize: Float): Float {
        val num = numStr.toFloatOrNull() ?: 0f
        return when (unit.lowercase()) {
            "px" -> num
            else -> num * baseSize
        }
    }

    /**
     * 解析颜色字符串，支持：
     *   - #RGB
     *   - #RRGGBB
     *   - #AARRGGBB
     *   - rgb(r, g, b)
     *   - rgba(r, g, b, a)
     */
    private fun parseColorHex(s: String): Int {
        return try {
            val str = s.trim()
            // rgba() / rgb()
            val m = Regex(
                """rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)""",
                RegexOption.IGNORE_CASE
            ).find(str)
            if (m != null) {
                val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: 0
                val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: 0
                val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: 0
                val aStr = m.groupValues.getOrNull(4).orEmpty()
                val alpha = if (aStr.isBlank()) 1f
                            else aStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                return Color.argb((alpha * 255f).toInt(), r, g, b)
            }
            // #hex
            when (str.length) {
                4 -> {
                    val r = str[1].toString().repeat(2).toInt(16)
                    val g = str[2].toString().repeat(2).toInt(16)
                    val b = str[3].toString().repeat(2).toInt(16)
                    Color.rgb(r, g, b)
                }
                7, 9 -> Color.parseColor(str)
                else -> Color.BLACK
            }
        } catch (_: Exception) {
            Color.BLACK
        }
    }

    /**
     * 用于提取 color 属性的值，兼容 #hex 和 rgba() 写法
     */
    private fun parseColor(style: String): Int {
        // 先尝试 rgba()/rgb()
        val rgbMatch = Regex(
            """(?:^|[;\s])color\s*:\s*(rgba?\([^)]+\))""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)
        if (rgbMatch != null) return parseColorHex(rgbMatch)

        // 再尝试 #hex
        val hexMatch = Regex(
            """(?:^|[;\s])color\s*:\s*(#[0-9a-fA-F]{3,8})""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)
        if (hexMatch != null) return parseColorHex(hexMatch)

        return Color.BLACK
    }

    // ============================================================
    // 加载背景图 / 剪影
    // ============================================================

    private fun loadBgBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null

        val cacheKey = "${url.length}_${url.hashCode()}"
        bgBitmapCache[cacheKey]?.let { return it }

        val bmp: Bitmap? = try {
            if (url.startsWith("data:", ignoreCase = true)) {
                decodeDataUri(url)
            } else {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Throwable) {
            AppLog.put("加载背景图失败: ${e.localizedMessage}", e)
            null
        }

        if (bmp != null) {
            bgBitmapCache[cacheKey] = bmp
        }
        return bmp
    }

    private fun decodeDataUri(dataUri: String): Bitmap? {
        val commaIdx = dataUri.indexOf(',')
        if (commaIdx < 0) return null

        val meta = dataUri.substring(5, commaIdx)
        val rawData = dataUri.substring(commaIdx + 1)

        val bytes: ByteArray = try {
            if (meta.contains("base64", ignoreCase = true)) {
                Base64.decode(rawData, Base64.DEFAULT)
            } else {
                URLDecoder.decode(rawData, "UTF-8").toByteArray(Charsets.ISO_8859_1)
            }
        } catch (e: Throwable) {
            AppLog.put("data URI 解码失败: ${e.localizedMessage}", e)
            return null
        }

        if (bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun binarize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        return try {
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)

            val threshold = 200

            for (i in px.indices) {
                val c = px[i]
                val a = (c shr 24) and 0xFF
                if (a == 0) {
                    px[i] = 0
                    continue
                }
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                px[i] = if (lum >= threshold) 0 else (a shl 24)
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("binarize 失败: ${e.localizedMessage}", e)
            src
        }
    }

    private fun weightToTypeface(weight: Int): Typeface {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, weight, false)
        } else {
            if (weight >= 700) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ============================================================
    // 渲染
    // ============================================================

    private fun renderTitleBitmap(css: TitleCss): Bitmap {
        val widthPx = visibleWidth

        val paints = css.blocks.map { block ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isDither = true
                isSubpixelText = false
                hinting = Paint.HINTING_ON
                textAlign = when (block.align) {
                    HAlign.LEFT -> Paint.Align.LEFT
                    HAlign.RIGHT -> Paint.Align.RIGHT
                    HAlign.CENTER -> Paint.Align.CENTER
                }
                color = block.color
                typeface = weightToTypeface(block.fontWeight)
                textSize = block.fontSizePx
            }
        }

        val blockHeights = css.blocks.mapIndexed { i, block ->
            val p = paints[i]
            val fm = p.fontMetrics
            val textHeight = if (block.lineHeightPx > 0) block.lineHeightPx
                             else (fm.descent - fm.ascent)
            block.marginTopPx + block.paddingTopPx + textHeight +
                block.paddingBottomPx + block.marginBottomPx
        }

        val totalHeight = blockHeights.sum().toInt().coerceAtLeast(1)
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = totalHeight.coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ---------- 1. 画背景图 ----------
        try {
            val bgUrlValue: String? = css.bgUrl
            val bgBitmap: Bitmap? = if (bgUrlValue.isNullOrBlank()) {
                null
            } else {
                loadBgBitmap(bgUrlValue)
            }
            val bgFinal: Bitmap? = if (css.brighten && bgBitmap != null) {
                binarize(bgBitmap)
            } else {
                bgBitmap
            }

            if (bgFinal != null && bgFinal.width > 0 && bgFinal.height > 0) {
                val targetH = safeHeight * css.bgHeightRatio
                val scale = targetH / bgFinal.height
                if (scale.isFinite() && scale > 0f) {
                    val drawW = bgFinal.width * scale
                    val drawH = bgFinal.height * scale
                    if (drawW.isFinite() && drawH.isFinite() && drawW > 0f && drawH > 0f) {
                        val pos = css.bgPosition
                        val left: Float = when {
                            pos.xPx != null -> pos.xPx
                            pos.xPercent != null -> (safeWidth - drawW) * pos.xPercent
                            else -> (safeWidth - drawW) / 2f
                        }
                        val top: Float = when {
                            pos.yPx != null -> pos.yPx
                            pos.yPercent != null -> (safeHeight - drawH) * pos.yPercent
                            else -> 0f
                        }
                        if (left.isFinite() && top.isFinite()) {
                            canvas.drawBitmap(
                                bgFinal,
                                null,
                                RectF(left, top, left + drawW, top + drawH),
                                null
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("标题背景渲染失败: ${e.localizedMessage}", e)
        }

        // ---------- 2. 画每块的背景色 / 圆角 ----------
        // ★ 改动 4：新增，画在文字之前（避免压住文字）
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        var bgY = 0f
        css.blocks.forEachIndexed { i, block ->
            val bgColor = block.background
            if (bgColor != null && Color.alpha(bgColor) > 0) {
                val p = paints[i]
                val fm = p.fontMetrics
                val textH = if (block.lineHeightPx > 0) block.lineHeightPx
                            else (fm.descent - fm.ascent)

                // 背景宽：紧凑模式只包住文字，否则撑满整行
                val textW: Float = if (block.inlineBlock) {
                    p.measureText(block.text)
                } else {
                    (safeWidth - block.marginLeftPx - block.marginRightPx)
                        .coerceAtLeast(0f)
                }

                val bgW = textW + block.paddingLeftPx + block.paddingRightPx
                val bgH = block.paddingTopPx + textH + block.paddingBottomPx

                // 背景水平位置
                val bgLeft: Float = if (block.inlineBlock) {
                    when (block.align) {
                        HAlign.LEFT ->
                            block.marginLeftPx + block.offsetXPx
                        HAlign.RIGHT ->
                            safeWidth - block.marginRightPx - bgW + block.offsetXPx
                        HAlign.CENTER ->
                            (safeWidth - bgW) / 2f + block.offsetXPx
                    }
                } else {
                    block.marginLeftPx + block.offsetXPx
                }
                val bgTop = bgY + block.marginTopPx + block.offsetYPx
                val bgRight = bgLeft + bgW
                val bgBottom = bgTop + bgH

                if (bgRight > bgLeft && bgBottom > bgTop) {
                    bgPaint.color = bgColor
                    val rect = RectF(bgLeft, bgTop, bgRight, bgBottom)
                    if (block.borderRadiusPx > 0f) {
                        val r = block.borderRadiusPx
                            .coerceAtMost(min(rect.width(), rect.height()) / 2f)
                        canvas.drawRoundRect(rect, r, r, bgPaint)
                    } else {
                        canvas.drawRect(rect, bgPaint)
                    }
                }
            }
            bgY += blockHeights.getOrElse(i) { 0f }
        }

        // ---------- 3. 画文字 ----------
        var y = 0f
        css.blocks.forEachIndexed { i, block ->
            val p = paints[i]
            val fm = p.fontMetrics

            val textTop = y + block.marginTopPx + block.paddingTopPx
            val baseline = textTop + (-fm.ascent)

            val blockLeft = block.marginLeftPx
            val blockRight = safeWidth - block.marginRightPx
            val textX = when (block.align) {
                HAlign.LEFT -> blockLeft + block.paddingLeftPx
                HAlign.RIGHT -> blockRight - block.paddingRightPx
                HAlign.CENTER -> (blockLeft + blockRight) / 2f +
                    (block.paddingLeftPx - block.paddingRightPx) / 2f
            }

            val finalX = textX + block.offsetXPx
            val finalY = baseline + block.offsetYPx

            if (block.text.isNotEmpty() && finalX.isFinite() && finalY.isFinite()) {
                canvas.drawText(block.text, finalX, finalY, p)
            }

            y += blockHeights.getOrElse(i) { 0f }
        }

        return bmp
    }

    private suspend fun setTitleAsCssBitmap(book: Book, html: String, emptyContent: Boolean) {
        try {
            val baseSize = contentPaint.textSize
            val css = parseTitleCss(html, baseSize)

            if (css.blocks.all { it.text.isEmpty() } && css.bgUrl.isNullOrBlank()) {
                return
            }

            val bmp = renderTitleBitmap(css)
            if (bmp.width <= 0 || bmp.height <= 0) return

            val src = "css-title://" + Integer.toHexString(html.hashCode())
            val vFile = BookHelp.getImage(book, src)
            if (!vFile.exists()) {
                vFile.parentFile?.mkdirs()
                runCatching {
                    FileOutputStream(vFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
            ImageProvider.put(vFile.absolutePath, bmp)

            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            if (w <= 0f || h <= 0f) return

            durY = when {
                emptyContent && textPages.isEmpty() -> {
                    ((visibleHeight - h) / 2).coerceAtLeast(titleTopSpacing.toFloat())
                }
                textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> durY + titleTopSpacing
                else -> durY
            }

            prepareNextPageIfNeed(durY + h)

            val textLine = TextLine(isImage = true)
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += h
            textLine.lineBottom = durY + paddingTop

            val start = (visibleWidth - w) / 2f
            val end = start + w
            textLine.addColumn(
                ImageColumn(
                    start = absStartX + start,
                    end = absStartX + end,
                    src = src,
                    click = null,
                )
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ")
            pendingTextPage.addLine(textLine)

            durY += titleBottomSpacing
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("setTitleAsCssBitmap 失败: ${e.localizedMessage}", e)
        }
    }

    // ============================================================
    // 正文排版辅助（与原文件一致）
    // ============================================================

    private suspend fun setTypeImage(
        book: Book,
        src: String,
        textHeight: Float,
        imageStyle: String?,
        size: Size,
        click: String?
    ) {
        if (size.width > 0 && size.height > 0) {
            prepareNextPageIfNeed(durY)
            var height = size.height
            var width = size.width
            when (imageStyle?.uppercase()) {
                Book.imgStyleFull -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        prepareNextPageIfNeed(durY + height)
                    }
                }

                Book.imgStyleSingle -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    if (durY > 0f) {
                        prepareNextPageIfNeed()
                    }
                    if (height < visibleHeight) {
                        val adjustHeight = (visibleHeight - height) / 2f
                        durY = adjustHeight
                    }
                }

                else -> {
                    if (size.width > visibleWidth) {
                        height = size.height * visibleWidth / size.width
                        width = visibleWidth
                    }
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    prepareNextPageIfNeed(durY + height)
                }
            }
            val textLine = TextLine(isImage = true)
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += height
            textLine.lineBottom = durY + paddingTop
            val (start, end) = if (visibleWidth > width) {
                when (imageStyle?.uppercase()) {
                    "RIGHT" -> Pair(visibleWidth - width, visibleWidth)
                    "LEFT" -> Pair(0f, width)
                    else -> {
                        val adjustWidth = (visibleWidth - width) / 2f
                        Pair(adjustWidth, adjustWidth + width)
                    }
                }
            } else {
                Pair(0f, width)
            }
            textLine.addColumn(
                ImageColumn(start = absStartX + start.toFloat(), end = absStartX + end.toFloat(), src = src, click = click)
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ")
            pendingTextPage.addLine(textLine)
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private suspend fun setTypeHtml(
        imageStyle: String?,
        book: Book,
        htmlContent: String,
    ) {
        val textViewTagHandler = TextViewTagHandler()
        val spanned = htmlContent.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = textViewTagHandler)
        val width = visibleWidth
        val textPaint = contentPaint
        val textColor = ReadBookConfig.textColor
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val staticLayout = if (atLeastApi28) {
            StaticLayout.Builder.obtain(spanned, 0, spanned.length, textPaint, width)
                .setIncludePad(true)
                .setUseLineSpacingFromFallbacks(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                spanned,
                textPaint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                true
            )
        }
        val tempPaint = TextPaint(textPaint)
        for (lineIndex in 0 until staticLayout.lineCount) {
            val lineStart = staticLayout.getLineStart(lineIndex)
            val lineEnd = staticLayout.getLineEnd(lineIndex)
            if (lineStart == lineEnd) {
                continue
            }
            val textLine = TextLine(isHtml = true)
            val lineText = StringBuilder()
            val lineLeft = staticLayout.getLineLeft(lineIndex)
            textLine.startX = absStartX + lineLeft
            val mLineTop = staticLayout.getLineTop(lineIndex).toFloat()
            val mLineBottom = staticLayout.getLineBottom(lineIndex).toFloat()
            val lineHeight = mLineBottom - mLineTop
            prepareNextPageIfNeed(durY + lineHeight)
            textLine.upTopBottom(durY, lineHeight, textPaint.fontMetrics)

            val columns = mutableListOf<BaseColumn>()
            var charIndex = lineStart
            while (charIndex < lineEnd) {
                val char = spanned[charIndex].toString()
                lineText.append(char)
                if (char == "\n") {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f
                    charIndex++
                    continue
                }
                val charX = staticLayout.getPrimaryHorizontal(charIndex)
                val textSize = extractTextSize(spanned, charIndex, textPaint.textSize)
                val textColor = extractTextColor(spanned, charIndex)
                val linkUrl = extractLinkUrl(spanned, charIndex)
                val charRight = if (charIndex + 1 < lineEnd) {
                    staticLayout.getPrimaryHorizontal(charIndex + 1)
                } else {
                    tempPaint.textSize = textSize
                    val charWidth = tempPaint.measureText(char)
                    charX + charWidth
                }
                var needAddText = true
                spanned.getSpans(charIndex, charIndex + 1, ImageSpan::class.java).firstOrNull()?.let { span ->
                    val source = span.source ?: return@let
                    val urlMatcher = paramPattern.matcher(source)
                    if (urlMatcher.find()) {
                        val urlOptionStr = source.substring(urlMatcher.end())
                        val urlOption = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return@let
                        var iStyle = urlOption["style"]
                        val width = urlOption["width"]
                        val click = urlOption["click"]
                        var imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        width?.let {
                            if (width.endsWith("%")) {
                                width.dropLast(1).toIntOrNull()?.let { percentage ->
                                    val imgWidth = visibleWidth * percentage / 100
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                                }
                            } else {
                                width.toIntOrNull()?.let { width ->
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(width, sizeHeight * width / sizeWidth)
                                }
                            }
                        }
                        if (iStyle == null) {
                            iStyle = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        when (iStyle?.uppercase()) {
                            "TEXT" -> {
                                ImageProvider.cacheImage(book, source, ReadBook.bookSource)
                                columns.add(
                                    ImageColumn(
                                        start = absStartX + charX,
                                        end = absStartX + charRight,
                                        src = source,
                                        click = click
                                    )
                                )
                            }
                            else -> {
                                setTypeImage(
                                    book,
                                    source,
                                    contentPaintTextHeight,
                                    iStyle,
                                    imgSize,
                                    click
                                )
                            }
                        }
                    } else {
                        val imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        setTypeImage(
                            book,
                            source,
                            contentPaintTextHeight,
                            imageStyle,
                            imgSize,
                            null
                        )
                    }
                    needAddText = false
                }
                spanned.getSpans(charIndex, charIndex + 1, ReplacementSpan::class.java).firstOrNull()?.let { _ ->
                    if (char == HR_PLACE_CHAR) {
                        columns.add(
                            TextHtmlColumn(
                                absStartX.toFloat(),
                                (absStartX + width - paddingRight).toFloat(),
                                HR_PLACE_STR,
                                textSize,
                                textColor,
                                linkUrl
                            )
                        )
                        needAddText = false
                    }
                }
                if (needAddText) {
                    columns.add(
                        TextHtmlColumn(
                            absStartX + charX,
                            absStartX + charRight,
                            char,
                            textSize,
                            textColor,
                            linkUrl
                        )
                    )
                }
                charIndex++
                if (charIndex == lineEnd && lineIndex == staticLayout.lineCount - 1) {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f
                }
            }
            textLine.text = lineText.toString()
            if (textFullJustify && !textLine.isParagraphEnd) {
                justifyHtmlLine(columns, textLine, visibleWidth)
            } else {
                textLine.addColumns(columns)
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += lineHeight * lineSpacingExtra + if (ReadBookConfig.underlineMode != 0) ChapterProvider.underlineExtraHeight else 0f
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
    }

    private fun justifyHtmlLine(
        columns: MutableList<BaseColumn>,
        textLine: TextLine,
        lineWidth: Int
    ) {
        if (columns.isEmpty()) return
        val firstCol = columns.first()
        val lastCol = columns.last()
        val currentWidth = lastCol.end - firstCol.start
        val residualWidth = lineWidth - currentWidth

        if (residualWidth <= 0) {
            textLine.addColumns(columns)
            return
        }

        val spaceCount = columns.count {
            (it as? TextBaseColumn)?.charData == " "
        }

        if (spaceCount > 1) {
            val spaceIncrement = residualWidth / spaceCount
            textLine.wordSpacing = spaceIncrement
            var currentX = firstCol.start
            for (i in columns.indices) {
                val col = columns[i]
                val width = col.end - col.start
                if ((col as? TextBaseColumn)?.charData == " " && i != columns.lastIndex) {
                    col.start = currentX
                    col.end = currentX + width + spaceIncrement
                    currentX = col.end
                } else {
                    col.start = currentX
                    col.end = currentX + width
                    currentX = col.end
                }
                textLine.addColumn(col)
            }
        } else {
            val gapCount = columns.lastIndex
            if (gapCount > 0) {
                val charIncrement = residualWidth / gapCount
                var currentX = firstCol.start
                for (i in columns.indices) {
                    val col = columns[i]
                    val width = col.end - col.start
                    if (i != columns.lastIndex) {
                        col.start = currentX
                        col.end = currentX + width + charIncrement
                        currentX = col.end
                    } else {
                        col.start = currentX
                        col.end = currentX + width
                    }
                    textLine.addColumn(col)
                }
            } else {
                textLine.addColumns(columns)
            }
        }
    }

    private fun extractTextSize(spanned: Spanned, index: Int, defaultSize: Float): Float {
        val relativeSpans = spanned.getSpans(index, index + 1, RelativeSizeSpan::class.java)
        relativeSpans.firstOrNull()?.let { span ->
            return defaultSize * span.sizeChange
        }
        return defaultSize
    }

    private fun extractTextColor(spanned: Spanned, index: Int): Int? {
        val foregroundSpans = spanned.getSpans(index, index + 1, ForegroundColorSpan::class.java)
        return foregroundSpans.firstOrNull()?.foregroundColor
    }

    private fun extractLinkUrl(spanned: Spanned, index: Int): String? {
        val urlSpans = spanned.getSpans(index, index + 1, URLSpan::class.java)
        urlSpans.firstOrNull()?.let { span ->
            return span.url
        }
        return null
    }

    @Suppress("DEPRECATION")
    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null,
        clickList: LinkedList<String?>?
    ) {
        val widthsArray = allocateFloatArray(text.length)
        textPaint.getTextWidthsCompat(text, widthsArray, reviewCharWidth)
        val layout = if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(text, textPaint, visibleWidth, words, widths, indentSize)
        } else {
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        durY = when {
            emptyContent && textPages.isEmpty() -> {
                val textPage = pendingTextPage
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> {
                when (imageStyle?.uppercase()) {
                    Book.imgStyleSingle -> {
                        val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                        if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                    }

                    else -> durY + titleTopSpacing
                }
            }

            else -> durY
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            prepareNextPageIfNeed(durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            val desiredWidth = widths.fastSum()
            textLine.text = lineText
            when (lineIndex) {
                0 if layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    addCharsToLineFirst(
                        book, absStartX, textLine, words, textPaint,
                        desiredWidth, widths, srcList, clickList
                    )
                }
                layout.lineCount - 1 -> {
                    val startX = if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else {
                        0f
                    }
                    addCharsToLineNatural(
                        book, absStartX, textLine, words,
                        startX, !isTitle && lineIndex == 0, widths, srcList, clickList
                    )
                }
                else -> {
                    if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            startX, false, widths, srcList, clickList
                        )
                    } else {
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths, srcList, clickList
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra + if (!isTitle && ReadBookConfig.underlineMode != 0) ChapterProvider.underlineExtraHeight else 0f
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.lastOrNull()?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                x, true, textWidths, srcList, clickList
            )
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = ChapterProvider.indentChar,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1, textPaint,
                desiredWidth, x, textWidths1, srcList, clickList
            )
        }
    }

    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, srcList,
                clickList
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else {
                    (x + cw)
                }
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList,
                    clickList
                )
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            textLine.extraLetterSpacingOffsetX = -d / 2
            textLine.extraLetterSpacing = d / textPaint.textSize
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList,
                    clickList
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, srcList, clickList)
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        val column = when {
            !srcList.isNullOrEmpty() && (char == srcReplaceStr || char == reviewStr) -> {
                val src = srcList.removeFirst()
                val click = clickList?.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src,
                    click = click
                )
            }

            else -> {
                TextColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    charData = char
                )
            }
        }
        textLine.addColumn(column)
    }

    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--
            offset++
            columns[columns.lastIndex - 1]
        } else {
            columns.last()
        }
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            val textPage = pendingTextPage
            if (textPage.height < durY) {
                textPage.height = durY
            }
            if (doublePage && absStartX < viewWidth / 2) {
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                if (textPage.leftLineSize == 0) {
                    textPage.leftLineSize = textPage.lineSize
                }
                textPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive()
                onPageCompleted()
                pendingTextPage = TextPage()
                stringBuilder.clear()
                absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) {
            floatArray = FloatArray(size)
        }
        return floatArray
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }
}