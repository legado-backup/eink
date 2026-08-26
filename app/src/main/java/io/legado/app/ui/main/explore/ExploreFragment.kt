package io.legado.app.ui.main.explore

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.applyTint
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreShowAdapter.CallBack,
    ExploreFilterView.CallBack {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")
    override val viewModel by viewModels<ExploreViewModel>()

    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val bookAdapter by lazy { ExploreShowAdapter(requireContext(), this) }
    private val searchView: SearchView by lazy { binding.searchView }
    private val filterView by lazy {
        ExploreFilterView(
            requireActivity() as AppCompatActivity,
            binding.filterRows,
            viewLifecycleOwner.lifecycleScope,
            this
        )
    }
    private val sources = arrayListOf<BookSourcePart>()
    private var selectedSource: BookSourcePart? = null
    private var sourceFlowJob: Job? = null
    private var categoryJob: Job? = null

    // ========== 分页相关 ==========
    private var allBooks = mutableListOf<SearchBook>()
    private var pageSize = 10
    private var currentDisplayPage = 1
    private var totalDisplayPage = 1
    private var isLoading = false
    private var isFirstLoad = true
    private var currentItemExtraSpacing = 0  // 当前每个 item 的额外上下间距（px）

    // ========== 滑动切页相关 ==========
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f  // 水平滑动触发阈值（px）

    private fun dp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density + 0.5f).toInt()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.topBar.visibility = View.GONE
        // 隐藏分类标签区域所有分割线/阴影
        binding.filterScroll.elevation = 0f
        binding.filterScroll.outlineProvider = null
        binding.filterScroll.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.filterRows.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 分类标签区域顶部留出间距
        binding.filterRows.setPadding(0, dp(45), 0, 0)
        // 隐藏刷新布局所有分割线/阴影
        binding.refreshLayout.elevation = 0f
        binding.refreshLayout.outlineProvider = null
        binding.refreshLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 根布局拦截水平滑动，禁止 ViewPager 切换页面
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    binding.root.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    // 水平滑动时禁止父视图拦截
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        binding.root.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.root.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false // 不消费事件，让子视图继续处理
        }
        initSearch()
        initBookList()
        observeBooks()
        observeSources()
    }

    private fun initSearch() {
        // 清除SearchView默认背景
        searchView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val searchEditFrame = searchView.findViewById<View>(androidx.appcompat.R.id.search_edit_frame)
        searchEditFrame?.layoutParams = searchEditFrame?.layoutParams?.apply {
            height = dp(32)
        }
        // 清除searchEditFrame内部padding，让文字真正左对齐
        searchEditFrame?.setPadding(dp(12), 0, dp(8), 0)
        // 给输入框区域设置圆角描边背景
        searchEditFrame?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(android.graphics.Color.WHITE)
            setStroke(dp(1), android.graphics.Color.BLACK)
        }
        // 隐藏底部分割线
        val searchPlate = searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)
        searchPlate?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 清除搜索框内部其他背景
        val searchBar = searchView.findViewById<View>(androidx.appcompat.R.id.search_bar)
        searchBar?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val searchSrcText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
        searchSrcText?.setTextColor(android.graphics.Color.BLACK)
        searchSrcText?.setHintTextColor(android.graphics.Color.BLACK)
        searchSrcText?.textSize = 12f
        searchSrcText?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 隐藏搜索图标
        val searchMagIcon = searchView.findViewById<View>(androidx.appcompat.R.id.search_mag_icon)
        searchMagIcon?.visibility = View.GONE
        // 隐藏提交按钮
        val searchGoBtn = searchView.findViewById<View>(androidx.appcompat.R.id.search_go_btn)
        searchGoBtn?.visibility = View.GONE
        // 隐藏关闭按钮
        val searchCloseBtn = searchView.findViewById<View>(androidx.appcompat.R.id.search_close_btn)
        searchCloseBtn?.visibility = View.GONE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val key = query?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    selectedSource?.let { SearchActivity.start(requireContext(), it, key) }
                        ?: SearchActivity.start(requireContext(), key)
                }
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun initBookList() {
        binding.rvBooks.adapter = bookAdapter
        binding.rvBooks.itemAnimator = null
        binding.rvBooks.setPadding(dp(8), dp(10), dp(8), dp(10))
        binding.rvBooks.clipToPadding = false
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            if (viewModel.hasCategorySelected) {
                // 重置分页状态
                allBooks.clear()
                currentDisplayPage = 1
                totalDisplayPage = 1
                        isLoading = false
                isFirstLoad = true
                viewModel.refreshCurrentCategory()
            } else {
                binding.refreshLayout.isRefreshing = false
            }
        }

        // ========== 左右滑动切页 ==========
        binding.rvBooks.addItemDecoration(DynamicSpacingDecoration())
        binding.rvBooks.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = e.x
                        touchStartY = e.y
                        // 按下时禁止父视图拦截，确保水平滑动不被 ViewPager 抢走
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.x - touchStartX
                        val dy = e.y - touchStartY

                        // 水平滑动距离超过阈值，且水平滑动大于垂直滑动
                        if (kotlin.math.abs(dx) > swipeThreshold && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            // 禁止父视图（ViewPager）拦截滑动事件
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                            if (dx < 0) {
                                // 向左滑动 → 下一页
                                if (currentDisplayPage < totalDisplayPage) {
                                    currentDisplayPage++
                                    refreshCurrentPage()
                                } else if (!isLoading) {
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
        binding.rvBooks.post {
            calculatePageSize()
        }
    }

    private fun observeBooks() {
        viewModel.booksState.observe(viewLifecycleOwner) { state ->
            isLoading = false

            if (state.books.isEmpty()) {
                if (allBooks.isEmpty()) {
                    bookAdapter.setItems(emptyList())
                }
                if (!state.isLoading) binding.refreshLayout.isRefreshing = false
                binding.progressLoading.isVisible = state.isLoading && allBooks.isEmpty()
                binding.tvEmptyMsg.isVisible = !state.isLoading && allBooks.isEmpty()
                binding.tvEmptyMsg.text = when {
                    state.error != null -> getString(R.string.discovery_load_failed, state.error)
                    viewModel.hasCategorySelected -> getString(R.string.discovery_no_books)
                    else -> getString(R.string.discovery_choose_category)
                }
                if (state.error != null && allBooks.isNotEmpty()) {
                    toastOnUi(getString(R.string.discovery_load_failed, state.error))
                }
                return@observe
            }

            // 添加新数据到总列表（去重）
            state.books.forEach { newBook ->
                if (allBooks.none { it.name == newBook.name && it.author == newBook.author }) {
                    allBooks.add(newBook)
                }
            }

            // 重新计算总页数
            totalDisplayPage = kotlin.math.ceil(allBooks.size.toDouble() / pageSize).toInt()

            // 首次加载：计算分页大小并刷新
            if (isFirstLoad) {
                isFirstLoad = false
                val recyclerViewHeight = binding.rvBooks.height
                if (recyclerViewHeight > 0) {
                    val itemHeight = dp(120)
                    pageSize = (recyclerViewHeight / itemHeight).coerceAtLeast(1)
                    currentDisplayPage = 1
                    refreshCurrentPage()
                } else {
                    binding.rvBooks.post {
                        calculatePageSize()
                        currentDisplayPage = 1
                        refreshCurrentPage()
                    }
                }
            } else {
                refreshCurrentPage()
            }

            if (!state.isLoading) binding.refreshLayout.isRefreshing = false
            binding.progressLoading.isVisible = false
            binding.tvEmptyMsg.isVisible = false
        }
        viewModel.upAdapterLiveData.observe(viewLifecycleOwner) {
            bookAdapter.notifyItemRangeChanged(0, bookAdapter.itemCount, Bundle().apply {
                putBoolean("isInBookshelf", true)
            })
        }
    }

    /**
     * 根据屏幕高度自动计算每页显示数量
     * 使用实际 item 高度计算，而不是固定估算值
     */
    private fun calculatePageSize() {
        val recyclerView = binding.rvBooks
        val recyclerViewHeight = recyclerView.height
        if (recyclerViewHeight <= 0) return

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        // 尝试获取第一个可见 item 的实际高度
        val firstView = layoutManager.findViewByPosition(0)
        val itemHeight = if (firstView != null && firstView.height > 0) {
            firstView.height
        } else {
            dp(120) //  fallback
        }

        pageSize = (recyclerViewHeight / itemHeight).coerceAtLeast(1)
        if (allBooks.isNotEmpty()) {
            refreshCurrentPage()
        }
    }

    /**
     * 刷新当前显示页
     */
    private fun refreshCurrentPage() {
        val startIndex = (currentDisplayPage - 1) * pageSize
        val endIndex = kotlin.math.min(startIndex + pageSize, allBooks.size)

        if (startIndex < allBooks.size) {
            val pageBooks = allBooks.subList(startIndex, endIndex)
            bookAdapter.setItems(pageBooks.toList())
            binding.rvBooks.scrollToPosition(0)
        }

        // 动态计算 item 间距，使最后一本书距离底栏正好 10dp
        // 使用 ViewTreeObserver 确保 layout 完成后再计算
        binding.rvBooks.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.rvBooks.viewTreeObserver.removeOnGlobalLayoutListener(this)
                // 检查是否超出，如果超出减少一本书重新刷新
                if (checkAndFixOverflow()) {
                    return
                }
                adjustItemSpacingForBottomAlign()
            }
        })

        // 如果当前页是最后一页，自动加载下一页
        if (currentDisplayPage >= totalDisplayPage && !isLoading) {
            loadNextPageData()
        }
    }

    /**
     * 检查当前页是否超出 RecyclerView 高度，如果超出减少 pageSize 并重新刷新
     */
    private fun checkAndFixOverflow(): Boolean {
        val recyclerView = binding.rvBooks
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = layoutManager.itemCount
        if (itemCount == 0) return false

        val rvHeight = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom
        val targetBottomMargin = dp(10)

        // 计算当前所有 item 的总高度（不含额外间距）
        var totalItemsHeight = 0
        for (i in 0 until itemCount) {
            val view = layoutManager.findViewByPosition(i) ?: continue
            totalItemsHeight += view.height
        }

        // 如果总高度超出可用高度（保留 10dp 底部边距），减少一页显示数量
        if (totalItemsHeight > rvHeight - targetBottomMargin && pageSize > 1) {
            pageSize = (pageSize - 1).coerceAtLeast(1)
            // 重新计算总页数
            totalDisplayPage = kotlin.math.ceil(allBooks.size.toDouble() / pageSize).toInt()
            // 重新刷新当前页
            refreshCurrentPage()
            return true
        }
        return false
    }

    /**
     * 调整 item 上下间距，使最后一本书距离底栏正好 10dp
     */
    private fun adjustItemSpacingForBottomAlign() {
        val recyclerView = binding.rvBooks
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = layoutManager.itemCount
        if (itemCount == 0) return

        val rvHeight = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom
        val targetBottomMargin = dp(10)

        // 计算当前所有 item 的总高度（不含额外间距）
        var totalItemsHeight = 0
        for (i in 0 until itemCount) {
            val view = layoutManager.findViewByPosition(i) ?: continue
            totalItemsHeight += view.height
        }

        if (totalItemsHeight == 0) return

        // 需要的总间距 = 可用高度 - 所有 item 高度 - 目标底部边距
        val neededTotalSpacing = rvHeight - totalItemsHeight - targetBottomMargin
        val neededExtraPerItem = if (neededTotalSpacing > 0 && itemCount > 0) {
            neededTotalSpacing / itemCount / 2  // 上下各分一半
        } else 0

        // 限制最大额外间距，避免间距过大（最大 8dp）
        val maxExtraSpacing = dp(8)
        val clampedExtra = neededExtraPerItem.coerceIn(0, maxExtraSpacing)

        if (clampedExtra != currentItemExtraSpacing) {
            currentItemExtraSpacing = clampedExtra
            recyclerView.invalidateItemDecorations()
        }
    }

    /**
     * 加载下一页网络数据
     */
    private fun loadNextPageData() {
        if (isLoading) return
        isLoading = true
        viewModel.loadNextPage()
    }

    /**
     * 动态调整 item 间距的 Decoration
     * 第一个 item 上边距和最后一个 item 下边距保持为 padding 值（10dp）
     * 中间 item 均匀分布剩余空间
     */
    inner class DynamicSpacingDecoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val itemCount = parent.adapter?.itemCount ?: 0
            if (position == RecyclerView.NO_POSITION || itemCount == 0) return

            // 第一个 item 不加顶部 extra，最后一个 item 不加底部 extra
            // 这样第一本上边距 = paddingTop(10)，最后一本下边距 = paddingBottom(10)
            if (position > 0) {
                outRect.top = currentItemExtraSpacing
            }
            if (position < itemCount - 1) {
                outRect.bottom = currentItemExtraSpacing
            }
        }
    }

    private fun observeSources() {
        sourceFlowJob?.cancel()
        sourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .catch { AppLog.put("发现页更新书源失败", it) }
                .conflate()
                .flowOn(IO)
                .collect { list ->
                    sources.clear()
                    sources.addAll(list)
                    val currentUrl = selectedSource?.bookSourceUrl
                        ?: requireContext().getPrefString(PreferKey.exploreSourceUrl)
                    val selected = list.firstOrNull { it.bookSourceUrl == currentUrl }
                        ?: list.firstOrNull()
                    if (selected == null) {
                        selectedSource = null
                        filterView.clear()
                    } else if (selected.bookSourceUrl != currentUrl || binding.filterRows.childCount == 0) {
                        selectSource(selected)
                    }
                }
        }
    }

    private fun selectSource(source: BookSourcePart, refresh: Boolean = false) {
        selectedSource = source
        requireContext().putPrefString(PreferKey.exploreSourceUrl, source.bookSourceUrl)
        searchView.queryHint = "${source.bookSourceName} · ${getString(R.string.search_book_key)}"
        viewModel.clearCategory()
        // 重置分页状态
        allBooks.clear()
        currentDisplayPage = 1
        totalDisplayPage = 1
        isLoading = false
        isFirstLoad = true
        bookAdapter.setItems(emptyList())
        categoryJob?.cancel()
        categoryJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (refresh) source.clearExploreKindsCache()
                source.getBookSource()?.let { it to source.exploreKinds() }
            }.onSuccess { data ->
                if (source.bookSourceUrl != selectedSource?.bookSourceUrl) return@onSuccess
                if (data == null) filterView.clear() else filterView.setData(data.first, data.second)
            }.onFailure {
                AppLog.put("发现页加载分类失败", it)
                toastOnUi(it.localizedMessage ?: it.javaClass.simpleName)
            }
        }
    }

    override fun showSourceMenu() {
        // 从分类标签区域下方弹出菜单，留出顶部间距
        val anchor = binding.filterRows
        PopupMenu(requireContext(), anchor).apply {
            // 设置菜单显示在锚点下方，并添加顶部偏移
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setGravity(Gravity.TOP or Gravity.START)
            }
            // 选择书源
            menu.add(0, MENU_SELECT_SOURCE, 0, "选择书源")
            // 刷新书源
            menu.add(0, MENU_REFRESH, 1, "刷新书源")
            // 登录书源（当前书源需要登录时才显示）
            selectedSource?.takeIf { it.hasLoginUrl }?.let {
                menu.add(0, MENU_LOGIN, 2, "登录书源")
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SELECT_SOURCE -> showSourceSelector()
                    MENU_REFRESH -> selectedSource?.let { selectSource(it, true) }
                    MENU_LOGIN -> selectedSource?.let {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "bookSource")
                            putExtra("key", it.bookSourceUrl)
                        }
                    }
                }
                true
            }
            show()
        }
    }

    /**
     * 弹出书源选择窗口（BottomSheetDialog + RecyclerView + 搜索）
     */
    private fun showSourceSelector() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        fun dp(px: Int): Int = (px * density + 0.5f).toInt()

        // ========== 根布局 ==========
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ========== 标题栏 ==========
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val titleText = TextView(context).apply {
            text = "选择书源"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val manageBtn = TextView(context).apply {
            text = "管理"
            textSize = 14f
            setTextColor(accentColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                startActivity<BookSourceActivity>()
                dialog.dismiss()
            }
        }
        titleBar.addView(titleText)
        titleBar.addView(manageBtn)
        root.addView(titleBar)

        // ========== 分割线 ==========
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        // ========== 搜索框 ==========
        val searchView = SearchView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            queryHint = "搜索书源..."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply {
                setMargins(dp(12), dp(8), dp(12), dp(4))
            }
            findViewById<View>(androidx.appcompat.R.id.search_mag_icon)?.visibility = View.GONE
            findViewById<View>(androidx.appcompat.R.id.search_go_btn)?.visibility = View.GONE
            findViewById<View>(androidx.appcompat.R.id.search_plate)?.setBackgroundColor(Color.TRANSPARENT)
            val searchEditFrame = findViewById<View>(androidx.appcompat.R.id.search_edit_frame)
            searchEditFrame?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
            }
            val searchSrcText = findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            searchSrcText?.setTextColor(Color.BLACK)
            searchSrcText?.setHintTextColor(Color.DKGRAY)
            searchSrcText?.textSize = 13f
        }
        root.addView(searchView)

        // ========== RecyclerView ==========
        val recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(400)
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutManager = LinearLayoutManager(context)
        }
        root.addView(recyclerView)

        // ========== 适配器 ==========
        val adapter = ExploreSourceAdapter(
            sources = sources,
            selectedUrl = selectedSource?.bookSourceUrl
        ) { source, action ->
            when (action) {
                SourceAction.SELECT -> {
                    selectSource(source)
                    dialog.dismiss()
                }
                SourceAction.REFRESH -> {
                    selectSource(source, true)
                    dialog.dismiss()
                }
                else -> Unit
            }
        }
        recyclerView.adapter = adapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })

        dialog.setContentView(root)
        dialog.show()
    }


    override fun openExplore(title: String, url: String) {
        val source = selectedSource?.getBookSource() ?: return
        // 重置分页状态
        allBooks.clear()
        currentDisplayPage = 1
        totalDisplayPage = 1
        isLoading = false
        isFirstLoad = true
        binding.rvBooks.scrollToPosition(0)
        viewModel.selectCategory(source, url)
    }

    override fun refreshExplore() {
        selectedSource?.let { selectSource(it, true) }
    }

    override fun isInBookshelf(book: SearchBook): Boolean = viewModel.isInBookShelf(book)

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        bookAdapter.upResumed(true)
        // 音量键翻页监听
        view?.isFocusableInTouchMode = true
        view?.requestFocus()
        view?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (currentDisplayPage > 1) {
                            currentDisplayPage--
                            refreshCurrentPage()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (currentDisplayPage < totalDisplayPage) {
                            currentDisplayPage++
                            refreshCurrentPage()
                        } else if (!isLoading) {
                            loadNextPageData()
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    override fun onPause() {
        filterView.saveState()
        bookAdapter.upResumed(false)
        searchView.clearFocus()
        super.onPause()
    }

    fun compressExplore() {
        if (binding.rvBooks.canScrollVertically(-1)) binding.rvBooks.smoothScrollToPosition(0)
        else binding.filterScroll.smoothScrollTo(0, 0)
    }

    /**
     * 书源列表适配器，支持搜索过滤
     */
        private enum class SourceAction { SELECT, LOGIN, REFRESH }

    private inner class ExploreSourceAdapter(
        private val sources: List<BookSourcePart>,
        private var selectedUrl: String?,
        private val onAction: (BookSourcePart, SourceAction) -> Unit
    ) : RecyclerView.Adapter<ExploreSourceAdapter.ViewHolder>() {

        private var filteredList = sources.toList()

        fun filter(query: String) {
            filteredList = if (query.isBlank()) {
                sources.toList()
            } else {
                sources.filter { it.bookSourceName.contains(query, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = filteredList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(px: Int): Int = (px * density + 0.5f).toInt()

            // 根布局 - 横向排列
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(16), dp(14))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
            }

            // 文字区域
            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(context).apply {
                textSize = 15f
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val urlView = TextView(context).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = View.GONE
                setPadding(0, dp(2), 0, 0)
            }

            textContainer.addView(nameView)
            textContainer.addView(urlView)

            // 选中标记 ✓
            val checkView = TextView(context).apply {
                text = "✓"
                textSize = 18f
                setTextColor(accentColor)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            }

            root.addView(textContainer)
            root.addView(checkView)

            return ViewHolder(root, nameView, urlView, checkView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(filteredList[position])
        }

        inner class ViewHolder(
            private val root: LinearLayout,
            private val nameView: TextView,
            private val urlView: TextView,
            private val checkView: TextView
        ) : RecyclerView.ViewHolder(root) {

            fun bind(source: BookSourcePart) {
                nameView.text = source.bookSourceName
                urlView.text = source.bookSourceUrl
                urlView.visibility = if (source.bookSourceUrl.isNotBlank()) View.VISIBLE else View.GONE

                val isSelected = source.bookSourceUrl == selectedUrl
                checkView.visibility = if (isSelected) View.VISIBLE else View.GONE

                // 选中态背景
                root.setBackgroundColor(
                    if (isSelected) Color.parseColor("#F5F5F5") else Color.TRANSPARENT
                )

                root.setOnClickListener {
                    selectedUrl = source.bookSourceUrl
                    onAction(source, SourceAction.SELECT)
                }

                root.setOnLongClickListener {
                    onAction(source, SourceAction.REFRESH)
                    true
                }
            }
        }
    }

    companion object {
        private const val MENU_SELECT_SOURCE = 10001
        private const val MENU_REFRESH = 10002
        private const val MENU_LOGIN = 10003
    }
}
