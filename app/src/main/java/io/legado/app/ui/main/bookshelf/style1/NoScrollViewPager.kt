package io.legado.app.ui.main.bookshelf.style1

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

class NoScrollViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    // 禁止手指左右滑动
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    // 强制所有切换都关闭平滑动画
    override fun setCurrentItem(item: Int, smoothScroll: Boolean) {
        super.setCurrentItem(item, false)
    }

    override fun setCurrentItem(item: Int) {
        super.setCurrentItem(item, false)
    }
}