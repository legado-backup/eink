@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.LanTransferActivity
import io.legado.app.ui.file.FilePickerDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

/**
 * 主页（E-ink 风格）
 *
 * 生命周期约定（本文件的硬性规则）：
 * 1. [viewLifecycleOwner] 只在 [onViewCreated] 内部取一次，之后全程通过
 *    [withViewLifecycle] / [repeatOnLifecycle] 传递，不再从 post / postDelayed / 回调里取。
 * 2. 所有 `post` / `postDelayed` 的 Runnable 都必须持有同一实例，
 *    以便在 [onDestroyView] 里真正 `removeCallbacks` 掉（不能用 removeCallbacks(null)）。
 * 3. 视图相关的渲染方法统一先过 [viewDestroyed] + view 的判空。
 */
class HomeFragment(
    private val argPosition: Int = -1
) : Fragment(), MainFragmentInterface, FilePickerDialog.CallBack {

    override val position: Int? get() = arguments?.getInt("position")

    init {
        arguments = Bundle().apply { putInt("position", argPosition) }
    }

    private val WHITE = Color.parseColor("#FFFFFFFF")
    private val BLACK = Color.parseColor("#FF000000")

    // 修复：原来是 #FF000000（AA=FF 即全不透明），实际就是黑色，"灰"是假的。
    private val GRAY = Color.parseColor("#FF8A8A8A")
    private val LIGHT_GRAY = Color.parseColor("#FFEEEEEE")
    private val STROKE_DP = 1.5f

    private var cardAlpha: Int = 204

    private lateinit var rootLayout: LinearLayout
    private var scrollViewRef: ScrollView? = null
    private var wallpaperPath: String? = null
    private var wallpaperDrawable: BitmapDrawable? = null
    private var isPickingWallpaper = false
    private var pickerBackCallback: OnBackPressedCallback? = null

    /** 视图是否已销毁。所有渲染入口的第一道闸门。 */
    private var viewDestroyed = false

    /**
     * fitAll 的重试令牌。
     * View.removeCallbacks 靠 `Runnable 实例相等` 匹配，之前传 null 匹配不到任何东西，
     * 导致 50ms 自重试循环在 onDestroyView 之后继续投递消息。必须复用同一个实例。
     */
    private val fitAllRunnable = Runnable { fitAll() }

    private var originalStatusBarColor: Int = Color.BLACK
    private var originalSystemUiVisibility: Int = 0

    private var appContext: Context? = null

    private val pickWallpaperFromGallery = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (!isViewAlive()) return@registerForActivityResult
        uri?.let { handleWallpaperUri(it) }
    }

    private lateinit var tvTitleGlobal: TextView
    private lateinit var tvSubtitleGlobal: TextView

    private lateinit var tvReadingName: TextView
    private lateinit var tvReadingAuthor: TextView
    private lateinit var tvReadingIntro: TextView
    private lateinit var ivReadingCover: RoundedImageView
    private lateinit var progressReading: CircularProgressView

    private lateinit var recentListContainer: LinearLayout

    private lateinit var fileListContainer: LinearLayout
    private val fileItemViews = mutableListOf<TextView>()
    private val EINK_DIR = "E-ink"

    private lateinit var tvSummaryToday: TextView
    private lateinit var tvSummaryWeek: TextView
    private lateinit var tvSummaryMonthAvg: TextView

    private lateinit var chartContainer: LinearLayout
    private val chartBars = mutableListOf<View>()
    private val chartBarOuters = mutableListOf<View>()
    private val chartTimeLabels = mutableListOf<TextView>()
    private val chartDayLabels = mutableListOf<TextView>()
    private var chartMaxMs: Long = 60L * 60L * 1000L

    // ---- 数据缓存：观察者只负责产出这些字段，渲染方法只读这些字段 ----
    private var readingBook: Book? = null
    private var recentBooks: List<Book> = emptyList()
    private var cachedFiles: List<File> = emptyList()
    private var cachedFilesDenied: Boolean = false
    private var dailyMap: Map<LocalDate, Long> = emptyMap()
    private var weekData: List<Long> = List(7) { 0L }

    private var statusBarInsetPx = 0
    private var navigationBarInsetPx = 0

    private var scale = 1f
    private var contentScale = 1f

    private lateinit var tvRecentTitleGlobal: TextView
    private lateinit var tvFileTitleGlobal: TextView
    private lateinit var ivArrow1: ImageView
    private lateinit var ivArrow2: ImageView

    private lateinit var ivScaleBtn: ImageView
    private lateinit var ivExploreBtn: ImageView
    private lateinit var ivMyBtn: ImageView

    private val baseFontTitle = 22f
    private val baseFontSubtitle = 13f
    private val baseFontReadingName = 22f
    private val baseFontReadingAuthor = 14f
    private val baseFontReadingIntro = 14f
    private val baseFontRecentTitle = 16f
    private val baseFontRecentItem = 12f
    private val baseFontFileTitle = 16f
    private val baseFontFileItem = 12f
    private val baseFontChartText = 10f
    private val baseFontSummaryLabel = 16f
    private val baseFontSummaryValue = 12f

    // 修复：不再用 requireContext()（detach 后会抛 "Fragment not attached"），改用 attach 时缓存的 appContext
    private val prefs: SharedPreferences by lazy {
        (appContext ?: requireContext().applicationContext)
            .getSharedPreferences("home_fragment_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val REQ_STORAGE = 1001
        private const val DEFAULT_SUBTITLE = "胸藏文墨虚若谷 腹有诗书气自华"
        private const val USER_SCALE_MIN = 0.5f
        private const val USER_SCALE_MAX = 1.5f

        private const val MAX_LIST_ITEMS = 4

        private const val READING_COVER_W_DP = 80f
        private const val READING_COVER_H_DP = 110f

        private const val MIN_CHART_MS = 60L * 60L * 1000L

        private val SVG_ARROW_RECENT = listOf(
            "M938.666667 277.333333c0-38.4 12.8-76.8 29.866666-110.933333 17.066667-34.133333-4.266667-81.066667-42.666666-81.066667H192C85.333333 85.333333 0 170.666667 0 277.333333S85.333333 469.333333 192 469.333333h733.866667c38.4 0 59.733333-46.933333 42.666666-81.066666-17.066667-34.133333-29.866667-72.533333-29.866666-110.933334zM874.666667 384h-682.666667C132.266667 384 85.333333 337.066667 85.333333 277.333333S132.266667 170.666667 192 170.666667h682.666667c-12.8 29.866667-21.333333 68.266667-21.333334 106.666666 0 38.4 8.533333 76.8 21.333334 106.666667zM832 554.666667H98.133333c-38.4 0-59.733333 46.933333-42.666666 81.066666 17.066667 34.133333 29.866667 72.533333 29.866666 110.933334 0 38.4-12.8 76.8-29.866666 110.933333-17.066667 34.133333 4.266667 81.066667 42.666666 81.066667h733.866667c106.666667 0 192-85.333333 192-192S938.666667 554.666667 832 554.666667z m0 298.666666h-682.666667c12.8-29.866667 21.333333-68.266667 21.333334-106.666666 0-38.4-8.533333-76.8-21.333334-106.666667h682.666667c59.733333 0 106.666667 46.933333 106.666667 106.666667S891.733333 853.333333 832 853.333333z"
        )

        private val SVG_ARROW_FOLDER = listOf(
            "M456.448 64a170.666667 170.666667 0 0 1 103.253333 34.773333l78.336 59.541334H768c117.824 0 213.333333 95.509333 213.333333 213.333333V746.666667c0 117.824-95.509333 213.333333-213.333333 213.333333H256C138.176 960 42.666667 864.490667 42.666667 746.666667V277.333333C42.666667 159.509333 138.176 64 256 64h200.448z m0 74.666667H256a138.666667 138.666667 0 0 0-138.581333 133.696L117.333333 277.333333v469.333334a138.666667 138.666667 0 0 0 133.696 138.581333L256 885.333333h512a138.666667 138.666667 0 0 0 138.581333-133.696L906.666667 746.666667V371.648a138.666667 138.666667 0 0 0-133.696-138.581333L768 232.981333h-129.962667a74.666667 74.666667 0 0 1-41.173333-12.373333l-4.010667-2.837333-78.314666-59.52a96 96 0 0 0-52.864-19.434667l-5.226667-0.149333z",
            "M469.333333 624a37.333333 37.333333 0 0 1 3.072 74.538667L469.333333 698.666667h-170.666666a37.333333 37.333333 0 0 1-3.072-74.538667L298.666667 624h170.666666z"
        )
    }

    // ==================== 生命周期判据 ====================

    /** 视图是否处于可用状态：既要没被标记销毁，也要真的拿得到 view。 */
    private fun isViewAlive(): Boolean {
        if (viewDestroyed) return false
        return view != null && viewLifecycleOwnerLiveData.value != null
    }

    /**
     * 安全的 view 访问入口。
     * 注意：不要再用 `view != null` 当作 viewLifecycleOwner 可用的判据——
     * androidx 的 destroyFragmentView() 先调 onDestroyView()、之后才把 mView 置 null，
     * 期间 getView() 非 null 但视图已经 detach、生命周期已开始拆除。
     */
    private inline fun withViewLifecycle(block: (View, LifecycleOwner) -> Unit) {
        if (viewDestroyed) return
        val v = view ?: return
        val owner = viewLifecycleOwnerLiveData.value ?: return
        block(v, owner)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appContext = context.applicationContext
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewDestroyed = false
        computeInitialScale()

        val scrollView = ScrollView(requireContext()).apply {
            setBackgroundColor(WHITE)
            isFillViewport = true
        }
        scrollViewRef = scrollView

        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16f), 0, dp(16f), 0)
        }
        scrollView.addView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { _, insets ->
            statusBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            navigationBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyPaddings()
            insets
        }

        wallpaperPath = prefs.getString("wallpaper_path", null)
        cardAlpha = prefs.getInt("card_alpha", 204).coerceIn(0, 255)
        applyWallpaper()

        contentScale = 1f
        // 只建骨架，不绑数据。此时 mView 还是 null，任何依赖 view 的渲染都会空转。
        buildSkeleton()
        // 用持有引用的 token，onDestroyView 才能真正撤掉
        rootLayout.post(fitAllRunnable)

        return scrollView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ★ 全文件唯一一处取 viewLifecycleOwner 的地方。
        // repeatOnLifecycle(STARTED) 会随视图生死自动启停：
        // 视图销毁 → 子协程立即取消；再次 STARTED → 自动重新订阅（等价于原来的 onResume 刷新）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeReadingBook() }
                launch { observeRecentBooks() }
                launch { observeFileList() }
                launch { observeReadStats() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.post { applyTransparentStatusBar() }
    }

    override fun onPause() {
        restoreStatusBar()
        super.onPause()
    }

    override fun onDestroyView() {
        viewDestroyed = true
        cleanupPickerBackCallback()
        if (::rootLayout.isInitialized) {
            // ★ 修复：原来是 removeCallbacks(null)，那是空操作，撤不掉任何东西。
            rootLayout.removeCallbacks(fitAllRunnable)
        }
        super.onDestroyView()
    }

    // ==================== 观察者（只产出数据，不直接碰 view 生命周期） ====================

    private suspend fun observeReadingBook() {
        appDb.bookDao.flowReading().collect { books ->
            readingBook = books.firstOrNull()
            renderReadingCard()
        }
    }

    private suspend fun observeRecentBooks() {
        appDb.bookDao.flowAll().collect { books ->
            recentBooks = books
                .filter { it.durChapterIndex > 0 && it.totalChapterNum > 0 }
                .take(MAX_LIST_ITEMS)
            renderRecentBooks()
        }
    }

    private suspend fun observeFileList() {
        if (!hasStoragePermission()) {
            cachedFiles = emptyList()
            cachedFilesDenied = true
            renderFileList()
            return
        }
        val files = withContext(IO) {
            val dir = File(Environment.getExternalStorageDirectory(), EINK_DIR)
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(MAX_LIST_ITEMS) ?: emptyList()
            } else emptyList()
        }
        cachedFiles = files
        cachedFilesDenied = false
        renderFileList()
    }

    private suspend fun observeReadStats() {
        val today = LocalDate.now()
        val map: Map<LocalDate, Long> = withContext(IO) {
            appDb.readRecordDailyDao.allDesc.mapNotNull { r ->
                runCatching { LocalDate.parse(r.date) to r.readTime }.getOrNull()
            }.toMap()
        }
        dailyMap = map

        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        weekData = (0L..6L).map { monday.plusDays(it) }.map { map[it] ?: 0L }

        renderSummary()
        renderStatsChart()
    }

    // ==================== 渲染（全部先过 isViewAlive） ====================

    private fun buildSkeleton() {
        if (!isAdded || !isViewAliveOrBuilding()) return
        rootLayout.removeAllViews()
        buildHeader()
        buildReadingCard()
        buildRecentAndFileRow()
        buildSummaryCard()
        buildStatsCard()
        bindViews()
    }

    /** onCreateView 期间 mView 尚未赋值，这里允许建骨架但不做数据绑定。 */
    private fun isViewAliveOrBuilding(): Boolean = !viewDestroyed

    private fun bindViews() {
        if (!isViewAlive()) return
        if (this::tvSubtitleGlobal.isInitialized) {
            tvSubtitleGlobal.text = loadSubtitle()
        }
        renderReadingCard()
        renderRecentBooks()
        renderFileList()
        renderSummary()
        renderStatsChart()
    }

    private fun renderReadingCard() {
        if (!isViewAlive()) return
        if (!this::tvReadingName.isInitialized) return
        val b = readingBook
        if (b != null) {
            tvReadingName.text = b.name
            tvReadingAuthor.text = b.author
            tvReadingIntro.text = b.intro ?: ""
            val progress = if (b.totalChapterNum > 0) {
                (b.durChapterIndex * 100 / b.totalChapterNum).coerceIn(0, 100)
            } else 0
            progressReading.setProgress(progress)
            loadCover(ivReadingCover, b)
        } else {
            tvReadingName.text = "暂无正在阅读"
            tvReadingAuthor.text = ""
            tvReadingIntro.text = "去书架选一本书开始阅读吧"
            progressReading.setProgress(0)
        }
    }

    private fun renderRecentBooks() {
        if (!isViewAlive()) return
        if (!this::recentListContainer.isInitialized) return
        recentListContainer.removeAllViews()

        if (recentBooks.isEmpty()) {
            recentListContainer.addView(TextView(requireContext()).apply {
                text = "暂无"
                setTextColor(GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontRecentItem))
                setPadding(0, dp(5f), 0, dp(5f))
            })
            return
        }

        recentBooks.forEach { book ->
            val tv = TextView(requireContext()).apply {
                text = book.name
                setTextColor(BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontRecentItem))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5f), 0, dp(5f))
                isClickable = true
                isFocusable = true
                setOnClickListener { openBook(book) }
            }
            recentListContainer.addView(tv)
        }
    }

    private fun renderFileList() {
        if (!isViewAlive()) return
        if (!this::fileListContainer.isInitialized) return
        fileListContainer.removeAllViews()
        fileItemViews.clear()

        if (cachedFilesDenied) {
            fileListContainer.addView(TextView(requireContext()).apply {
                text = "请先授权文件访问权限"
                setTextColor(GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                setPadding(0, dp(8f), 0, dp(8f))
                isClickable = true
                isFocusable = true
                setOnClickListener { requestStoragePermission() }
            })
            return
        }

        if (cachedFiles.isEmpty()) {
            fileListContainer.addView(TextView(requireContext()).apply {
                text = "暂无文件"
                setTextColor(GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                setPadding(0, dp(8f), 0, dp(8f))
            })
            return
        }

        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        cachedFiles.forEach { file ->
            val tv = TextView(requireContext()).apply {
                text = "${file.name}  ·  ${formatSize(file.length())}  ·  ${sdf.format(Date(file.lastModified()))}"
                setTextColor(BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontFileItem))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5f), 0, dp(5f))
                isClickable = true
                isFocusable = true
                setOnClickListener { openFileTransfer(file.absolutePath) }
            }
            fileListContainer.addView(tv)
            fileItemViews.add(tv)
        }
    }

    private fun renderSummary() {
        if (!isViewAlive()) return
        if (!this::tvSummaryToday.isInitialized) return
        val today = LocalDate.now()
        val map = dailyMap

        tvSummaryToday.text = formatReadTime(map[today] ?: 0L)

        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var weekMs = 0L
        var d = monday
        while (!d.isAfter(today)) {
            weekMs += map[d] ?: 0L
            d = d.plusDays(1)
        }
        tvSummaryWeek.text = formatReadTime(weekMs)

        val firstDay = today.withDayOfMonth(1)
        var monthTotal = 0L
        var days = 0
        var dd = firstDay
        while (!dd.isAfter(today)) {
            monthTotal += map[dd] ?: 0L
            days++
            dd = dd.plusDays(1)
        }
        val avgMs = if (days > 0) monthTotal / days else 0L
        tvSummaryMonthAvg.text = formatReadTime(avgMs)
    }

    private fun renderStatsChart() {
        if (!isViewAlive()) return
        if (!this::chartContainer.isInitialized) return
        chartMaxMs = weekData.maxOrNull()?.coerceAtLeast(MIN_CHART_MS) ?: MIN_CHART_MS
        updateChart(weekData)
        // 下一帧再刷一次，保证 bar 的 height 已经量出来
        chartContainer.post {
            if (isViewAlive()) updateChart(weekData)
        }
    }

    // ==================== 布局测量 ====================

    private fun fitAll() {
        if (viewDestroyed || !::rootLayout.isInitialized) return

        val usable = rootLayout.height - rootLayout.paddingTop - rootLayout.paddingBottom
        if (rootLayout.height <= 0 || usable <= 0) {
            rootLayout.removeCallbacks(fitAllRunnable)
            rootLayout.postDelayed(fitAllRunnable, 50)
            return
        }

        var lo = 0.35f
        var hi = 1.0f
        var best = 0.35f

        // 二分测量：循环里只建骨架量高度，不再重复绑数据（原实现在这里每轮都 post 一次 loadFileList）
        repeat(18) {
            val mid = (lo + hi) / 2f
            contentScale = mid

            rootLayout.removeAllViews()
            buildHeader()
            buildReadingCard()
            buildRecentAndFileRow()
            buildSummaryCard()
            buildStatsCard()

            val gapTotal = gapPx() * 4
            rootLayout.measure(
                View.MeasureSpec.makeMeasureSpec(rootLayout.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rootLayout.height, View.MeasureSpec.AT_MOST)
            )

            var total = gapTotal
            for (i in 0 until rootLayout.childCount) {
                total += rootLayout.getChildAt(i).measuredHeight
            }

            if (total <= usable) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        contentScale = best
        rootLayout.removeAllViews()
        buildHeader()
        buildReadingCard()
        buildRecentAndFileRow()
        buildSummaryCard()
        buildStatsCard()
        bindViews()
        adjustSpacing()
    }

    private fun adjustSpacing() {
        if (viewDestroyed || !::rootLayout.isInitialized) return
        val gap = gapPx()
        for (i in 0 until rootLayout.childCount) {
            val child = rootLayout.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.topMargin = gap
            child.layoutParams = lp
        }
    }

    private fun applyPaddings() {
        if (viewDestroyed || !::rootLayout.isInitialized) return
        rootLayout.setPadding(dp(16f), dp(8f), dp(16f), navigationBarInsetPx + dp(16f))
    }

    private fun applyScaleAndFit() {
        if (!isViewAlive()) return
        rootLayout.removeCallbacks(fitAllRunnable)
        rootLayout.post {
            if (isViewAlive()) {
                computeInitialScale()
                fitAll()
            }
        }
    }

    private fun updateChart(data: List<Long>) {
        if (viewDestroyed) return
        for (i in 0 until 7) {
            val ms = data.getOrElse(i) { 0L }
            if (i < chartTimeLabels.size) {
                chartTimeLabels[i].text =
                    String.format(Locale.getDefault(), "%.1fH", ms / 1000f / 60f / 60f)
                chartTimeLabels[i].setTextColor(BLACK)
            }
            if (i < chartDayLabels.size) chartDayLabels[i].setTextColor(BLACK)

            if (i < chartBarOuters.size && i < chartBars.size) {
                chartBarOuters[i].background = createCapsuleStrokeDrawable(BLACK, 1.2f)
                chartBars[i].background = createCapsuleFillDrawable(BLACK)
                val outer = chartBarOuters[i]
                val inner = chartBars[i]
                outer.post {
                    if (viewDestroyed) return@post
                    val oh = outer.height
                    if (oh > 0) {
                        val usableH = oh - dp(6f)
                        val ratio = (ms.toFloat() / chartMaxMs.toFloat()).coerceIn(0f, 1f)
                        val fillH = (usableH * ratio).toInt().coerceIn(0, usableH)
                        val lp = inner.layoutParams as FrameLayout.LayoutParams
                        lp.height = fillH
                        inner.layoutParams = lp
                    }
                }
            }
        }
    }

    // ==================== 壁纸 ====================

    override fun onResult(data: Intent) {
        if (!isViewAlive()) return
        val uri = data.data ?: return
        if (!isPickingWallpaper) return
        isPickingWallpaper = false
        view?.postDelayed({ cleanupPickerBackCallback() }, 500)
        handleWallpaperUri(uri)
    }

    private fun handleWallpaperUri(uri: Uri) {
        if (!isViewAlive()) return
        try {
            val destFile = File(
                requireContext().filesDir,
                "wallpaper_${System.currentTimeMillis()}.jpg"
            )
            if (uri.isContentScheme()) {
                readUri(uri) { _, inputStream ->
                    destFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                }
            } else {
                val srcPath = uri.path ?: run {
                    Toast.makeText(requireContext(), "壁纸路径无效", Toast.LENGTH_SHORT).show()
                    return
                }
                File(srcPath).inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            wallpaperPath?.let { old ->
                try {
                    val oldFile = File(old)
                    if (oldFile.exists() && oldFile.name.startsWith("wallpaper_")) {
                        oldFile.delete()
                    }
                } catch (_: Exception) {
                }
            }

            wallpaperPath = destFile.absolutePath
            prefs.edit().putString("wallpaper_path", destFile.absolutePath).apply()
            applyWallpaper()
            rebuildAllCards()
            Toast.makeText(requireContext(), "壁纸已设置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "壁纸设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 整体重建 + 重新绑定（用户改了壁纸/透明度之后走这里） */
    private fun rebuildAllCards() {
        if (!isAdded || !isViewAlive()) return
        rootLayout.removeCallbacks(fitAllRunnable)
        rootLayout.removeAllViews()
        buildHeader()
        buildReadingCard()
        buildRecentAndFileRow()
        buildSummaryCard()
        buildStatsCard()
        bindViews()
        rootLayout.post {
            if (isViewAlive()) adjustSpacing()
        }
    }

    private fun showWallpaperPicker() {
        if (!isViewAlive()) return
        isPickingWallpaper = true
        installPickerBackCallback()
        FilePickerDialog.show(
            childFragmentManager,
            mode = HandleFileContract.FILE,
            allowExtensions = arrayOf("jpg", "jpeg", "png", "webp", "gif")
        )
    }

    private fun installPickerBackCallback() {
        withViewLifecycle { _, owner ->
            val act = activity as? ComponentActivity ?: return@withViewLifecycle
            if (pickerBackCallback == null) {
                pickerBackCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val dialog = childFragmentManager.fragments
                            .filterIsInstance<DialogFragment>()
                            .firstOrNull { it.isVisible }
                        if (dialog != null) {
                            dialog.dismiss()
                        }
                        cleanupPickerBackCallback()
                    }
                }
                act.onBackPressedDispatcher.addCallback(owner, pickerBackCallback!!)
            } else {
                pickerBackCallback!!.isEnabled = true
            }
        }
    }

    private fun cleanupPickerBackCallback() {
        pickerBackCallback?.isEnabled = false
        pickerBackCallback = null
    }

    private fun applyWallpaper() {
        val sv = scrollViewRef ?: return
        val path = wallpaperPath
        if (path.isNullOrBlank()) {
            wallpaperDrawable = null
            sv.setBackgroundColor(WHITE)
            return
        }
        try {
            val file = File(path)
            if (!file.exists()) {
                wallpaperDrawable = null
                sv.setBackgroundColor(WHITE)
                return
            }
            val dm = resources.displayMetrics
            val bmp = decodeSampledBitmap(path, dm.widthPixels, dm.heightPixels)
            if (bmp != null) {
                val drawable = BitmapDrawable(resources, bmp).apply {
                    gravity = Gravity.FILL
                }
                wallpaperDrawable = drawable
                sv.background = drawable
            } else {
                wallpaperDrawable = null
                sv.setBackgroundColor(WHITE)
            }
        } catch (_: Exception) {
            wallpaperDrawable = null
            sv.setBackgroundColor(WHITE)
        }
    }

    private fun decodeSampledBitmap(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): android.graphics.Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565

        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ==================== 权限 ====================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!isViewAlive()) return
        if (requestCode == REQ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                observeFileListOnce()
            } else {
                cachedFilesDenied = true
                renderFileList()
            }
        }
    }

    private fun observeFileListOnce() {
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            observeFileList()
        }
    }

    private fun hasStoragePermission(): Boolean {
        val ctx = appContext ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() ||
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + requireContext().packageName)
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    requestPermissions(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        REQ_STORAGE
                    )
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    // ==================== 尺寸 ====================

    private fun fixedDp(v: Float): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)

    private fun dp(v: Float): Int =
        (v * scale * contentScale).dpToPx().toInt().coerceAtLeast(2)

    private fun sp(base: Float): Float = base * contentScale

    private fun readingCoverWpx(): Int = fixedDp(READING_COVER_W_DP)
    private fun readingCoverHpx(): Int = fixedDp(READING_COVER_H_DP)

    private fun cardBgColor(): Int =
        if (wallpaperPath.isNullOrBlank()) WHITE
        else Color.argb(cardAlpha, 0xFF, 0xFF, 0xFF)

    private fun computeInitialScale() {
        val dm = resources.displayMetrics
        val screenWdp = dm.widthPixels / dm.density
        val screenHdp = dm.heightPixels / dm.density
        val scaleW = screenWdp / 400f
        val scaleH = screenHdp / 800f
        val rawScale = minOf(scaleW, scaleH)
        val userFactor = loadUserScaleFactor()
        scale = (rawScale * userFactor).coerceIn(0.35f, 2.2f)
        contentScale = 1f
    }

    private fun gapPx(): Int = (16f * scale * contentScale).dpToPx().toInt().coerceAtLeast(4)

    // ==================== 状态栏 ====================

    private fun applyTransparentStatusBar() {
        val act = activity ?: return
        val w = act.window ?: return
        originalStatusBarColor = w.statusBarColor
        originalSystemUiVisibility = w.decorView.systemUiVisibility

        val controller = WindowInsetsControllerCompat(w, w.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = true
        WindowCompat.setDecorFitsSystemWindows(w, false)
    }

    private fun restoreStatusBar() {
        val act = activity ?: return
        val w = act.window ?: return
        val controller = WindowInsetsControllerCompat(w, w.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.isAppearanceLightStatusBars = false
        WindowCompat.setDecorFitsSystemWindows(w, true)
        w.statusBarColor = originalStatusBarColor
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = originalSystemUiVisibility
    }

    // ==================== 副标题 / 尺寸设置 ====================

    private fun loadSubtitle(): String =
        prefs.getString("subtitle", DEFAULT_SUBTITLE) ?: DEFAULT_SUBTITLE

    private fun saveSubtitle(s: String) = prefs.edit().putString("subtitle", s).apply()

    private fun showSubtitleDialog() {
        if (!isViewAlive()) return
        val editText = EditText(requireContext()).apply {
            setText(tvSubtitleGlobal.text)
            setSelection(text.length)
            setPadding(32.dpToPx(), 24.dpToPx(), 32.dpToPx(), 24.dpToPx())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改副标题")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newText = editText.text.toString().ifBlank { DEFAULT_SUBTITLE }
                saveSubtitle(newText)
                tvSubtitleGlobal.text = newText
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadUserScaleFactor(): Float =
        prefs.getFloat("user_scale_factor", 1.0f).coerceIn(USER_SCALE_MIN, USER_SCALE_MAX)

    private fun saveUserScaleFactor(f: Float) =
        prefs.edit().putFloat("user_scale_factor", f).apply()

    private fun showScaleDialog() {
        if (!isViewAlive()) return
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dpToPx(), 24.dpToPx(), 32.dpToPx(), 8.dpToPx())
        }

        val sizeLabelRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvSizeLabel = TextView(requireContext()).apply {
            text = "主页大小"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSizeValue = TextView(requireContext()).apply {
            text = String.format(Locale.getDefault(), "%.2f", loadUserScaleFactor())
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        sizeLabelRow.addView(tvSizeLabel)
        sizeLabelRow.addView(tvSizeValue)
        container.addView(sizeLabelRow)

        val seekBar = SeekBar(requireContext()).apply {
            max = 100
            progress = ((loadUserScaleFactor() - USER_SCALE_MIN) /
                    (USER_SCALE_MAX - USER_SCALE_MIN) * 100f).toInt().coerceIn(0, 100)
        }
        container.addView(seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val f = USER_SCALE_MIN + (USER_SCALE_MAX - USER_SCALE_MIN) * progress / 100f
                tvSizeValue.text = String.format(Locale.getDefault(), "%.2f", f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val cardAlphaLabelRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16.dpToPx(), 0, 0)
        }
        val tvAlphaLabel = TextView(requireContext()).apply {
            text = "卡片透明度"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvAlphaValue = TextView(requireContext()).apply {
            text = "${cardAlpha * 100 / 255}%"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        cardAlphaLabelRow.addView(tvAlphaLabel)
        cardAlphaLabelRow.addView(tvAlphaValue)
        container.addView(cardAlphaLabelRow)

        val seekAlpha = SeekBar(requireContext()).apply {
            max = 100
            progress = cardAlpha * 100 / 255
        }
        container.addView(seekAlpha)
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                cardAlpha = 255 * seekAlpha.progress / 100
                prefs.edit().putInt("card_alpha", cardAlpha).apply()
                rebuildAllCards()
            }
        })

        val wallpaperRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20.dpToPx(), 0, 0)
        }

        val btnPick = TextView(requireContext()).apply {
            text = "选择壁纸"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(28.dpToPx(), 12.dpToPx(), 28.dpToPx(), 12.dpToPx())
            background = createRoundRectDrawable(8f, WHITE, 1f, BLACK)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("选择壁纸")
                    .setItems(arrayOf("文件管理", "系统图库")) { _, which ->
                        when (which) {
                            0 -> showWallpaperPicker()
                            1 -> pickWallpaperFromGallery.launch("image/*")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        wallpaperRow.addView(btnPick)

        val btnClear = TextView(requireContext()).apply {
            text = "清除壁纸"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(28.dpToPx(), 12.dpToPx(), 28.dpToPx(), 12.dpToPx())
            background = createRoundRectDrawable(8f, WHITE, 1f, BLACK)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                wallpaperPath?.let { old ->
                    try {
                        val oldFile = File(old)
                        if (oldFile.exists()) oldFile.delete()
                    } catch (_: Exception) {
                    }
                }
                wallpaperPath = null
                wallpaperDrawable = null
                prefs.edit().remove("wallpaper_path").apply()
                applyWallpaper()
                rebuildAllCards()
                Toast.makeText(requireContext(), "壁纸已清除", Toast.LENGTH_SHORT).show()
            }
        }
        wallpaperRow.addView(btnClear)
        (btnClear.layoutParams as LinearLayout.LayoutParams).marginStart = 16.dpToPx()

        container.addView(wallpaperRow)

        AlertDialog.Builder(requireContext())
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val f = USER_SCALE_MIN + (USER_SCALE_MAX - USER_SCALE_MIN) * seekBar.progress / 100f
                saveUserScaleFactor(f)
                cardAlpha = 255 * seekAlpha.progress / 100
                prefs.edit().putInt("card_alpha", cardAlpha).apply()
                applyScaleAndFit()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("重置") { _, _ ->
                saveUserScaleFactor(1.0f)
                cardAlpha = 204
                prefs.edit().putInt("card_alpha", cardAlpha).apply()
                applyScaleAndFit()
            }
            .show()
    }

    // ==================== 卡片构建 ====================

    private fun buildHeader() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val textLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvTitleGlobal = TextView(requireContext()).apply {
            text = "阅读 E-ink"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontTitle))
            setTypeface(null, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        textLayout.addView(tvTitleGlobal)

        tvSubtitleGlobal = TextView(requireContext()).apply {
            text = loadSubtitle()
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontSubtitle))
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4f), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSubtitleDialog() }
        }
        textLayout.addView(tvSubtitleGlobal)
        header.addView(textLayout)

        val btnContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val iconSize = fixedDp(30f)
        val iconPadding = fixedDp(7f)

        ivScaleBtn = ImageView(requireContext()).apply {
            setImageDrawable(createScaleSizeDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = createCircleStrokeDrawable()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                .apply { marginStart = fixedDp(8f) }
            isClickable = true
            isFocusable = true
            setOnClickListener { showScaleDialog() }
        }
        btnContainer.addView(ivScaleBtn)

        ivExploreBtn = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_bottom_explore)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = createCircleStrokeDrawable()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            imageTintList = ColorStateList.valueOf(BLACK)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                .apply { marginStart = fixedDp(8f) }
            isClickable = true
            isFocusable = true
            setOnClickListener { gotoTab(2) }
        }
        btnContainer.addView(ivExploreBtn)

        ivMyBtn = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_bottom_person)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = createCircleStrokeDrawable()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            imageTintList = ColorStateList.valueOf(BLACK)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                .apply { marginStart = fixedDp(8f) }
            isClickable = true
            isFocusable = true
            setOnClickListener { gotoTab(3) }
        }
        btnContainer.addView(ivMyBtn)

        header.addView(btnContainer)
        rootLayout.addView(header)
    }

    private fun buildReadingCard() {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = createRoundRectDrawable(16f, cardBgColor(), STROKE_DP, BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        val coverW = readingCoverWpx()
        val coverH = readingCoverHpx()

        ivReadingCover = RoundedImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = createRoundRectDrawable(6f, cardBgColor(), STROKE_DP, BLACK)
            setCornerRadius(fixedDp(6f).toFloat())
            layoutParams = LinearLayout.LayoutParams(coverW, coverH)
        }
        card.addView(ivReadingCover)

        val infoLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14f) }
        }

        tvReadingName = TextView(requireContext()).apply {
            text = "暂无正在阅读"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontReadingName))
            setTypeface(null, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        infoLayout.addView(tvReadingName)

        tvReadingAuthor = TextView(requireContext()).apply {
            text = ""
            setTextColor(GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontReadingAuthor))
            setPadding(0, dp(4f), 0, 0)
        }
        infoLayout.addView(tvReadingAuthor)

        tvReadingIntro = TextView(requireContext()).apply {
            text = ""
            setTextColor(GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontReadingIntro))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8f), 0, 0)
        }
        infoLayout.addView(tvReadingIntro)
        card.addView(infoLayout)

        val ringSize = fixedDp(58f)
        progressReading = CircularProgressView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ringSize, ringSize)
                .apply { marginStart = dp(10f) }
            setScaleTextSize(sp(10f))
            setInnerBgColor(cardBgColor())
        }
        card.addView(progressReading)

        card.setOnClickListener { readingBook?.let { openBook(it) } }
        rootLayout.addView(card)
    }

    private fun gotoTab(index: Int) {
        val vp = activity?.findViewById<androidx.viewpager.widget.ViewPager>(R.id.view_pager_main)
            ?: return
        vp.setCurrentItem(index, false)
    }

    private fun buildRecentAndFileRow() {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val recentCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = createRoundRectDrawable(16f, cardBgColor(), STROKE_DP, BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = dp(6f) }
        }

        val recentHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        tvRecentTitleGlobal = TextView(requireContext()).apply {
            text = "最近阅读"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontRecentTitle))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ivArrow1 = ImageView(requireContext()).apply {
            setImageDrawable(createSvgDrawable(SVG_ARROW_RECENT))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
        }
        recentHeader.addView(tvRecentTitleGlobal)
        recentHeader.addView(ivArrow1)
        recentHeader.setOnClickListener { gotoTab(1) }
        recentCard.addView(recentHeader)

        recentListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        recentCard.addView(recentListContainer)

        recentCard.isClickable = true
        recentCard.isFocusable = true
        recentCard.setOnClickListener { gotoTab(1) }
        row.addView(recentCard)

        val fileCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = createRoundRectDrawable(16f, cardBgColor(), STROKE_DP, BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = dp(6f) }
            isClickable = true
            isFocusable = true
        }

        val fileHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvFileTitleGlobal = TextView(requireContext()).apply {
            text = "文件传输"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontFileTitle))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ivArrow2 = ImageView(requireContext()).apply {
            setImageDrawable(createSvgDrawable(SVG_ARROW_FOLDER))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
        }
        fileHeader.addView(tvFileTitleGlobal)
        fileHeader.addView(ivArrow2)
        fileCard.addView(fileHeader)

        fileListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        fileCard.addView(fileListContainer)
        fileCard.setOnClickListener {
            if (!hasStoragePermission()) requestStoragePermission()
            else {
                observeFileListOnce()
                openFileTransfer(null)
            }
        }
        row.addView(fileCard)
        rootLayout.addView(row)

        row.post {
            if (row.childCount == 2) {
                val c1 = row.getChildAt(0)
                val c2 = row.getChildAt(1)
                c1.layoutParams = c1.layoutParams.apply {
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                }
                c2.layoutParams = c2.layoutParams.apply {
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                }
                row.requestLayout()
            }
        }
    }

    private fun buildSummaryCard() {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createRoundRectDrawable(16f, cardBgColor(), STROKE_DP, BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        val colToday = buildSummaryColumn("今日时长")
        tvSummaryToday = colToday.second
        card.addView(
            colToday.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(buildSummaryDivider())
        val colWeek = buildSummaryColumn("本周时长")
        tvSummaryWeek = colWeek.second
        card.addView(
            colWeek.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(buildSummaryDivider())
        val colMonth = buildSummaryColumn("本月平均")
        tvSummaryMonthAvg = colMonth.second
        card.addView(
            colMonth.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        card.setOnClickListener {
            startActivity(Intent(requireContext(), ReadRecordActivity::class.java))
        }
        rootLayout.addView(card)
    }

    private fun buildSummaryColumn(label: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val tvLabel = TextView(requireContext()).apply {
            text = label
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontSummaryLabel))
            gravity = Gravity.CENTER
        }
        col.addView(tvLabel)
        val tvValue = TextView(requireContext()).apply {
            text = "0分钟"
            setTextColor(BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontSummaryValue))
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
        }
        col.addView(tvValue)
        return col to tvValue
    }

    private fun buildSummaryDivider(): View {
        return View(requireContext()).apply {
            setBackgroundColor(LIGHT_GRAY)
            layoutParams = LinearLayout.LayoutParams(
                dp(1f),
                (36f * scale * contentScale).dpToPx().toInt().coerceAtLeast(10)
            )
        }
    }

    private fun formatReadTime(ms: Long): String {
        val totalMin = ms / (1000 * 60)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分"
            h > 0 -> "${h}小时"
            m > 0 -> "${m}分钟"
            else -> "0分钟"
        }
    }

    private fun buildStatsCard() {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRectDrawable(16f, cardBgColor(), STROKE_DP, BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            isClickable = true
            isFocusable = true
        }

        val chartH = (90f * scale * contentScale).dpToPx().toInt().coerceAtLeast(30)

        chartContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(8f), 0, dp(8f))
            minimumHeight = chartH
        }

        chartBars.clear()
        chartBarOuters.clear()
        chartTimeLabels.clear()
        chartDayLabels.clear()

        val dayLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (i in 0 until 7) {
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val tvTime = TextView(requireContext()).apply {
                text = "0.0H"
                setTextColor(BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontChartText))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6f))
            }
            col.addView(tvTime)
            chartTimeLabels.add(tvTime)

            val barArea = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, chartH
                )
                setPadding(0, dp(6f), 0, dp(6f))
            }

            val barWidth = (14f * scale * contentScale).dpToPx().toInt().coerceAtLeast(4)

            val outer = View(requireContext()).apply {
                background = createCapsuleStrokeDrawable(BLACK, 1.2f)
                layoutParams = FrameLayout.LayoutParams(
                    barWidth, FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
            }
            barArea.addView(outer)
            chartBarOuters.add(outer)

            val fill = View(requireContext()).apply {
                background = createCapsuleFillDrawable(BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    barWidth - dp(6f), 0
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    bottomMargin = dp(3f)
                }
            }
            barArea.addView(fill)
            chartBars.add(fill)

            col.addView(barArea)

            val tvDay = TextView(requireContext()).apply {
                text = dayLabels[i]
                setTextColor(BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseFontChartText))
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, 0)
            }
            col.addView(tvDay)
            chartDayLabels.add(tvDay)
            chartContainer.addView(col)
        }
        card.addView(chartContainer)

        card.setOnClickListener {
            startActivity(Intent(requireContext(), ReadRecordActivity::class.java))
        }
        rootLayout.addView(card)
    }

    // ==================== 跳转 / 工具 ====================

    private fun openFileTransfer(filePath: String?) {
        val intent = Intent(requireContext(), LanTransferActivity::class.java)
        if (filePath != null) intent.putExtra("filePath", filePath)
        intent.putExtra("from", "home")
        startActivity(intent)
    }

    private fun openBook(book: Book) {
        startActivity(Intent(requireContext(), ReadBookActivity::class.java).apply {
            putExtra("bookUrl", book.bookUrl)
            putExtra("from", "home")
        })
    }

    private fun loadCover(iv: ImageView, book: Book) {
        try {
            val url = book.getDisplayCover()
            if (!url.isNullOrBlank()) {
                Glide.with(requireContext()).load(url).centerCrop().into(iv)
            }
        } catch (_: Exception) {
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 ->
            String.format(Locale.getDefault(), "%.1fMB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    // ==================== Drawable ====================

    private fun createSvgDrawable(pathData: List<String>): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLACK
            style = Paint.Style.FILL
        }
        private val parsedPaths: List<Path?> = pathData.map {
            try {
                PathParser.createPathFromPathData(it)
            } catch (_: Throwable) {
                null
            }
        }
        private val m = Matrix()

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            m.reset()
            m.setScale(w / 1024f, h / 1024f)
            m.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            parsedPaths.forEach { p ->
                if (p != null) {
                    canvas.drawPath(Path().apply { p.transform(m, this) }, paint)
                }
            }
        }

        override fun setAlpha(a: Int) {
            paint.alpha = a
        }

        override fun setColorFilter(cf: ColorFilter?) {
            paint.colorFilter = cf
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun createScaleSizeDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLACK
            style = Paint.Style.FILL
        }
        private val svg1 =
            "M920,416H616c-4.4,0 -8,3.6 -8,8v112c0,4.4 3.6,8 8,8h48c4.4,0 8,-3.6 8,-8v-56h60v320h-46c-4.4,0 -8,3.6 -8,8v48c0,4.4 3.6,8 8,8h164c4.4,0 8,-3.6 8,-8v-48c0,-4.4 -3.6,-8 -8,-8h-46V480h60v56c0,4.4 3.6,8 8,8h48c4.4,0 8,-3.6 8,-8V424c0,-4.4 -3.6,-8 -8,-8z"
        private val svg2 =
            "M656,296V168c0,-4.4 -3.6,-8 -8,-8H104c-4.4,0 -8,3.6 -8,8v128c0,4.4 3.6,8 8,8h56c4.4,0 8,-3.6 8,-8v-64h168v560h-92c-4.4,0 -8,3.6 -8,8v56c0,4.4 3.6,8 8,8h264c4.4,0 8,-3.6 8,-8v-56c0,-4.4 -3.6,-8 -8,-8h-92V232h168v64c0,4.4 3.6,8 8,8h56c4.4,0 8,-3.6 8,-8z"
        private val p1: Path? = try {
            PathParser.createPathFromPathData(svg1)
        } catch (_: Throwable) {
            null
        }
        private val p2: Path? = try {
            PathParser.createPathFromPathData(svg2)
        } catch (_: Throwable) {
            null
        }
        private val m = Matrix()

        override fun draw(canvas: Canvas) {
            val q1 = p1 ?: return
            val q2 = p2 ?: return
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            m.reset()
            m.setScale(w / 1024f, h / 1024f)
            m.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawPath(Path().apply { q1.transform(m, this) }, paint)
            canvas.drawPath(Path().apply { q2.transform(m, this) }, paint)
        }

        override fun setAlpha(a: Int) {
            paint.alpha = a
        }

        override fun setColorFilter(cf: ColorFilter?) {
            paint.colorFilter = cf
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun createRoundRectDrawable(
        radiusDp: Float, bgColor: Int, strokeWidthDp: Float, strokeColor: Int
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.dpToPx()
        setColor(bgColor)
        if (strokeWidthDp > 0) setStroke(strokeWidthDp.dpToPx().toInt(), strokeColor)
    }

    private fun createCapsuleStrokeDrawable(color: Int, strokeWidthDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(cardBgColor())
            setStroke(strokeWidthDp.dpToPx().toInt(), color)
        }

    private fun createCapsuleFillDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(color)
        }

    private fun createCircleStrokeDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(cardBgColor())
            setStroke(1.dpToPx(), BLACK)
        }
}

private class RoundedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val path = Path()
    private val rectF = RectF()
    private val xferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var radius = 0f

    fun setCornerRadius(r: Float) {
        radius = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f || width == 0 || height == 0) {
            super.onDraw(canvas)
            return
        }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        path.reset()
        rectF.set(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, xferPaint)
        canvas.restoreToCount(save)
    }
}

private class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * density

    private val thinStroke = dp(1.5f)
    private val lineGap = dp(10f)
    private val arcPadding = dp(3f)

    private val thinLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thinStroke
        color = Color.BLACK
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private var progress = 0

    fun setProgress(p: Int) {
        progress = p.coerceIn(0, 100)
        invalidate()
    }

    fun setScaleTextSize(sizeSp: Float) {
        textPaint.textSize = sizeSp * scaledDensity
        invalidate()
    }

    fun setInnerBgColor(color: Int) {
        bgPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outerR = minOf(cx, cy) - thinStroke / 2f
        if (outerR <= 0f) return
        val innerR = outerR - lineGap
        if (innerR <= 0f) return

        canvas.drawCircle(cx, cy, innerR - thinStroke / 2f, bgPaint)
        canvas.drawCircle(cx, cy, outerR, thinLinePaint)
        canvas.drawCircle(cx, cy, innerR, thinLinePaint)

        val arcWidth = lineGap - thinStroke - arcPadding * 2
        if (arcWidth > 0f) {
            progressPaint.strokeWidth = arcWidth
            val midR = (outerR + innerR) / 2f
            val rectF = RectF(cx - midR, cy - midR, cx + midR, cy + midR)
            val sweep = progress / 100f * 360f
            if (sweep > 0f) canvas.drawArc(rectF, -90f, sweep, false, progressPaint)
        }

        val text = "$progress%"
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val textY = cy - (fm.ascent + textHeight / 2f)
        canvas.drawText(text, cx, textY, textPaint)
    }
}
