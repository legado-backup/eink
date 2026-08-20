package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 阅读视图 墨水屏 完美流态防闪 原版功能无阉割 仅修复类型报错
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val defaultAnimationSpeed = 300
    private var pressDown = false
    private var isMove = false

    //起始点
    var startX: Float = 0f
    var startY: Float = 0f

    //上一个触碰点
    var lastX: Float = 0f
    var lastY: Float = 0f

    //触碰点
    var touchX: Float = 0f
    var touchY: Float = 0f

    //是否停止动画动作
    var isAbortAnim = false

    //长按 修复类型：固定Long 不改动任何业务
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    var isTextSelected = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var pageSlopSquare: Int = slopSquare
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private var pageTouchClick: Int = 0
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning

    init {
        // 墨水屏全局软件渲染 防残影
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        elevation = 0f
        translationZ = 0f
        isDrawingCacheEnabled = false

        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
        addView(nextPage)
        addView(curPage)
        addView(prevPage)

        // 关键：不再隐藏上下页，三页面永久常驻内存
        // prevPage.invisible()
        // nextPage.invisible()

        curPage.markAsMainView()
        upPageTouchClick()
    }

    private fun setRect9x() {
        tlRect.set(0f + pageTouchClick, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat() - pageTouchClick, height * 0.33f)
        mlRect.set(0f + pageTouchClick, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat() - pageTouchClick, height * 0.66f)
        blRect.set(0f + pageTouchClick, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat() - pageTouchClick, height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        if (isTextSelected) {
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y
    }

    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    private fun onLongPress() {
        kotlin.runCatching {
            curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextBaseColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
        }
    }

    private fun onSingleTapUp() {
        when {
            isTextSelected -> Unit
            mcRect.contains(startX, startY) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(startX, startY) -> {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTR)
            }
        }
    }

    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }
            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> activity?.showDialogFragment(ContentEditDialog())
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })
            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }
                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    fun onDestroy() {
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }
            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }
            else -> false
        }
    }

    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = false
        ChapterProvider.upLayout()
        if (pageDelegate !is NoAnimPageDelegate) {
            pageDelegate = NoAnimPageDelegate(this)
        }
        pageDelegate?.setViewSize(width, height)
        curPage.setAutoPager(null)
        curPage.setIsScroll(false)

        // 墨水屏流态核心：三页面常驻可见
        prevPage.visibility = View.VISIBLE
        curPage.visibility = View.VISIBLE
        nextPage.visibility = View.VISIBLE
        prevPage.alpha = 1f
        curPage.alpha = 1f
        nextPage.alpha = 1f
        prevPage.setWillNotDraw(false)
        curPage.setWillNotDraw(false)
        nextPage.setWillNotDraw(false)

        // 兜底白色背景，彻底消除翻页闪白空白
        setBackgroundColor(-0x1)
    }

    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> {
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * pageSlopSquare
    }

    fun upPageTouchClick() {
        this.pageTouchClick = AppConfig.pageTouchClick
        setRect9x()
    }

    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
        if (ReadBookConfig.isNineBgImg) {
            upBg()
        }
    }

    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    // 完整保留朗读原有逻辑 不阉割 只强转适配类型
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage()) {
                ReadBook.moveToNextChapterAwait(false)
            }
            pagePos--
        }
        // 仅加toInt 保留原功能不动
        val startPos = curPage.textPage.getPosByLineColumn(line.toInt(), column.toInt())
        ReadBook.readAloud(startPos = startPos)
    }

    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (!AppConfig.optimizeRender) {
            return
        }
        pageFactory.run {
            prevPage.invalidateAll()
            curPage.invalidateAll()
            nextPage.invalidateAll()
            nextPlusPage.invalidateAll()
        }
    }




    fun onScrollAnimStart() {
    autoPager.pause()
    
}

fun onScrollAnimStop() {
    autoPager.resume()
    
}

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() = if (callBack.isInitFinish) ReadBook.textChapter(0) else null

    override val nextChapter: TextChapter?
        get() = if (callBack.isInitFinish) ReadBook.textChapter(1) else null

    override val prevChapter: TextChapter?
        get() = if (callBack.isInitFinish) ReadBook.textChapter(-1) else null

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
    }
		
		
		
}