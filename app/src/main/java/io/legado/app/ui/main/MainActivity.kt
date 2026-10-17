@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.home.HomeFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.isCreated
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours

/**
 * 主界面
 *
 * 生命周期约定：
 * Activity 上的延时任务一律走 [lifecycleScope] + [delay]，不用 View.postDelayed。
 * 后者不可取消 —— Activity 销毁后消息依旧会从主线程队列里跑出来，
 * 去访问已经不可用的 Fragment / View，是崩溃的温床。
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener,
    MainViewModel.CallBack {

    internal val globalAaFilter = PaintFlagsDrawFilter(
        0,
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    )

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()

    private val idHome = 0
    private val idBookshelf = 1
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 2
    private val idMy = 3

    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition = 0
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 4
    private val EXIT_INTERVAL = 2000L

    private val realPositions = arrayOf(idHome, idBookshelf, idExplore, idMy)

    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var onUpBooksBadgeView: BadgeView? = null

    /** 是否打开过二级页面（用于返回时强制回主页） */
    private var secondaryPageOpened = false

    private val secondaryPageTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // ★ 只统计「本应用内」的二级页面（如 BookInfoActivity / ReadBookActivity）。
            //   忽略系统文件选择器、分享面板、系统设置等外部 Activity，
            //   否则从外部 Activity 返回主界面时会被强制切回首页 tab。
            if (activity !is MainActivity &&
                activity.packageName == applicationContext.packageName
            ) {
                secondaryPageOpened = true
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upHomePage()

        // 滑动禁用只在这里做一次。原来放在 HomeFragment 的 onCreateView/onResume 里，
        // 每次切页都反射一次，HomeFragment 销毁后 ViewPager 也照样被误伤。
        disableViewPagerSwipe()

        // 注册二级页面追踪
        application.registerActivityLifecycleCallbacks(secondaryPageTracker)

        onBackPressedDispatcher.addCallback(this) {
            // ① 若有任何可见的 DialogFragment（含 childFragmentManager 里的，如 FilePickerDialog）→ 交回给 dialog
            if (hasVisibleDialog(supportFragmentManager)) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
                return@addCallback
            }

            // ② 原有逻辑
            val isOnHome = binding.bottomNavigationView.selectedItemId == R.id.menu_home

            if (!isOnHome) {
                switchPageWithoutAnim(realPositions.indexOf(idHome).coerceAtLeast(0))
                exitTime = 0L
                return@addCallback
            }

            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    /**
     * 递归查找 FragmentManager 内是否存在可见的 DialogFragment
     * FilePickerDialog 是通过 HomeFragment.childFragmentManager 提交的，
     * 只查 supportFragmentManager.fragments 找不到它。
     */
    private fun hasVisibleDialog(fm: FragmentManager): Boolean {
        for (f in fm.fragments) {
            if (f is androidx.fragment.app.DialogFragment && f.isVisible) return true
            if (hasVisibleDialog(f.childFragmentManager)) return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        // 从二级页面返回时强制切主页
        if (secondaryPageOpened) {
            secondaryPageOpened = false
            val homeIndex = realPositions.indexOf(idHome).coerceAtLeast(0)
            if (binding.bottomNavigationView.selectedItemId != R.id.menu_home) {
                switchPageWithoutAnim(homeIndex)
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            if (!privacyPolicy()) return@launch
            upVersion()
            setLocalPassword()
            notifyAppCrash()
            backupSync()
            viewModel.setActivityCallback(this@MainActivity)

            // ★ 改用 delay：lifecycleScope 在 onDestroy 时取消，这些延时任务不会残留到销毁之后。
            // 原来的 viewPagerMain.postDelayed 无法取消，Activity 重建时旧消息仍会执行。
            delay(1000)
            viewModel.ruleSubsUp()

            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                delay(2000)
                viewModel.upAllBookToc()
            }

            delay(3000)
            viewModel.postLoad()
        }
    }

    /**
     * 音量键翻页：只在书架页时处理，style1 / style2 都支持
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pagePosition == 1) {
            val shelf = fragmentMap[getFragmentId(1)]
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val up = when (shelf) {
                        is BookshelfFragment2 -> shelf.onVolumeUp()
                        is BookshelfFragment1 -> shelf.onVolumeUp()
                        else -> false
                    }
                    if (up) return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val down = when (shelf) {
                        is BookshelfFragment2 -> shelf.onVolumeDown()
                        is BookshelfFragment1 -> shelf.onVolumeDown()
                        else -> false
                    }
                    if (down) return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchPageWithoutAnim(index: Int) {
        binding.viewPagerMain.post {
            binding.viewPagerMain.setCurrentItem(index, false)
            // 宽度还没量出来的时候 scrollTo(0) 等于什么都没做，去掉这次无意义的重绘
            val w = binding.viewPagerMain.width
            if (w > 0) {
                binding.viewPagerMain.scrollTo(index * w, 0)
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_home -> switchPageWithoutAnim(realPositions.indexOf(idHome))
            R.id.menu_bookshelf -> switchPageWithoutAnim(realPositions.indexOf(idBookshelf))
            R.id.menu_discovery -> switchPageWithoutAnim(realPositions.indexOf(idExplore))
            R.id.menu_my_config -> switchPageWithoutAnim(realPositions.indexOf(idMy))
        }
        return false
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_home -> {}
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    val shelf = fragmentMap[getFragmentId(1)]
                    when (shelf) {
                        is BookshelfFragment2 -> shelf.gotoTop()
                        is BookshelfFragment1 -> shelf.gotoTop()
                        else -> {}
                    }
                }
            }

            R.id.menu_discovery -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[idExplore] as? ExploreFragment)?.compressExplore()
                }
            }
        }
    }

    /**
     * 禁用主 ViewPager 的横向滑动。
     * E-ink 主页用代码创建按钮切页，不需要滑动；触摸交给点击处理。
     */
    @SuppressLint("SoonBlockedPrivateApi")
    private fun disableViewPagerSwipe() {
        try {
            val field = ViewPager::class.java.getDeclaredField("mTouchSlop")
            field.isAccessible = true
            field.setInt(binding.viewPagerMain, Int.MAX_VALUE)
        } catch (_: Throwable) {
        }
    }

    private fun initView() = binding.run {
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = 3
        viewPagerMain.adapter = adapter
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        bottomNavigationView.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_UNLABELED
        val blackColorList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                android.graphics.Color.BLACK,
                android.graphics.Color.BLACK,
                android.graphics.Color.BLACK
            )
        )
        bottomNavigationView.itemIconTintList = blackColorList
        bottomNavigationView.itemTextColor = blackColorList
        if (AppConfig.isEInkMode) {
            bottomNavigationView.background = null
        }
        bottomNavigationView.elevation = 0f
        for (i in 0 until bottomNavigationView.childCount) {
            val child = bottomNavigationView.getChildAt(i)
            if (child is com.google.android.material.bottomnavigation.BottomNavigationMenuView) {
                child.background = null
                child.setPadding(0, 0, 0, 0)
            }
        }
        bottomNavigationView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            view.bottomPadding = height
            windowInsets.inset(0, 0, 0, height)
        }
    }

    private inline fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
        if (isActive) resume(value)
    }

    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resumeOnce(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resumeOnce(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resumeOnce(false)
            }
            // ★ 修复：点外面/返回键关掉对话框时，原来两个分支都不会走到，
            // 协程永久挂起 —— lifecycleScope 里这个 job 再也不会结束。
            onDismiss {
                block.resumeOnce(false)
            }
        }
    }

    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()) {
                    AppUpdate.giteeUpdate.check(lifecycleScope)
                        .onSuccess {
                            showDialogFragment(UpdateDialog(it))
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resumeOnce(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(getString(R.string.help), help, TextDialog.Mode.MD)
            dialog.setOnDismissListener { block.resumeOnce(null) }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener { block.resumeOnce(null) }
            showDialogFragment(dialog)
        } else {
            block.resumeOnce(null)
        }
    }

    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resumeOnce(null)
            return@sc
        }
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView { editTextBinding.root }
            onDismiss { block.resumeOnce(null) }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) return
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton { showDialogFragment<CrashLogsDialog>() }
            noButton()
        }
    }

    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) return
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton { viewModel.restoreWebDav(lastBackupFile.displayName) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 与原实现保持一致：只要开着自动刷新就落盘，重建后跳过 upAllBookToc()。
        // 已知的小瑕疵：如果在隐私弹窗 / 前 2 秒延迟期间发生配置变更，
        // 这次刷新其实还没跑也会被记成"已刷新"。属于原有行为，本次不动。
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(secondaryPageTracker)
        super.onDestroy()
        Coroutine.async { BookHelp.clearInvalidCache() }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun recreate() {
        val shelf = fragmentMap[getFragmentId(1)]
        when (shelf) {
            is BookshelfFragment2 -> shelf.upSort()
            is BookshelfFragment1 -> shelf.upSort()
            else -> {}
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(1)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                if (it) {
                    switchPageWithoutAnim(bottomMenuCount - 1)
                }
            }
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery
        }
        realPositions[0] = idHome
        realPositions[1] = idBookshelf
        var index = 1
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        adapter.notifyDataSetChanged()
    }

    private fun upHomePage() {
        switchPageWithoutAnim(realPositions.indexOf(idHome))
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            pagePosition = position
            val menuId = when (realPositions[position]) {
                idHome -> R.id.menu_home
                idBookshelf -> R.id.menu_bookshelf
                idExplore -> R.id.menu_discovery
                idMy -> R.id.menu_my_config
                else -> return
            }
            binding.bottomNavigationView.menu.findItem(menuId)?.isChecked = true
        }
    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idHome && any is HomeFragment)
                || (fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idExplore && any is ExploreFragment)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idHome -> HomeFragment(position)
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idMy -> MyFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }
    }

    override fun openImportUi(type: Int, source: String) {
        when (type) {
            0 -> showDialogFragment(ImportBookSourceDialog(source))
            1 -> showDialogFragment(ImportRssSourceDialog(source))
            2 -> showDialogFragment(ImportReplaceRuleDialog(source))
        }
    }
}