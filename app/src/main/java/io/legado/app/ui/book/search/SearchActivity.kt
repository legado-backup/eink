package io.legado.app.ui.book.search

import io.legado.app.utils.dpToPx

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ActivityBookSearchBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    BookAdapter.CallBack,
    HistoryKeyAdapter.CallBack,
    SearchScopeDialog.Callback,
    SearchAdapter.CallBack {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()

    private val adapter by lazy { SearchAdapter(this, this) }
    private val bookAdapter by lazy {
        BookAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val historyKeyAdapter by lazy {
        HistoryKeyAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var menu: Menu? = null
    private var groups: List<String>? = null
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var precisionSearchMenuItem: MenuItem? = null
    private var isManualStopSearch = false

    // ========== 分页相关 ==========
    private var allBooks = mutableListOf<SearchBook>()
    private var pageSize = 10
    private var currentDisplayPage = 1
    private var totalDisplayPage = 1
    private var hasMoreData = true
    private var isLoading = false
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
        initSearchView()
        initOtherView()
        initData()
        receiptIntent(intent)
        // 延迟隐藏返回按钮，确保 TitleBar attach 完成
        binding.titleBar.post {
            binding.titleBar.toolbar.navigationIcon = null
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_search, menu)
        this.menu = menu
        precisionSearchMenuItem = menu.findItem(R.id.menu_precision_search)
        precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
        // 设置溢出菜单图标为黑色
        binding.titleBar.toolbar.overflowIcon?.setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.transaction {
            menu.removeGroup(R.id.menu_group_1)
            menu.removeGroup(R.id.menu_group_2)
            var hasChecked = false
            val searchScopeNames = viewModel.searchScope.displayNames
            if (viewModel.searchScope.isSource()) {
                menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, searchScopeNames.first()).apply {
                    isChecked = true
                    hasChecked = true
                }
            }
            val allSourceMenu =
                menu.add(R.id.menu_group_2, R.id.menu_1, Menu.NONE, getString(R.string.all_source))
                    .apply {
                        if (searchScopeNames.isEmpty()) {
                            isChecked = true
                            hasChecked = true
                        }
                    }
            groups?.forEach {
                if (searchScopeNames.contains(it)) {
                    menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, it).apply {
                        isChecked = true
                        hasChecked = true
                    }
                } else {
                    menu.add(R.id.menu_group_2, Menu.NONE, Menu.NONE, it)
                }
            }
            if (!hasChecked) {
                viewModel.searchScope.update("")
                allSourceMenu.isChecked = true
            }
            menu.setGroupCheckable(R.id.menu_group_1, true, false)
            menu.setGroupCheckable(R.id.menu_group_2, true, true)
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_precision_search -> {
                putPrefBoolean(
                    PreferKey.precisionSearch,
                    !getPrefBoolean(PreferKey.precisionSearch)
                )
                precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }

            R.id.menu_search_scope -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> viewModel.searchScope.update("")
            else -> {
                if (item.groupId == R.id.menu_group_1) {
                    viewModel.searchScope.remove(item.title.toString())
                } else if (item.groupId == R.id.menu_group_2) {
                    viewModel.searchScope.update(item.title.toString())
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_key)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                query.trim().let { searchKey ->
                    isManualStopSearch = false
                    viewModel.saveSearchKey(searchKey)
                    viewModel.searchKey = ""
                    // 重置分页状态
                    allBooks.clear()
                    currentDisplayPage = 1
                    totalDisplayPage = 1
                    hasMoreData = true
                    isLoading = false
                    isFirstLoad = true
                    viewModel.search(searchKey)
                }
                visibleInputHelp(false)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.stop()
                binding.fbStartStop.invisible()
                upHistory(newText.trim())
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (binding.refreshProgressBar.isAutoLoading || (!hasFocus && adapter.isNotEmpty() && searchView.query.isNotBlank())) {
                visibleInputHelp(false)
            } else {
                visibleInputHelp(true)
            }
        }
        visibleInputHelp(true)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.setEdgeEffectColor(primaryColor)
        binding.rvHistoryKey.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.layoutManager = FlexboxLayoutManager(this)
        binding.rvBookshelfSearch.adapter = bookAdapter
        binding.rvBookshelfSearch.applyNavigationBarMargin()
        binding.rvHistoryKey.layoutManager = FlexboxLayoutManager(this)
        binding.rvHistoryKey.adapter = historyKeyAdapter
        binding.rvHistoryKey.applyNavigationBarMargin()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        // 强制列表左右各15dp边距
        val sidePadding = dip2px(15f)
        binding.recyclerView.setPadding(sidePadding, binding.recyclerView.paddingTop, sidePadding, binding.recyclerView.paddingBottom)
        binding.recyclerView.clipToPadding = false
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                if (toPosition == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        })
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // 分页由分页栏控制，不自动滚动加载
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
     * 初始化底部分页栏 - 悬浮在列表底部，背景透明
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
                NumberPickerDialog(this@SearchActivity)
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

        val toolbar = binding.titleBar.toolbar
        val searchView = this.searchView

        // 隐藏返回按钮（延迟执行，确保 TitleBar attach 完成）
        toolbar.post {
            toolbar.navigationIcon = null
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }

        // 找到 searchView 在 toolbar 中的位置
        val searchIndex = toolbar.indexOfChild(searchView)
        if (searchIndex >= 0) {
            // 保存 searchView 的 LayoutParams
            val searchLp = searchView.layoutParams

            // 从 toolbar 移除 searchView
            toolbar.removeView(searchView)

            // 创建水平容器：searchView + pageBar
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = searchLp
            }

            // searchView 占剩余空间，高度和分页按钮对齐
            searchView.layoutParams = LinearLayout.LayoutParams(
                0,
                dip2px(28f),
                1f
            )
            // 搜索框样式：透明背景、黑色描边、更圆角、文字和图标纯黑
            searchView.apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(dip2px(1f), Color.BLACK)
                    cornerRadius = dip2px(14f).toFloat()
                }
                // 强制设置所有图标为黑色
                findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_mag_icon)
                    ?.apply {
                        setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
                        imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                    }
                findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_close_btn)
                    ?.apply {
                        setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
                        imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                    }
                findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(
                    androidx.appcompat.R.id.search_src_text
                )?.apply {
                    setTextColor(Color.BLACK)
                    setHintTextColor(Color.BLACK)
                }
            }
            container.addView(searchView)

            // pageBar 固定小宽度，靠右
            val pageBarLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dip2px(4f)
            }
            container.addView(pageBar, pageBarLp)

            // 把容器插回 toolbar
            toolbar.addView(container, searchIndex)
        }
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
        viewModel.search("")
    }

    private fun initOtherView() {
        binding.fbStartStop.gone()
        binding.tvClearHistory.setOnClickListener { alertClearHistory() }
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!binding.llInputHelp.isVisible) {
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }
        }
        viewModel.isSearchLiveData.observe(this) {
            if (it) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        // 修改：接收数据时分页显示
        viewModel.searchBookLiveData.observe(this) {
            upData(it)
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().collect {
                groups = it
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.resume()
                try {
                    awaitCancellation()
                } finally {
                    viewModel.pause()
                }
            }
        }
    }

    /**
     * 处理传入数据
     */
    private fun receiptIntent(intent: Intent? = null) {
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let {
            viewModel.searchScope.update(searchScope, postValue = false, save = false)
        }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
                .requestFocus()
        } else {
            searchView.setQuery(key, true)
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(searchView.query.toString())
            binding.llInputHelp.visibility = View.VISIBLE
        } else {
            binding.llInputHelp.visibility = View.GONE
        }
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                binding.tvBookShow.gone()
                binding.rvBookshelfSearch.gone()
            } else {
                appDb.bookDao.flowSearch(key).conflate().collect {
                    if (it.isEmpty()) {
                        binding.tvBookShow.gone()
                        binding.rvBookshelfSearch.gone()
                    } else {
                        binding.tvBookShow.visible()
                        binding.rvBookshelfSearch.visible()
                    }
                    bookAdapter.setItems(it)
                }
            }
        }
        historyFlowJob?.cancel()
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime()
                else -> appDb.searchKeywordDao.flowSearch(key)
            }.catch {
                AppLog.put("搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                historyKeyAdapter.setItems(it)
                if (it.isEmpty()) {
                    binding.tvClearHistory.invisible()
                } else {
                    binding.tvClearHistory.visible()
                }
            }
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        binding.refreshProgressBar.visible()
        binding.refreshProgressBar.isAutoLoading = true
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        isLoading = false
        binding.refreshProgressBar.isAutoLoading = false
        binding.refreshProgressBar.gone()
        updatePageBar()
    }

    /**
     * 接收搜索数据并分页显示
     */
    private fun upData(books: List<SearchBook>) {
        isLoading = false

        if (books.isEmpty()) {
            hasMoreData = viewModel.hasMore
            if (allBooks.isEmpty()) {
                // 空数据提示
            } else {
                // 没有更多数据
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
        hasMoreData = viewModel.hasMore

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

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            alert("搜索结果为空") {
                val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
                val displayScope = viewModel.searchScope.display
                if (precisionSearch) {
                    setMessage("${displayScope}分组搜索结果为空，是否关闭精准搜索？")
                    yesButton {
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        precisionSearchMenuItem?.isChecked = false
                        viewModel.searchKey = ""
                        viewModel.search(searchView.query.toString())
                    }
                } else {
                    setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                    yesButton {
                        viewModel.searchScope.update("")
                    }
                }
                noButton()
            }
        }
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(name: String, author: String, bookUrl: String) {
        startActivity<BookInfoActivity> {
            putExtra("name", name)
            putExtra("author", author)
            putExtra("bookUrl", bookUrl)
        }
    }

    /**
     * 是否已经加入书架
     */
    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(book: Book) {
        showBookInfo(book.name, book.author, book.bookUrl)
    }

    /**
     * 点击历史关键字
     */
    override fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                searchView.query.toString() == key -> {
                    searchView.setQuery(key, true)
                }

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    searchView.setQuery(key, true)
                }

                else -> {
                    searchView.setQuery(key, false)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }


    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    private fun alertSearchScope() {
        showDialogFragment<SearchScopeDialog>()
    }

    private fun alertClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton {
                viewModel.clearHistory()
            }
            noButton()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                // 音量上键 → 上一页
                if (currentDisplayPage > 1) {
                    currentDisplayPage--
                    refreshCurrentPage()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 音量下键 → 下一页
                if (currentDisplayPage < totalDisplayPage) {
                    currentDisplayPage++
                    refreshCurrentPage()
                } else if (hasMoreData && !isLoading) {
                    loadNextPageData()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun finish() {
        if (searchView.hasFocus()) {
            searchView.clearFocus()
            return
        }
        super.finish()
    }

    // ========== 底部分页栏自定义 View - 背景透明 ==========
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
            setBackgroundColor(Color.TRANSPARENT)

            setPadding(0, 0, 0, 0)

            // 上一页按钮
            prevBtn = TextView(context).apply {
                text = "◀"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(28))
                setOnClickListener { onPrevClick?.invoke() }
            }
            addView(prevBtn)

            // 页码按钮
            pageBtn = TextView(context).apply {
                text = "1/1"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28)).apply {
                    leftMargin = dpToPx(2)
                    rightMargin = dpToPx(2)
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
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(28))
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

    companion object {

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

    }
}
