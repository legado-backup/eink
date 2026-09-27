package io.legado.app.ui.book.manga.recyclerview

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.utils.findCenterViewPosition
import kotlin.math.abs

class WebtoonRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private var isZooming = false
    private var atLastPosition = false
    private var atFirstPosition = false
    private var halfWidth = 0
    private var halfHeight = 0
    private var originalHeight = 0
    private var heightSet = false
    private var firstVisibleItemPosition = 0
    private var lastVisibleItemPosition = 0
    private var currentScale = DEFAULT_RATE
    private var mLastCenterViewPosition = 0

    private var mPreScrollListener: IComicPreScroll? = null
    private var mNestedPreScrollListener: IComicPreScroll? = null
    private val listener = GestureListener()
    private val detector = Detector()

    var doubleTapZoom = true
    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null
    var disableMangaScale = false

    // ===== 滑动翻页相关 =====
    var pageTurnListener: ((Int) -> Unit)? = null  // direction: 1=下一页, -1=上一页
    private var mStartX = 0f
    private var mStartY = 0f
    private var mIsDragging = false
    private val mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** 翻页所需的最小滑动距离（像素），超过这个距离才触发翻页 */
    private val mPageTurnThreshold = mTouchSlop * 2

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        halfWidth = MeasureSpec.getSize(widthSpec) / 2
        halfHeight = MeasureSpec.getSize(heightSpec) / 2
        if (!heightSet) {
            originalHeight = MeasureSpec.getSize(heightSpec)
            heightSet = true
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 非缩放模式下：完全接管触摸事件，屏蔽所有默认滑动
        if (currentScale <= 1f) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mStartX = e.x
                    mStartY = e.y
                    mIsDragging = false
                    // 同时传递给 detector 用于检测双击/长按
                    detector.onTouchEvent(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - mStartX
                    val dy = e.y - mStartY
                    if (!mIsDragging && (abs(dx) > mTouchSlop || abs(dy) > mTouchSlop)) {
                        mIsDragging = true
                    }
                    // 边界检测：如果当前位置是章节第一页或最后一页，且继续向边界外滑动，
                    // 强制标记为 dragging，确保 ACTION_UP 时能触发翻页
                    if (!mIsDragging) {
                        val lm = layoutManager as? LinearLayoutManager
                        val currentAdapter = adapter as? MangaAdapter
                        if (lm != null && currentAdapter != null) {
                            val currentPos = findCenterViewPosition()
                            if (currentPos != NO_POSITION && currentPos < currentAdapter.itemCount) {
                                val currentItem = currentAdapter.getItem(currentPos)
                                if (currentItem is MangaPage) {
                                    val isFirstPageOfChapter = currentItem.index == 0
                                    val isLastPageOfChapter = currentItem.index == currentItem.imageCount - 1
                                    
                                    // 向右滑且是章节第一页 → 上一章
                                    if (isFirstPageOfChapter && dx > mTouchSlop) {
                                        mIsDragging = true
                                    }
                                    // 向左滑且是章节最后一页 → 下一章
                                    if (isLastPageOfChapter && dx < -mTouchSlop) {
                                        mIsDragging = true
                                    }
                                }
                            }
                        }
                    }
                    // 传递给 detector（缩放时有用）
                    detector.onTouchEvent(e)
                    // 完全阻止默认滑动行为
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - mStartX
                    val dy = e.y - mStartY

                    if (!mIsDragging) {
                        // 没有拖动 → 这是点击，触发 tapListener（修复点击不出菜单）
                        tapListener?.invoke(e)
                    } else {
                        // 有拖动 → 判断滑动方向翻页
                        if (abs(dx) > abs(dy) && abs(dx) > mPageTurnThreshold) {
                            if (dx < 0) {
                                pageTurnListener?.invoke(1)   // 向左滑 → 下一页
                            } else {
                                pageTurnListener?.invoke(-1)  // 向右滑 → 上一页
                            }
                        }
                    }
                    detector.onTouchEvent(e)
                    mIsDragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mIsDragging = false
                    detector.onTouchEvent(e)
                    return true
                }
            }
        }
        // 缩放模式下走原来的 detector 逻辑
        return detector.onTouchEvent(e) || super.onTouchEvent(e)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val layoutManager = layoutManager as LinearLayoutManager
        lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
        firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

        val position = findCenterViewPosition()
        if (position != NO_POSITION && position != mLastCenterViewPosition) {
            mLastCenterViewPosition = position
            mPreScrollListener?.onPreScrollListener(this, dx, dy, position)
        }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        val layoutManager = layoutManager
        val visibleItemCount = layoutManager?.childCount ?: 0
        val totalItemCount = layoutManager?.itemCount ?: 0
        atLastPosition = visibleItemCount > 0 && lastVisibleItemPosition == totalItemCount - 1
        atFirstPosition = firstVisibleItemPosition == 0
    }

    override fun fling(velocityX: Int, velocityY: Int): Boolean {
        // 禁用 fling 惯性滚动，完全无动画
        return false
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        val position = findCenterViewPosition()
        mNestedPreScrollListener?.onPreScrollListener(this, dx, dy, position)
        return super.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    }

    private fun getPositionX(positionX: Float): Float {
        if (currentScale < 1) return 0f
        val maxPositionX = halfWidth * (currentScale - 1)
        return positionX.coerceIn(-maxPositionX, maxPositionX)
    }

    private fun getPositionY(positionY: Float): Float {
        if (currentScale < 1) return (originalHeight / 2 - halfHeight).toFloat()
        val maxPositionY = halfHeight * (currentScale - 1)
        return positionY.coerceIn(-maxPositionY, maxPositionY)
    }

    private fun zoom(
        fromRate: Float,
        toRate: Float,
        fromX: Float,
        toX: Float,
        fromY: Float,
        toY: Float,
    ) {
        isZooming = true
        val animatorSet = AnimatorSet()
        val translationXAnimator = ValueAnimator.ofFloat(fromX, toX)
        translationXAnimator.addUpdateListener { animation -> x = animation.animatedValue as Float }

        val translationYAnimator = ValueAnimator.ofFloat(fromY, toY)
        translationYAnimator.addUpdateListener { animation -> y = animation.animatedValue as Float }

        val scaleAnimator = ValueAnimator.ofFloat(fromRate, toRate)
        scaleAnimator.addUpdateListener { animation ->
            currentScale = animation.animatedValue as Float
            setScaleRate(currentScale)
        }
        animatorSet.playTogether(translationXAnimator, translationYAnimator, scaleAnimator)
        animatorSet.duration = ANIMATOR_DURATION_TIME.toLong()
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.start()
        animatorSet.doOnEnd {
            isZooming = false
            currentScale = toRate
        }
    }

    fun zoomFling(velocityX: Int, velocityY: Int): Boolean {
        if (currentScale <= 1f) return false

        val distanceTimeFactor = 0.4f
        val animatorSet = AnimatorSet()

        if (velocityX != 0) {
            val dx = (distanceTimeFactor * velocityX / 2)
            val newX = getPositionX(x + dx)
            val translationXAnimator = ValueAnimator.ofFloat(x, newX)
            translationXAnimator.addUpdateListener { animation ->
                x = getPositionX(animation.animatedValue as Float)
            }
            animatorSet.play(translationXAnimator)
        }
        if (velocityY != 0 && (atFirstPosition || atLastPosition)) {
            val dy = (distanceTimeFactor * velocityY / 2)
            val newY = getPositionY(y + dy)
            val translationYAnimator = ValueAnimator.ofFloat(y, newY)
            translationYAnimator.addUpdateListener { animation ->
                y = getPositionY(animation.animatedValue as Float)
            }
            animatorSet.play(translationYAnimator)
        }

        animatorSet.duration = 400
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.start()

        return true
    }

    fun resetZoom() {
        zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
    }

    private fun zoomScrollBy(dx: Int, dy: Int) {
        if (dx != 0) {
            x = getPositionX(x + dx)
        }
        if (dy != 0) {
            y = getPositionY(y + dy)
        }
    }

    private fun setScaleRate(rate: Float) {
        scaleX = rate
        scaleY = rate
    }

    fun onScale(scaleFactor: Float) {
        currentScale *= scaleFactor
        currentScale = currentScale.coerceIn(
            MIN_RATE,
            MAX_SCALE_RATE,
        )

        setScaleRate(currentScale)

        layoutParams.height = if (currentScale < 1) {
            (originalHeight / currentScale).toInt()
        } else {
            originalHeight
        }
        halfHeight = layoutParams.height / 2

        if (currentScale != DEFAULT_RATE) {
            x = getPositionX(x)
            y = getPositionY(y)
        } else {
            x = 0f
            y = 0f
        }

        requestLayout()
    }

    fun onScaleBegin() {
        if (detector.isDoubleTapping) {
            detector.isQuickScaling = true
        }
    }

    fun onScaleEnd() {
        if (scaleX < MIN_RATE) {
            zoom(currentScale, MIN_RATE, x, 0f, y, 0f)
        }
    }

    inner class GestureListener : GestureDetectorWithLongTap.Listener() {

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            // 点击事件改由 onTouchEvent 的 ACTION_UP 统一处理
            // 这里不再触发，避免重复
            return false
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            detector.isDoubleTapping = true
            return false
        }

        fun onDoubleTapConfirmed(ev: MotionEvent) {
            if (!isZooming && doubleTapZoom) {
                if (scaleX != DEFAULT_RATE) {
                    zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
                } else {
                    val toScale = 2f
                    val toX = (halfWidth - ev.x) * (toScale - 1)
                    val toY = (halfHeight - ev.y) * (toScale - 1)
                    zoom(DEFAULT_RATE, toScale, 0f, toX, 0f, toY)
                }
            }
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            if (longTapListener?.invoke(ev) == true) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    inner class Detector : GestureDetectorWithLongTap(context, listener) {

        private var scrollPointerId = 0
        private var downX = 0
        private var downY = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isZoomDragging = false
        var isDoubleTapping = false
        var isQuickScaling = false

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val actionIndex = ev.actionIndex

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollPointerId = ev.getPointerId(0)
                    downX = (ev.x + 0.5f).toInt()
                    downY = (ev.y + 0.5f).toInt()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    scrollPointerId = ev.getPointerId(actionIndex)
                    downX = (ev.getX(actionIndex) + 0.5f).toInt()
                    downY = (ev.getY(actionIndex) + 0.5f).toInt()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (disableMangaScale) {
                        return super.onTouchEvent(ev)
                    }
                    if (isDoubleTapping && isQuickScaling) {
                        return true
                    }

                    val index = ev.findPointerIndex(scrollPointerId)
                    if (index < 0) {
                        return false
                    }

                    val x = (ev.getX(index) + 0.5f).toInt()
                    val y = (ev.getY(index) + 0.5f).toInt()
                    var dx = x - downX
                    var dy = if (atFirstPosition || atLastPosition) y - downY else 0

                    if (!isZoomDragging && currentScale > 1f) {
                        var startScroll = false

                        if (abs(dx) > touchSlop) {
                            if (dx < 0) {
                                dx += touchSlop
                            } else {
                                dx -= touchSlop
                            }
                            startScroll = true
                        }
                        if (abs(dy) > touchSlop) {
                            if (dy < 0) {
                                dy += touchSlop
                            } else {
                                dy -= touchSlop
                            }
                            startScroll = true
                        }

                        if (startScroll) {
                            isZoomDragging = true
                        }
                    }

                    if (isZoomDragging) {
                        zoomScrollBy(dx, dy)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapping && !isQuickScaling && !disableMangaScale) {
                        listener.onDoubleTapConfirmed(ev)
                    }
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    fun setPreScrollListener(iComicPreScroll: IComicPreScroll) {
        mPreScrollListener = iComicPreScroll
    }

    fun setNestedPreScrollListener(iComicPreScroll: IComicPreScroll) {
        mNestedPreScrollListener = iComicPreScroll
    }

    fun interface IComicPreScroll {
        fun onPreScrollListener(recyclerView: RecyclerView, dx: Int, dy: Int, position: Int)
    }
}

private const val ANIMATOR_DURATION_TIME = 200
private const val MIN_RATE = 0.5f
private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f
