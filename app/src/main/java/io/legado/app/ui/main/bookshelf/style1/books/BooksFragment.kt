package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBooksBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class BooksFragment() : BaseFragment(R.layout.fragment_books),
    BaseBooksAdapter.CallBack {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        bundle.putBoolean("onlyUpdateRead", group.onlyUpdateRead)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBooksBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }

    private val userLayout by lazy { AppConfig.bookshelfLayout }
    private val isGrid: Boolean get() = userLayout >= 2
    private val autoSpanCount: Int by lazy { computeAutoSpanCount() }

    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (isGrid) {
            BooksAdapterGrid(requireContext(), this)
        } else {
            when (userLayout) {
                0 -> BooksAdapterList(requireContext(), this, this, viewLifecycleOwner.lifecycle)
                else -> BooksAdapterList2(requireContext(), this, this, viewLifecycleOwner.lifecycle)
            }
        }
    }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true
    private var onlyUpdateRead = false
    private var itemCount = 0
    private var totalRows = 0

    private var allBooks: List<Book> = emptyList()
    private var displayBooks: List<Book> = emptyList()
    private var currentPage = 1
    private var totalPages = 1
    private var pageSize = 20

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f

    private var pageBar: PageBarView? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            onlyUpdateRead = it.getBoolean("onlyUpdateRead", false)
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        initBottomPageBar()
        upRecyclerData()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun computeAutoSpanCount(): Int {
        val dm = resources.displayMetrics
        val screenWdp = dm.widthPixels / dm.density
        val usable = screenWdp - bookshelfMargin * 2f
        val perColumn = 100f + bookshelfMargin
        return (usable / perColumn).toInt().coerceIn(2, 6)
    }

    private fun initBottomPageBar() {
        if (!isGrid) return
        val root = binding.root as? ViewGroup ?: return

        val bar = PageBarView(requireContext()).apply {
            onPrevClick = {
                if (currentPage > 1) {
                    currentPage--
                    applyPaging()
                    binding.rvBookshelf.scrollToPosition(0)
                    updatePageBar()
                }
            }
            onPageClick = {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(currentPage)
                    .show { targetPage ->
                        val t = targetPage.coerceIn(1, totalPages)
                        if (t != currentPage) {
                            currentPage = t
                            applyPaging()
                            binding.rvBookshelf.scrollToPosition(0)
                            updatePageBar()
                        }
                    }
            }
            onNextClick = {
                if (currentPage < totalPages) {
                    currentPage++
                    applyPaging()
                    binding.rvBookshelf.scrollToPosition(0)
                    updatePageBar()
                }
            }
        }
        pageBar = bar

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
        root.addView(bar, lp)

        bar.post {
            val barH = bar.height
            (binding.refreshLayout.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = barH
                binding.refreshLayout.layoutParams = it
            }
            binding.rvBookshelf.updatePadding(bottom = dp(15))
            calculatePageSize()
        }

        updatePageBar()
    }

    private fun updatePageBar() {
        pageBar?.setPage(currentPage, totalPages)
        pageBar?.setPrevEnabled(currentPage > 1)
        pageBar?.setNextEnabled(currentPage < totalPages)
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)

        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(allBooks, onlyUpdateRead)
        }

        if (isGrid) {
            binding.rvBookshelf.setPadding(0, 0, 0, dp(15))
            binding.rvBookshelf.clipToPadding = false

            binding.rvBookshelf.layoutManager = GridLayoutManager(context, autoSpanCount)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksGridRecycledViewPool)

            binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    val spanCount = autoSpanCount
                    val rowIndex = position / spanCount
                    if (rowIndex == 0) {
                        outRect.set(bookshelfMargin, bookshelfMargin + 24, bookshelfMargin, bookshelfMargin)
                    } else {
                        outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                    }
                }
            })

            binding.rvBookshelf.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            touchStartX = e.x
                            touchStartY = e.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(e.x - touchStartX)
                            val dy = abs(e.y - touchStartY)
                            if (dx > dy + 10) {
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                            val dx = e.x - touchStartX
                            val dy = e.y - touchStartY
                            if (abs(dx) > swipeThreshold && abs(dx) > abs(dy)) {
                                if (dx < 0) {
                                    if (currentPage < totalPages) {
                                        currentPage++
                                        applyPaging()
                                        rv.scrollToPosition(0)
                                        updatePageBar()
                                    }
                                } else {
                                    if (currentPage > 1) {
                                        currentPage--
                                        applyPaging()
                                        rv.scrollToPosition(0)
                                        updatePageBar()
                                    }
                                }
                                return true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })

            binding.rvBookshelf.post {
                calculatePageSize()
                binding.rvBookshelf.postDelayed({
                    val firstChild = binding.rvBookshelf.getChildAt(0)
                    if (firstChild != null && firstChild.height > 0) {
                        calculatePageSize()
                    }
                }, 100)
            }
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksListRecycledViewPool)

            binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == 0) {
                        outRect.set(0, bookshelfMargin + 24, 0, bookshelfMargin)
                    } else {
                        outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                    }
                }
            })
        }

        booksAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.rvBookshelf.adapter = booksAdapter

        startLastUpdateTimeJob()
    }

    private fun calculatePageSize() {
        val rv = binding.rvBookshelf
        val h = rv.height
        if (h <= 0) return

        val bottomPad = rv.paddingBottom
        val usableH = (h - bottomPad).coerceAtLeast(1)

        val firstChild = rv.getChildAt(0)
        val rowHeightPx = if (firstChild != null && firstChild.height > 0) {
            firstChild.height + bookshelfMargin * 2
        } else {
            val density = resources.displayMetrics.density
            (200f * density).toInt() + bookshelfMargin * 2
        }

        val rows = (usableH / rowHeightPx).coerceAtLeast(1)
        pageSize = (rows * autoSpanCount).coerceAtLeast(1)

        if (allBooks.isNotEmpty()) {
            applyPaging()
        }
    }

    private fun applyPaging() {
        if (!isGrid) {
            displayBooks = allBooks
        } else {
            val total = allBooks.size
            totalPages = if (total == 0) 1 else ceil(total.toDouble() / pageSize).toInt()
            currentPage = currentPage.coerceIn(1, totalPages)
            val start = (currentPage - 1) * pageSize
            val end = min(start + pageSize, total)
            displayBooks = if (start < total) allBooks.subList(start, end).toList() else emptyList()
        }
        booksAdapter.setItems(displayBooks)
        itemCount = displayBooks.size
        if (isGrid) {
            val spanCount = autoSpanCount
            totalRows =
                if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
            updatePageBar()
        }
        binding.tvEmptyMsg.isGone = itemCount > 0
        binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
    }

    fun onVolumeUp(): Boolean {
        if (isGrid && currentPage > 1) {
            currentPage--
            applyPaging()
            binding.rvBookshelf.scrollToPosition(0)
            updatePageBar()
            return true
        }
        return false
    }

    fun onVolumeDown(): Boolean {
        if (isGrid && currentPage < totalPages) {
            currentPage++
            applyPaging()
            binding.rvBookshelf.scrollToPosition(0)
            updatePageBar()
            return true
        }
        return false
    }

    private fun upFastScrollerBar() {
        val showBookshelfFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showBookshelfFastScroller)
        if (showBookshelfFastScroller) {
            binding.rvBookshelf.scrollBarSize = 0
        } else {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
                    5 -> list.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                allBooks = list
                applyPaging()
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || isGrid) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    booksAdapter.upLastUpdateTime()
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        return allBooks
    }

    fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    fun getBooksCount(): Int {
        return allBooks.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pageBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pageBar = null
        binding.rvBookshelf.setItemViewCacheSize(0)
        binding.rvBookshelf.adapter = null
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}

/**
 * 底部分页栏——透明背景 + 黑色文字 + 大间距 + 点击页码选页
 * 从 SearchActivity.PageBarView 复制过来，尺寸略放大。
 */
private class PageBarView @JvmOverloads constructor(
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
        gravity = Gravity.CENTER
        setBackgroundColor(Color.TRANSPARENT)
        // 整体上下加一点间距
        setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))

        // 上一页
        prevBtn = TextView(context).apply {
            text = "◀"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(40))
            setOnClickListener { onPrevClick?.invoke() }
        }
        addView(prevBtn)

        // 页码
        pageBtn = TextView(context).apply {
            text = "1/1"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(40)).apply {
                leftMargin = dpToPx(12)
                rightMargin = dpToPx(12)
            }
            setOnClickListener { onPageClick?.invoke() }
        }
        addView(pageBtn)

        // 下一页
        nextBtn = TextView(context).apply {
            text = "▶"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(40))
            setOnClickListener { onNextClick?.invoke() }
        }
        addView(nextBtn)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun setPage(current: Int, total: Int) {
        pageBtn.text = "$current/$total"
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
