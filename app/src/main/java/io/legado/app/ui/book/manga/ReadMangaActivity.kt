package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.util.FixedPreloadSizeProvider
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.book.isImage
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadManga
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.ScrollTimer
import io.legado.app.ui.book.read.MangaMenu
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.canScroll
import io.legado.app.utils.fastBinarySearch
import io.legado.app.utils.findCenterViewPosition
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.text.DecimalFormat
import kotlin.math.ceil

class ReadMangaActivity : VMBaseActivity<ActivityMangaBinding, ReadMangaViewModel>(),
    ReadManga.Callback, ChangeBookSourceDialog.CallBack, MangaMenu.CallBack,
    MangaColorFilterDialog.Callback, ScrollTimer.ScrollCallback, MangaEpaperDialog.Callback {

    // ============ 内嵌 EPDC 水波纹 ============
    private object Ripple {
        private const val TAG = "EPDCRipple"
        private const val FORCE_NEXT_PAGE_H = 0x01000063

        private var clazz: Class<*>? = null
        private var post1: Method? = null
        private var post2: Method? = null
        private var force: Method? = null
        private var inited = false

        @Synchronized
        fun init(): Boolean {
            if (inited) return clazz != null
            inited = true
            try {
                clazz = Class.forName("android.eink.EPDCDevice")
                post1 = try {
                    clazz!!.getMethod("nativePostCommand", String::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }
                post2 = try {
                    clazz!!.getMethod(
                        "nativePostCommand",
                        String::class.java,
                        Array<String>::class.java
                    )
                } catch (e: NoSuchMethodException) {
                    null
                }
                force = try {
                    clazz!!.getMethod("setForceNextPostMode", Int::class.javaPrimitiveType)
                } catch (e: NoSuchMethodException) {
                    null
                }
                Log.i(TAG, "loaded post1=${post1 != null} post2=${post2 != null} force=${force != null}")
            } catch (t: Throwable) {
                Log.w(TAG, "not available: $t")
                clazz = null
            }
            return clazz != null
        }

        fun isSupported(): Boolean {
            return init()
        }

        fun apply(direction: Int, speed: Int) {
            if (!init()) return
            val effect = direction or speed
            try {
                val cmd = "next-effect-type $effect"
                if (post2 != null) {
                    post2!!.invoke(null, cmd, null)
                } else if (post1 != null) {
                    post1!!.invoke(null, cmd)
                }
                force?.invoke(null, FORCE_NEXT_PAGE_H)
                Log.i(TAG, "apply dir=$direction speed=$speed effect=$effect")
            } catch (t: Throwable) {
                Log.w(TAG, "apply failed: $t")
            }
        }
    }

    companion object {
        private const val MENU_RIPPLE = 9001
        private const val MENU_RIPPLE_SPEED = 9002
        private const val MENU_RIPPLE_WATERFALL = 9003
        private const val MENU_RIPPLE_REVERSE = 9004
        private const val RIPPLE_PREFS = "ripple_cfg"
    }

    // ============ 水波纹设置 ============
    private val ripplePrefs by lazy {
        getSharedPreferences(RIPPLE_PREFS, Context.MODE_PRIVATE)
    }

    private fun isRippleEnabled(): Boolean = ripplePrefs.getBoolean("enabled", true)
    private fun setRippleEnabled(v: Boolean) {
        ripplePrefs.edit().putBoolean("enabled", v).apply()
    }

    private fun rippleSpeed(): String = ripplePrefs.getString("speed", "slow") ?: "slow"
    private fun setRippleSpeed(v: String) {
        ripplePrefs.edit().putString("speed", v).apply()
    }

    private fun isRippleWaterfall(): Boolean = ripplePrefs.getBoolean("waterfall", false)
    private fun setRippleWaterfall(v: Boolean) {
        ripplePrefs.edit().putBoolean("waterfall", v).apply()
    }

    private fun isRippleReverse(): Boolean = ripplePrefs.getBoolean("reverse", false)
    private fun setRippleReverse(v: Boolean) {
        ripplePrefs.edit().putBoolean("reverse", v).apply()
    }

    private fun rippleSpeedBits(): Int {
        return when (rippleSpeed()) {
            "medium" -> 64
            "fast" -> 0
            else -> 128
        }
    }

    /**
     * 方向映射：1=右 2=左 3=下 4=上
     * 非瀑布：下一页=右(1)，上一页=左(2)
     * 瀑布：  下一页=上(4)，上一页=下(3)
     */
    private fun calcDirection(forward: Boolean): Int {
        var f = forward
        if (isRippleReverse()) f = !f
        return if (isRippleWaterfall()) {
            if (f) 4 else 3
        } else {
            if (f) 1 else 2
        }
    }

    private fun rippleNext() {
        if (!isRippleEnabled()) return
        try {
            Ripple.apply(direction = calcDirection(true), speed = rippleSpeedBits())
        } catch (t: Throwable) {
        }
    }

    private fun ripplePrev() {
        if (!isRippleEnabled()) return
        try {
            Ripple.apply(direction = calcDirection(false), speed = rippleSpeedBits())
        } catch (t: Throwable) {
        }
    }

    // ============ 原有字段 ============
    private val mLayoutManager by lazy {
        MangaLayoutManager(this)
    }
    private val mAdapter: MangaAdapter by lazy {
        MangaAdapter(this)
    }

    private val mSizeProvider by lazy {
        FixedPreloadSizeProvider<Any>(resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
    }

    private val mPagerSnapHelper: PagerSnapHelper by lazy {
        PagerSnapHelper()
    }

    private lateinit var mMangaFooterConfig: MangaFooterConfig
    private val mLabelBuilder by lazy { StringBuilder() }

    private var mMenu: Menu? = null

    private var mRecyclerViewPreloader: RecyclerViewPreloader<Any>? = null

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null
    private val mScrollTimer by lazy {
        ScrollTimer(this, binding.recyclerView, lifecycleScope).apply {
            setSpeed(AppConfig.mangaAutoPageSpeed)
        }
    }
    private var enableAutoScrollPage = false
    private var enableAutoScroll = false
    private val mLinearInterpolator by lazy {
        LinearInterpolator()
    }

    private val loadMoreView by lazy {
        LoadMoreView(this).apply {
            setBackgroundColor(getCompatColor(R.color.book_ant_10))
            setLoadingColor(R.color.white)
            setLoadingTextColor(R.color.white)
        }
    }

    private val tocActivity = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.openChapter(it[0] as Int, it[1] as Int)
        }
    }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadManga.loadOrUpContent()
            }
        }

    override val binding by viewBinding(ActivityMangaBinding::inflate)
    override val viewModel by viewModels<ReadMangaViewModel>()
    private val loadingViewVisible get() = binding.flLoading.isVisible
    private val df by lazy {
        DecimalFormat("0.0%")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        ReadManga.register(this)
        upSystemUiVisibility(false)
        initRecyclerView()
        binding.tvRetry.setOnClickListener {
            binding.llLoading.isVisible = true
            binding.llRetry.isGone = true
            ReadManga.loadOrUpContent()
        }
        binding.pbLoading.isVisible = !AppConfig.isEInkMode
        mAdapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading && ReadManga.hasNextChapter) {
                loadMoreView.startLoad()
                ReadManga.loadOrUpContent()
            }
        }
        loadMoreView.gone()
        mMangaFooterConfig =
            GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
                ?: MangaFooterConfig()
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) {
            mMangaFooterConfig = it
            val item = mAdapter.getItem(binding.recyclerView.findCenterViewPosition())
            upInfoBar(item)
        }
    }

    private fun initRecyclerView() {
        val mangaColorFilter =
            GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
                ?: MangaColorFilterConfig()
        mAdapter.run {
            setMangaImageColorFilter(mangaColorFilter)
            enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            enableGray(AppConfig.enableMangaGray)
        }
        setHorizontalScroll(true)
        binding.recyclerView.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            setDisableClickScroll(AppConfig.disableClickScroll)
            setDisableMangaScale(AppConfig.disableMangaScale)
            setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
            pageTurnListener = { direction ->
                if (direction > 0) {
                    val nextPos = ReadManga.durChapterPos + 1
                    if (nextPos < (ReadManga.curMangaChapter?.imageCount ?: 0)) {
                        rippleNext()
                        ReadManga.durChapterPos = nextPos
                        ReadManga.curPageChanged()
                        skipToPage(nextPos)
                    } else {
                        ReadManga.moveToNextChapter()
                    }
                } else {
                    val prevPos = ReadManga.durChapterPos - 1
                    if (prevPos >= 0) {
                        ripplePrev()
                        ReadManga.durChapterPos = prevPos
                        ReadManga.curPageChanged()
                        skipToPage(prevPos)
                    } else {
                        ReadManga.moveToPrevChapter()
                    }
                }
            }
            setPreScrollListener { _, _, _, position ->
                if (mAdapter.isNotEmpty()) {
                    val item = mAdapter.getItem(position)
                    if (item is MangaPage) {
                        if (ReadManga.durChapterIndex < item.chapterIndex) {
                            ReadManga.moveToNextChapter()
                        } else if (ReadManga.durChapterIndex > item.chapterIndex) {
                            ReadManga.moveToPrevChapter()
                        } else {
                            ReadManga.durChapterPos = item.index
                            ReadManga.curPageChanged()
                        }
                        binding.mangaMenu.upSeekBar(item.index, item.imageCount)
                        upInfoBar(item)
                    }
                }
            }
        }
        binding.webtoonFrame.run {
            onTouchMiddle {
                if (!binding.mangaMenu.isVisible && !loadingViewVisible) {
                    binding.mangaMenu.runMenuIn()
                }
            }
            onNextPage {
                scrollToNext()
            }
            onPrevPage {
                scrollToPrev()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initData(intent)
        justInitData = true
    }

    override fun upContent() {
        lifecycleScope.launch {
            setTitle(ReadManga.book?.name)
            val data = withContext(IO) { ReadManga.mangaContents }
            val pos = data.pos
            val list = data.items.filterIsInstance<MangaPage>()
            val newPos = list.indexOfFirst {
                it.chapterIndex == ReadManga.durChapterIndex && it.index == pos
            }.coerceAtLeast(0)

            val curFinish = data.curFinish
            val nextFinish = data.nextFinish
            mAdapter.submitList(list) {
                if (loadingViewVisible && curFinish) {
                    binding.infobar.isVisible = true
                    upInfoBar(list[newPos])
                    mLayoutManager.scrollToPositionWithOffset(newPos, 0)
                    binding.flLoading.isGone = true
                    loadMoreView.visible()
                    binding.mangaMenu.upSeekBar(
                        ReadManga.durChapterPos, ReadManga.curMangaChapter!!.imageCount
                    )
                }

                if (curFinish) {
                    if (!ReadManga.hasNextChapter) {
                        loadMoreView.noMore("暂无章节了！")
                    } else if (nextFinish) {
                        loadMoreView.stopLoad()
                    } else {
                        loadMoreView.startLoad()
                    }
                }
            }
        }
    }

    private fun upInfoBar(page: Any?) {
        if (page !is MangaPage) {
            return
        }
        val chapterIndex = page.chapterIndex
        val chapterSize = page.chapterSize
        val chapterPos = page.index
        val imageCount = page.imageCount
        val chapterName = page.mChapterName
        mMangaFooterConfig.run {
            mLabelBuilder.clear()
            binding.infobar.isGone = hideFooter
            binding.infobar.textInfoAlignment = footerOrientation

            if (!hideChapterName) {
                mLabelBuilder.append(chapterName).append(" ")
            }

            if (!hidePageNumber) {
                if (!hidePageNumberLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_page_number))
                }
                mLabelBuilder.append("${chapterPos + 1}/${imageCount}").append(" ")
            }

            if (!hideChapter) {
                if (!hideChapterLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_chapter))
                }
                mLabelBuilder.append("${chapterIndex + 1}/${chapterSize}").append(" ")
            }

            if (!hideProgressRatio) {
                if (!hideProgressRatioLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_progress))
                }
                val percent = if (chapterSize == 0 || imageCount == 0 && chapterIndex == 0) {
                    "0.0%"
                } else if (imageCount == 0) {
                    df.format((chapterIndex + 1.0f) / chapterSize.toDouble())
                } else {
                    var p =
                        df.format(
                            chapterIndex * 1.0f / chapterSize + 1.0f /
                                    chapterSize * (chapterPos + 1) / imageCount.toDouble()
                        )
                    if (p == "100.0%" && (chapterIndex + 1 != chapterSize || chapterPos + 1 != imageCount)) {
                        p = "99.9%"
                    }
                    p
                }
                mLabelBuilder.append(percent)
            }
        }
        binding.infobar.update(
            if (mLabelBuilder.isEmpty()) "" else mLabelBuilder.toString()
        )
    }

    override fun onResume() {
        super.onResume()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadManga.inBookshelf) {
                ReadManga.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = true
        }
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (ReadManga.inBookshelf) {
            ReadManga.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) {
                    ReadManga.syncProgress()
                } else {
                    ReadManga.uploadProgress()
                }
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        ReadManga.cancelPreDownloadTask()
        networkChangedListener.unRegister()
        mScrollTimer.isEnabledPage = false
        mScrollTimer.isEnabled = false
    }

    override fun loadFail(msg: String, retry: Boolean) {
        lifecycleScope.launch {
            if (loadingViewVisible) {
                binding.llLoading.isGone = true
                binding.llRetry.isVisible = true
                binding.tvRetry.isVisible = retry
                binding.tvMsg.text = msg
            } else {
                loadMoreView.error(null, "加载失败，点击重试")
            }
        }
    }

    override fun onDestroy() {
        ReadManga.unregister(this)
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadManga.setProgress(progress)
            }
            noButton()
        }
    }

    override fun showLoading() {
        lifecycleScope.launch {
            binding.flLoading.isVisible = true
        }
    }

    override fun startLoad() {
        lifecycleScope.launch {
            loadMoreView.startLoad()
        }
    }

    override fun scrollBy(distance: Int) {
        if (!binding.recyclerView.canScroll(1)) {
            return
        }
        val time = ceil(16f / distance * 10000).toInt()
        binding.recyclerView.smoothScrollBy(10000, 10000, mLinearInterpolator, time)
    }

    override fun scrollPage() {
        scrollToNext()
    }

    override val oldBook: Book?
        get() = ReadManga.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isImage) {
            binding.flLoading.isVisible = true
            viewModel.changeTo(book, toc)
        } else {
            toastOnUi("所选择的源不是漫画源")
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        mAdapter.setMangaImageColorFilter(config)
        updateWindowBrightness(config.l)
    }

    @SuppressLint("StringFormatMatches")
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_manga, menu)

        val hideIds = intArrayOf(
            R.id.menu_disable_manga_scale,
            R.id.menu_disable_click_scroll,
            R.id.menu_enable_auto_page,
            R.id.menu_manga_auto_page_speed,
            R.id.menu_enable_horizontal_scroll,
            R.id.menu_manga_color_filter,
            R.id.menu_enable_auto_scroll,
            R.id.menu_epaper_manga,
            R.id.menu_epaper_manga_setting,
            R.id.menu_disable_horizontal_page_snap,
            R.id.menu_disable_manga_page_anim,
            R.id.menu_gray_manga
        )
        for (id in hideIds) {
            menu.findItem(id)?.isVisible = false
        }

        if (Ripple.isSupported()) {
            menu.add(0, MENU_RIPPLE, 0, "启用波纹")
                .setCheckable(true)
                .setChecked(isRippleEnabled())
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            menu.add(0, MENU_RIPPLE_SPEED, 1, "波纹速度")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            menu.add(0, MENU_RIPPLE_WATERFALL, 2, "瀑布波纹")
                .setCheckable(true)
                .setChecked(isRippleWaterfall())
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            menu.add(0, MENU_RIPPLE_REVERSE, 3, "反转波纹")
                .setCheckable(true)
                .setChecked(isRippleReverse())
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        upMenu(menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    @SuppressLint("StringFormatMatches", "NotifyDataSetChanged")
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_RIPPLE -> {
                item.isChecked = !item.isChecked
                setRippleEnabled(item.isChecked)
                toastOnUi(if (item.isChecked) "已启用波纹" else "已关闭波纹")
            }

            MENU_RIPPLE_SPEED -> {
                showRippleSpeedDialog()
            }

            MENU_RIPPLE_WATERFALL -> {
                item.isChecked = !item.isChecked
                setRippleWaterfall(item.isChecked)
                toastOnUi(if (item.isChecked) "瀑布波纹：开" else "瀑布波纹：关")
            }

            MENU_RIPPLE_REVERSE -> {
                item.isChecked = !item.isChecked
                setRippleReverse(item.isChecked)
                toastOnUi(if (item.isChecked) "反转波纹：开" else "反转波纹：关")
            }

            R.id.menu_change_source -> {
                binding.mangaMenu.runMenuOut()
                ReadManga.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_catalog -> {
                ReadManga.book?.let {
                    tocActivity.launch(it.bookUrl)
                }
            }

            R.id.menu_refresh -> {
                binding.flLoading.isVisible = true
                ReadManga.book?.let {
                    viewModel.refreshContentDur(it)
                }
            }

            R.id.menu_pre_manga_number -> {
                showNumberPickerDialog(
                    0,
                    getString(R.string.pre_download),
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    item.title = getString(R.string.pre_download_m, it)
                    setRecyclerViewPreloader(it)
                }
            }

            R.id.menu_manga_footer_config -> {
                showDialogFragment(MangaFooterSettingDialog())
            }

            R.id.menu_hide_manga_title -> {
                item.isChecked = !item.isChecked
                AppConfig.hideMangaTitle = item.isChecked
                ReadManga.loadContent()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showRippleSpeedDialog() {
        val items = arrayOf("慢", "中", "快")
        val values = arrayOf("slow", "medium", "fast")
        val currentIdx = values.indexOf(rippleSpeed()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("波纹速度")
            .setSingleChoiceItems(items, currentIdx) { dlg, which ->
                setRippleSpeed(values[which])
                toastOnUi("已切换到：${items[which]}")
                dlg.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun openBookInfoActivity() {
        ReadManga.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    override fun upSystemUiVisibility(menuIsVisible: Boolean) {
        toggleSystemBar(menuIsVisible)
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = !menuIsVisible
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = !menuIsVisible
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setRecyclerViewPreloader(maxPreload: Int) {
        if (mRecyclerViewPreloader != null) {
            binding.recyclerView.removeOnScrollListener(mRecyclerViewPreloader!!)
        }
        mRecyclerViewPreloader = RecyclerViewPreloader(
            Glide.with(this), mAdapter, mSizeProvider, maxPreload
        )
        binding.recyclerView.addOnScrollListener(mRecyclerViewPreloader!!)
    }

    private fun setHorizontalScroll(enable: Boolean) {
        mAdapter.isHorizontal = true
        mPagerSnapHelper.attachToRecyclerView(null)
        mLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
    }

    @SuppressLint("StringFormatMatches")
    private fun upMenu(menu: Menu) {
        this.mMenu = menu
        menu.findItem(R.id.menu_pre_manga_number).title =
            getString(R.string.pre_download_m, AppConfig.mangaPreDownloadNum)
    }

    private fun setDisableMangaScale(disable: Boolean) {
        binding.webtoonFrame.disableMangaScale = disable
        binding.recyclerView.disableMangaScale = disable
        if (disable) {
            binding.recyclerView.resetZoom()
        }
    }

    private fun setDisableClickScroll(disable: Boolean) {
        binding.webtoonFrame.disabledClickScroll = disable
    }

    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun scrollToNext() {
        val nextPos = ReadManga.durChapterPos + 1
        if (nextPos < (ReadManga.curMangaChapter?.imageCount ?: 0)) {
            rippleNext()
            ReadManga.durChapterPos = nextPos
            ReadManga.curPageChanged()
            skipToPage(nextPos)
        } else {
            ReadManga.moveToNextChapter()
        }
    }

    private fun scrollToPrev() {
        val prevPos = ReadManga.durChapterPos - 1
        if (prevPos >= 0) {
            ripplePrev()
            ReadManga.durChapterPos = prevPos
            ReadManga.curPageChanged()
            skipToPage(prevPos)
        } else {
            ReadManga.moveToPrevChapter()
        }
    }

    private fun showNumberPickerDialog(
        min: Int,
        title: String,
        initValue: Int,
        callback: (Int) -> Unit,
    ) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMaxValue(9999)
            .setMinValue(min)
            .setValue(initValue)
            .show {
                callback.invoke(it)
            }
    }

    override fun finish() {
        val book = ReadManga.book ?: return super.finish()

        if (ReadManga.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadManga.book?.removeType(BookType.notShelf)
                    ReadManga.book?.save()
                    ReadManga.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    fun updateWindowBrightness(brightness: Int) {
        val layoutParams = window.attributes
        val normalizedBrightness = brightness.toFloat() / 255.0f
        layoutParams.screenBrightness = normalizedBrightness.coerceIn(0f, 1f)
        window.attributes = layoutParams
        window.decorView.postInvalidate()
    }

    override fun skipToPage(index: Int) {
        val durChapterIndex = ReadManga.durChapterIndex
        val itemPos = mAdapter.getItems().fastBinarySearch {
            val chapterIndex: Int
            val pageIndex: Int
            if (it is MangaPage) {
                chapterIndex = it.chapterIndex
                pageIndex = it.index
            } else {
                error("unknown item type")
            }
            val delta = chapterIndex - durChapterIndex
            if (delta != 0) {
                delta
            } else {
                pageIndex - index
            }
        }
        if (itemPos > -1) {
            mLayoutManager.scrollToPositionWithOffset(itemPos, 0)
            upInfoBar(mAdapter.getItem(itemPos))
            ReadManga.durChapterPos = index
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                scrollToPrev()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                scrollToNext()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun updateEepaper(value: Int) {
        mAdapter.updateThreshold(value)
    }
}