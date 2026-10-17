package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }

    private var autoSpanCount = 3

    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        BooksAdapterGrid(requireContext(), this)
    }

    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private var itemCount = 0
    private var totalRows = 0

    private val enablePaging = true
    private var displayBooks: List<Book> = emptyList()
    private var currentPage = 1
    private var totalPages = 1
    private var pageSize = 20
    private var lastGroupId: Long = -1L
    private var shelfPageBar: ShelfPageBarView? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        autoSpanCount = computeAutoSpanCount()
        initRecyclerView()
        initPageBar()
        initBookGroupData()
        initBooksData()
    }

    private fun computeAutoSpanCount(): Int {
        val dm = resources.displayMetrics
        val screenWdp = dm.widthPixels / dm.density
        val usable = screenWdp - bookshelfMargin * 2f
        val perColumn = 100f + bookshelfMargin
        return (usable / perColumn).toInt().coerceIn(2, 6)
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)

        // ★★★ 写死 40dp 底部 padding，避让导航栏/手势条 ★★★
        val bottomPad = (40 * resources.displayMetrics.density).toInt()
        binding.rvBookshelf.updatePadding(bottom = bottomPad)
        binding.rvBookshelf.clipToPadding = false
        // ★★★ 写死结束 ★★★

        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }

        binding.rvBookshelf.layoutManager = GridLayoutManager(context, autoSpanCount)
        binding.rvBookshelf.adapter = booksAdapter
        binding.rvBookshelf.itemAnimator = null

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

        // 左右滑动翻页
        binding.rvBookshelf.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_DOWN) {
                    touchStartX = e.x
                    touchStartY = e.y
                } else if (e.action == MotionEvent.ACTION_UP) {
                    val dx = e.x - touchStartX
                    val dy = e.y - touchStartY
                    if (abs(dx) > swipeThreshold && abs(dx) > abs(dy)) {
                        if (dx < 0) {
                            if (currentPage < totalPages) {
                                currentPage++
                                applyPagingAndUpdate()
                                binding.rvBookshelf.scrollToPosition(0)
                            }
                        } else {
                            if (currentPage > 1) {
                                currentPage--
                                applyPagingAndUpdate()
                                binding.rvBookshelf.scrollToPosition(0)
                            }
                        }
                        return true
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        binding.rvBookshelf.addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
            val newW = r - l
            val oldW = orr - ol
            if (newW != oldW && newW > 0) {
                val newSpan = computeAutoSpanCount()
                if (newSpan != autoSpanCount) {
                    autoSpanCount = newSpan
                    (binding.rvBookshelf.layoutManager as? GridLayoutManager)?.spanCount = newSpan
                    binding.rvBookshelf.post { calculatePageSize() }
                }
            }
        }

        binding.rvBookshelf.post {
            calculatePageSize()
            binding.rvBookshelf.postDelayed({
                val firstChild = binding.rvBookshelf.getChildAt(0)
                if (firstChild != null && firstChild.height > 0) {
                    calculatePageSize()
                }
            }, 100)
        }
    }

    private fun initPageBar() {
        shelfPageBar = ShelfPageBarView(requireContext()).apply {
            onPrevClick = {
                if (currentPage > 1) {
                    currentPage--
                    applyPagingAndUpdate()
                    binding.rvBookshelf.scrollToPosition(0)
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
                            applyPagingAndUpdate()
                            binding.rvBookshelf.scrollToPosition(0)
                        }
                    }
            }
            onNextClick = {
                if (currentPage < totalPages) {
                    currentPage++
                    applyPagingAndUpdate()
                    binding.rvBookshelf.scrollToPosition(0)
                }
            }
        }

        val toolbar = binding.titleBar.toolbar
        val params = androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        toolbar.addView(shelfPageBar, params)
    }

    private fun calculatePageSize() {
        val rv = binding.rvBookshelf
        val h = rv.height
        if (h <= 0) return

        // 扣掉底部 padding，避免算
        val bottomPad = rv.paddingBottom
        val usableH = (h - bottomPad).coerceAtLeast(1)

        val firstChild = rv.getChildAt(0)
        val rowHeightPx = if (firstChild != null && firstChild.height > 0) {
            firstChild.height + bookshelfMargin * 2
        } else {
            val density = resources.displayMetrics.density
            (180f * density + 0.5f).toInt() + bookshelfMargin * 2
        }

        val rows = (usableH / rowHeightPx).coerceAtLeast(1)
        pageSize = (rows * autoSpanCount).coerceAtLeast(1)

        if (books.isNotEmpty()) {
            applyPagingAndUpdate()
        }
    }

    private fun applyPagingAndUpdate() {
        val total = books.size
        totalPages = if (total == 0) 1 else ceil(total.toDouble() / pageSize).toInt()
        currentPage = currentPage.coerceIn(1, totalPages)
        val start = (currentPage - 1) * pageSize
        val end = min(start + pageSize, total)
        displayBooks = if (start < total) books.subList(start, end).toList() else emptyList()

        booksAdapter.updateItems(groupId)
        itemCount = getItemCount()
        totalRows = if (itemCount % autoSpanCount == 0) itemCount / autoSpanCount else itemCount / autoSpanCount + 1
        binding.tvEmptyMsg.isGone = itemCount > 0
        binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
        updatePageBar()
    }

    private fun updatePageBar() {
        val bar = shelfPageBar ?: return
        bar.setPage(currentPage, totalPages)
        bar.setPrevEnabled(currentPage > 1)
        bar.setNextEnabled(currentPage < totalPages)
    }

    fun onVolumeUp(): Boolean {
        if (currentPage > 1) {
            currentPage--
            applyPagingAndUpdate()
            binding.rvBookshelf.scrollToPosition(0)
            return true
        }
        return false
    }

    fun onVolumeDown(): Boolean {
        if (currentPage < totalPages) {
            currentPage++
            applyPagingAndUpdate()
            binding.rvBookshelf.scrollToPosition(0)
            return true
        }
        return false
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            applyPagingAndUpdate()
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull { groupId == it.groupId }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                if (groupId != lastGroupId) {
                    lastGroupId = groupId
                    currentPage = 1
                }
                applyPagingAndUpdate()
                delay(100)
            }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)
            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }
            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + displayBooks.size
        } else {
            displayBooks.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return displayBooks
        }
        return bookGroups + displayBooks
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
        }
    }
}

private class ShelfPageBarView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : android.widget.LinearLayout(context, attrs, defStyleAttr) {

    var onPrevClick: (() -> Unit)? = null
    var onPageClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null

    private val prevBtn: android.widget.TextView
    private val pageBtn: android.widget.TextView
    private val nextBtn: android.widget.TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val padding = dpToPx(4)
        setPadding(padding, 0, padding, 0)

        prevBtn = android.widget.TextView(context).apply {
            text = "◀"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            layoutParams = LayoutParams(dpToPx(32), dpToPx(32))
            setOnClickListener { onPrevClick?.invoke() }
        }
        addView(prevBtn)

        pageBtn = android.widget.TextView(context).apply {
            text = "1 / 1"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            layoutParams = LayoutParams(dpToPx(80), dpToPx(32)).apply {
                leftMargin = dpToPx(8)
                rightMargin = dpToPx(8)
            }
            setOnClickListener { onPageClick?.invoke() }
        }
        addView(pageBtn)

        nextBtn = android.widget.TextView(context).apply {
            text = "▶"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            layoutParams = LayoutParams(dpToPx(32), dpToPx(32))
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