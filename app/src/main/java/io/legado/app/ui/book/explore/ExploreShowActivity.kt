package io.legado.app.ui.book.explore

import io.legado.app.utils.dpToPx

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { ExploreShowAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var oldPage = -1
    private var isClearAll = false
    private var isLoading = false

    // ========== 分页相关 ==========
    private var allBooks = mutableListOf<SearchBook>()
    private var pageSize = 10
    private var currentDisplayPage = 1
    private var totalDisplayPage = 1
    private var hasMoreData = true
    private var isFirstLoad = true

    private lateinit var pageBar: PageBarView

    // ========== 滑动切页相关 ==========
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f  // 水平滑动触发阈值（px）

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initRecyclerView()
        initPageBar()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) { key ->
            val bundle = Bundle()
            bundle.putString(key, null)
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundle)
        }
        viewModel.pageLiveData.observe(this) {
            // 保留原逻辑
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        // 强制列表左右各15dp边距
        val sidePadding = dip2px(15f)
        binding.recyclerView.setPadding(sidePadding, binding.recyclerView.paddingTop, sidePadding, binding.recyclerView.paddingBottom)
        binding.recyclerView.clipToPadding = false
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (isLoading) return
                if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })

        // ========== 左右滑动切页 ==========
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = e.x
                        touchStartY = e.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.x - touchStartX
                        val dy = e.y - touchStartY

                        // 水平滑动距离超过阈值，且水平滑动大于垂直滑动
                        if (abs(dx) > swipeThreshold && abs(dx) > abs(dy)) {
                            if (dx < 0) {
                                // 向左滑动 → 下一页
                                if (currentDisplayPage < totalDisplayPage) {
                                    currentDisplayPage++
                                    refreshCurrentPage()
                                } else if (hasMoreData && !isLoading) {
                                    loadNextPageData()
                                }
                            } else {
                                // 向右滑动 → 上一页
                                if (currentDisplayPage > 1) {
                                    currentDisplayPage--
                                    refreshCurrentPage()
                                }
                            }
                            return true // 拦截事件，不传递给子View
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        // 计算每页显示数量
        binding.recyclerView.post {
            calculatePageSize()
        }
    }

    /**
     * 初始化顶栏分页栏 - 添加到 titleBar 的 toolbar 右侧
     */
    private fun initPageBar() {
        pageBar = PageBarView(this).apply {
            onPrevClick = {
                if (currentDisplayPage > 1) {
                    currentDisplayPage--
                    refreshCurrentPage()
                }
            }
            onPageClick = {
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(currentDisplayPage)
                    .show { targetPage ->
                        if (targetPage != currentDisplayPage) {
                            currentDisplayPage = targetPage
                            refreshCurrentPage()
                        }
                    }
            }
            onNextClick = {
                if (currentDisplayPage < totalDisplayPage) {
                    currentDisplayPage++
                    refreshCurrentPage()
                } else if (hasMoreData && !isLoading) {
                    loadNextPageData()
                }
            }
        }

        // 添加到 titleBar 的 toolbar 右侧
        val toolbar = binding.titleBar.toolbar
        val params = androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        toolbar.addView(pageBar, params)
    }

    /**
     * 根据屏幕高度自动计算每页显示数量
     */
    private fun calculatePageSize() {
        val recyclerViewHeight = binding.recyclerView.height
        if (recyclerViewHeight > 0) {
            val itemHeight = dip2px(120f)
            pageSize = (recyclerViewHeight / itemHeight).coerceAtLeast(1)
            if (allBooks.isNotEmpty()) {
                refreshCurrentPage()
            }
        }
    }

    private fun dip2px(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * 刷新当前显示页
     */
    private fun refreshCurrentPage() {
        val startIndex = (currentDisplayPage - 1) * pageSize
        val endIndex = min(startIndex + pageSize, allBooks.size)

        if (startIndex < allBooks.size) {
            val pageBooks = allBooks.subList(startIndex, endIndex)
            adapter.setItems(pageBooks.toList())
            binding.recyclerView.scrollToPosition(0)
        }

        updatePageBar()

        // 如果当前页是最后一页且还有更多数据，自动加载下一页
        if (currentDisplayPage >= totalDisplayPage && hasMoreData && !isLoading) {
            loadNextPageData()
        }
    }

    /**
     * 更新分页栏状态
     */
    private fun updatePageBar() {
        totalDisplayPage = if (allBooks.isEmpty()) 1 else ceil(allBooks.size.toDouble() / pageSize).toInt()
        pageBar.setPage(currentDisplayPage, totalDisplayPage)
        pageBar.setPrevEnabled(currentDisplayPage > 1)
        pageBar.setNextEnabled((currentDisplayPage < totalDisplayPage) || (hasMoreData && !isLoading))
    }

    /**
     * 加载下一页网络数据
     */
    private fun loadNextPageData() {
        if (isLoading || !hasMoreData) return
        isLoading = true
        loadMoreView.startLoad()
        viewModel.explore()
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (isLoading) return
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            isLoading = true
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.explore(oldPage)
            binding.recyclerView.postDelayed({ isLoading = false }, 300)
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        isLoading = false

        if (books.isEmpty()) {
            hasMoreData = false
            if (allBooks.isEmpty()) {
                loadMoreView.noMore(getString(R.string.empty))
            } else {
                loadMoreView.noMore()
            }
            updatePageBar()
            return
        }

        // 添加新数据到总列表（去重）
        books.forEach { newBook ->
            if (allBooks.none { it.name == newBook.name && it.author == newBook.author }) {
                allBooks.add(newBook)
            }
        }

        // 重新计算总页数
        totalDisplayPage = ceil(allBooks.size.toDouble() / pageSize).toInt()

        // 首次加载：计算分页大小并刷新
        if (isFirstLoad) {
            isFirstLoad = false
            val recyclerViewHeight = binding.recyclerView.height
            if (recyclerViewHeight > 0) {
                val itemHeight = dip2px(120f)
                pageSize = (recyclerViewHeight / itemHeight).coerceAtLeast(1)
                currentDisplayPage = 1
                refreshCurrentPage()
            } else {
                binding.recyclerView.post {
                    calculatePageSize()
                    currentDisplayPage = 1
                    refreshCurrentPage()
                }
            }
        } else {
            refreshCurrentPage()
        }

        updatePageBar()
    }

    private fun upDataTop(books: List<SearchBook>) {
        loadMoreViewTop.stopLoad()

        if (books.isEmpty()) return

        // 添加新数据到总列表头部（去重）
        val newBooks = books.filter { newBook ->
            allBooks.none { it.name == newBook.name && it.author == newBook.author }
        }
        allBooks.addAll(0, newBooks)

        // 重新计算总页数
        totalDisplayPage = ceil(allBooks.size.toDouble() / pageSize).toInt()

        // 当前页码需要调整（因为前面插入了数据）
        val insertedPages = ceil(newBooks.size.toDouble() / pageSize).toInt()
        currentDisplayPage += insertedPages

        refreshCurrentPage()
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    // ========== 顶栏分页栏自定义 View ==========
    class PageBarView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : LinearLayout(context, attrs, defStyleAttr) {

        var onPrevClick: (() -> Unit)? = null
        var onPageClick: (() -> Unit)? = null
        var onNextClick: (() -> Unit)? = null

        private val prevBtn: TextView
        private val pageBtn: TextView
        private val nextBtn: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 背景透明
            setBackgroundColor(Color.TRANSPARENT)

            val padding = dpToPx(4)
            setPadding(padding, 0, padding, 0)

            // 上一页按钮
            prevBtn = TextView(context).apply {
                text = "◀"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                setOnClickListener { onPrevClick?.invoke() }
            }
            addView(prevBtn)

            // 页码按钮
            pageBtn = TextView(context).apply {
                text = "1 / 1"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(32)).apply {
                    leftMargin = dpToPx(8)
                    rightMargin = dpToPx(8)
                }
                setOnClickListener { onPageClick?.invoke() }
            }
            addView(pageBtn)

            // 下一页按钮
            nextBtn = TextView(context).apply {
                text = "▶"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                setOnClickListener { onNextClick?.invoke() }
            }
            addView(nextBtn)
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
        }

        fun setPage(current: Int, total: Int) {
            pageBtn.text = "$current / $total"
        }

        fun setPrevEnabled(enabled: Boolean) {
            prevBtn.alpha = if (enabled) 1.0f else 0.3f
            prevBtn.isClickable = enabled
        }

        fun setNextEnabled(enabled: Boolean) {
            nextBtn.alpha = if (enabled) 1.0f else 0.3f
            nextBtn.isClickable = enabled
        }
    }
}
