package io.legado.app.ui.launcher

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.ui.file.FilePickerDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import java.io.File
import java.util.*
import kotlin.math.abs

/**
 * 阅读桌面 Launcher
 * 纯代码绘制，无 XML
 * 墨水屏风格：白底黑字
 * 图标从下往上排列，支持点击选中跨页移动
 * 全部编译错误修复完毕，拖拽分页逻辑稳定
 */
class LauncherActivity : AppCompatActivity(), FilePickerDialog.CallBack {

    private val appList = mutableListOf<AppInfo>()
    private val hiddenList = mutableSetOf<String>()
    private val renameMap = mutableMapOf<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val colCount = 4
    private val rowCount = 4
    private val pageSize = colCount * rowCount
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvPageIndicator: TextView
    private lateinit var gridContainer: LinearLayout
    private var settingsPanel: View? = null
    private var wallpaperPath: String? = null
    private var wallpaperBgViewId: Int = -1
    private var currentPage = 0
    private val prefs by lazy { getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }
    private var iconSizeDp: Int = 48
    private var textSizeSp: Float = 12f
    private var clockSizeSp: Float = 85f
    private var strokeWidthDp: Int = 1
    private var cornerRadiusDp: Int = 4

    // 每页位置映射: 页码 -> (格子索引0-15 -> 应用包名)
    private val pagePositionMap = mutableMapOf<Int, MutableMap<Int, String>>()

    private val timeRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // 翻页刷新锁，防止快速点击放错页面
    private var isRefreshing = false

    // 手势翻页

    // 标记当前是选择壁纸还是图标
    private var isPickingWallpaper = false
    private var pendingIconKey: String? = null


    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED && replacing) return
                    handler.postDelayed({
                        loadApps()
                        refreshGrid()
                        cleanupEmptyPages()
                    }, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        setContentView(createRootLayout())
        loadApps()
        refreshGrid()
        updateClock()
        handler.post(timeRunnable)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    // ========== 左右滑动翻页 ==========
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = it.x
                    touchStartY = it.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = it.x - touchStartX
                    val dy = it.y - touchStartY
                    if (kotlin.math.abs(dx) > swipeThreshold && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        if (dx < 0) {
                            goToNextPage()
                        } else {
                            goToPrevPage()
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // ========== FilePickerDialog 回调 ==========
    override fun onResult(data: Intent) {
        val uri = data.data ?: return

        if (isPickingWallpaper) {
            isPickingWallpaper = false
            try {
                if (uri.isContentScheme()) {
                    readUri(uri) { _, inputStream ->
                        val destFile = File(filesDir, "wallpaper_${System.currentTimeMillis()}.jpg")
                        destFile.outputStream().use { output ->
                            inputStream.copyTo(output)
                        }
                        wallpaperPath = destFile.absolutePath
                        saveSettings()
                        recreate()
                    }
                } else {
                    wallpaperPath = uri.path
                    saveSettings()
                    recreate()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "壁纸设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else if (pendingIconKey != null) {
            try {
                val key = pendingIconKey ?: return
                val destFile = File(filesDir, "icon_${key.replace("|", "_")}_${System.currentTimeMillis()}.png")
                if (uri.isContentScheme()) {
                    readUri(uri) { _, inputStream ->
                        destFile.outputStream().use { output ->
                            inputStream.copyTo(output)
                        }
                    }
                } else {
                    uri.path?.let { path ->
                        File(path).inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                customIconMap[key] = destFile.absolutePath
                saveCustomIcons()
                val idx = appList.indexOfFirst { it.uniqueKey == key }
                if (idx >= 0) {
                    val customIcon = loadCustomIconDrawable(key)
                    if (customIcon != null) {
                        appList[idx] = appList[idx].copy(icon = customIcon)
                    }
                }
                refreshGrid()
                Toast.makeText(this, "图标已更换", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "图标更换失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pendingIconKey = null
            }
        }
    }

    private fun loadSettings() {
        iconSizeDp = prefs.getInt("icon_size", 48)
        textSizeSp = prefs.getFloat("text_size", 12f)
        clockSizeSp = prefs.getFloat("clock_size", 85f)
        strokeWidthDp = prefs.getInt("stroke_width", 1)
        cornerRadiusDp = prefs.getInt("corner_radius", 4)
        wallpaperPath = prefs.getString("wallpaper_path", null)
        hiddenList.clear()
        val rawHidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        hiddenList.addAll(rawHidden.filter { "|" in it })
        renameMap.clear()
        val renameStr = prefs.getString("rename_map", "") ?: ""
        renameStr.split(";").forEach { pair ->
            val parts = pair.split("=")
            if (parts.size == 2) renameMap[parts[0]] = parts[1]
        }
        loadCustomIcons()
        loadPositions()
    }

    private fun loadPositions() {
        pagePositionMap.clear()
        val posStr = prefs.getString("app_positions_v2", "") ?: ""
        if (posStr.isNotEmpty()) {
            posStr.split("\u00B6").forEach { pageEntry ->
                val parts = pageEntry.split(":")
                if (parts.size == 2) {
                    val page = parts[0].toInt()
                    val slots = mutableMapOf<Int, String>()
                    parts[1].split(";").forEach { slotEntry ->
                        val slotParts = slotEntry.split("=")
                        if (slotParts.size == 2) {
                            slots[slotParts[0].toInt()] = slotParts[1]
                        }
                    }
                    pagePositionMap[page] = slots
                }
            }
        }
    }

    private fun savePositions() {
        val posStr = pagePositionMap.entries.joinToString("\u00B6") { pageEntry ->
            "${pageEntry.key}:" + pageEntry.value.entries.joinToString(";") { slotEntry ->
                "${slotEntry.key}=${slotEntry.value}"
            }
        }
        prefs.edit().putString("app_positions_v2", posStr).commit()
    }

    private fun getCurrentPageMap(): MutableMap<Int, String> {
        return pagePositionMap.getOrPut(currentPage) { mutableMapOf() }
    }

    private fun saveSettings() {
        val renameStr = renameMap.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit()
            .putInt("icon_size", iconSizeDp)
            .putFloat("text_size", textSizeSp)
            .putFloat("clock_size", clockSizeSp)
            .putInt("stroke_width", strokeWidthDp)
            .putInt("corner_radius", cornerRadiusDp)
            .putString("wallpaper_path", wallpaperPath)
            .putStringSet("hidden_apps", hiddenList)
            .putString("rename_map", renameStr)
            .apply()
    }

    // ========== 根布局 ==========
    private fun createRootLayout(): View {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // 壁纸背景
        val bgView = View(this).apply {
            id = View.generateViewId()
            wallpaperBgViewId = id
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (wallpaperPath != null) {
                try {
                    val bitmap = BitmapFactory.decodeFile(wallpaperPath)
                    if (bitmap != null) {
                        background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                    } else {
                        setBackgroundColor(Color.WHITE)
                    }
                } catch (e: Exception) {
                    setBackgroundColor(Color.WHITE)
                }
            } else {
                setBackgroundColor(Color.WHITE)
            }
        }
        root.addView(bgView)

        // 主内容
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 时钟日历
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 20.dpToPx(), 0, 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvClock = TextView(this).apply {
            textSize = clockSizeSp
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        header.addView(tvClock)
        tvDate = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 4.dpToPx(), 0, 0)
        }
        header.addView(tvDate)
        content.addView(header)

        // 应用网格（从下往上排列）
        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        }
        content.addView(gridContainer)

        // 底部导航栏
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dpToPx(), 16.dpToPx(), 18.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnPrev = createNavButton(getDrawable(R.drawable.ic_12)!!) {
            goToPrevPage()
        }
        navBar.addView(btnPrev, LinearLayout.LayoutParams(0, 56.dpToPx(), 1f))

        tvPageIndicator = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            setOnClickListener { showPagePicker() }
        }
        navBar.addView(tvPageIndicator, LinearLayout.LayoutParams(0, 56.dpToPx(), 1f))

        val btnNext = createNavButton(getDrawable(R.drawable.ic_13)!!) {
            goToNextPage()
        }
        navBar.addView(btnNext, LinearLayout.LayoutParams(0, 56.dpToPx(), 1f))

        val btnReader = createNavButton(getDrawable(R.drawable.ic_9)!!) {
            startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
        }
        navBar.addView(btnReader, LinearLayout.LayoutParams(0, 56.dpToPx(), 1.5f))

        val btnEdit = createNavButton(getDrawable(R.drawable.ic_11)!!) {
            if (isEditMode) {
                cancelEdit()
            } else {
                startEditMode()
            }
        }
        navBar.addView(btnEdit, LinearLayout.LayoutParams(0, 56.dpToPx(), 1f))

        val btnSettings = createNavButton(getDrawable(R.drawable.ic_10)!!) {
            toggleSettingsPanel()
        }
        navBar.addView(btnSettings, LinearLayout.LayoutParams(0, 56.dpToPx(), 1f))

        content.addView(navBar)
        root.addView(content)

        // 根布局空白点击退出编辑
        root.setOnClickListener {
            if (isEditMode) {
                cancelEdit()
            }
        }

        return root
    }


    // ========== 翻页方法 ==========
    private fun goToPrevPage() {
        if (isRefreshing || currentPage <= 0) return
        isRefreshing = true
        currentPage--
        refreshGrid()
        handler.postDelayed({ isRefreshing = false }, 100)
    }

    private fun goToNextPage() {
        if (isRefreshing) return
        isRefreshing = true
        val visibleCount = appList.filter { it.uniqueKey !in hiddenList }.size
        val appCountPages = maxOf(1, (visibleCount + pageSize - 1) / pageSize)
        val positionMapMaxPage = if (pagePositionMap.isEmpty()) 0 else pagePositionMap.keys.max() + 1
        val totalPages = maxOf(appCountPages, positionMapMaxPage)
        val maxPage = if (isEditMode) {
            if (currentPage + 1 >= totalPages) {
                pagePositionMap.getOrPut(currentPage) { mutableMapOf() }
                savePositions()
            }
            totalPages
        } else {
            positionMapMaxPage - 1
        }
        if (currentPage < maxPage) {
            currentPage++
            refreshGrid()
        }
        handler.postDelayed({ isRefreshing = false }, 100)
    }

    // ========== 刷新网格【全局清理所有页面失效应用】 ==========
    private fun refreshGrid() {
        try {
            gridContainer.removeAllViews()
            val visibleApps = appList.filter { it.uniqueKey !in hiddenList }
            val visibleKeySet = visibleApps.map { it.uniqueKey }.toSet()

            val toRemoveAll = mutableListOf<Pair<Int, Int>>()
            pagePositionMap.forEach { (pageNum, slots) ->
                slots.forEach { (idx, key) ->
                    if (key !in visibleKeySet) {
                        toRemoveAll.add(pageNum to idx)
                    }
                }
            }
            toRemoveAll.forEach { (p, idx) ->
                pagePositionMap[p]?.remove(idx)
            }
            if (toRemoveAll.isNotEmpty()) savePositions()

            val storedPages = pagePositionMap.keys.sorted()
            val totalRealPages = maxOf(1, storedPages.size)
            updatePageIndicator(currentPage + 1, totalRealPages)
            val currentMap = getCurrentPageMap()

            val screenWidth = resources.displayMetrics.widthPixels
            val itemWidth = (screenWidth - 24.dpToPx()) / colCount
            val iconPx = iconSizeDp.dpToPx()
            val pad = 6.dpToPx()

            for (rowIdx in (rowCount - 1) downTo 0) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0, 1f
                    )
                }
                for (colIdx in 0 until colCount) {
                    val index = rowIdx * colCount + colIdx
                    val appKey = currentMap[index]
                    val app = visibleApps.find { it.uniqueKey == appKey }
                    if (app != null) {
                        val displayName = renameMap[app.uniqueKey] ?: app.label
                        val item = createApp(app, displayName, itemWidth, iconPx, index)
                        if (isEditMode && draggedApp?.uniqueKey == app.uniqueKey) {
                            draggedView = item
                            item.alpha = 0.5f
                        }
                        row.addView(item)
                    } else {
                        row.addView(createEmptySlot(itemWidth, index))
                    }
                }
                gridContainer.addView(row)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createEmptySlot(itemWidth: Int, index: Int): LinearLayout {
        val pad = 6.dpToPx()
        val iconPx = iconSizeDp.dpToPx()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            tag = "empty_$index"
            isClickable = true
            if (isEditMode) {
                addView(View(this@LauncherActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(iconPx + 8.dpToPx(), iconPx + 8.dpToPx()).apply {
                        gravity = Gravity.CENTER
                    }
                    background = createStrokeDrawable(cornerRadiusDp.dpToPx().toFloat())
                })
            }
            setOnClickListener {
                if (isEditMode && draggedApp != null) {
                    val currentMap = getCurrentPageMap()
                    pagePositionMap.getOrPut(currentPage) { mutableMapOf() }
                    val dragKey = draggedApp!!.uniqueKey
                    val removeList = mutableListOf<Pair<Int, Int>>()
                    pagePositionMap.forEach { (p, slots) ->
                        slots.forEach { (idx, key) ->
                            if (key == dragKey) removeList.add(p to idx)
                        }
                    }
                    removeList.forEach { (p, idx) ->
                        pagePositionMap[p]?.remove(idx)
                    }
                    currentMap[index] = dragKey
                    savePositions()
                    refreshGrid()
                    draggedView?.alpha = 1.0f
                    draggedView = null
                    draggedApp = null
                }
            }
        }
    }

    private fun createApp(app: AppInfo, displayName: String, itemWidth: Int, iconPx: Int, index: Int): LinearLayout {
        val pad = 6.dpToPx()
        val itemView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            tag = "app_$index"
            setOnClickListener {
                try {
                    if (!isEditMode) {
                        launchApp(app)
                    } else {
                        val targetKey = app.uniqueKey
                        if (draggedApp == null) {
                            draggedApp = app
                            draggedView = this
                            this.alpha = 0.5f
                        } else {
                            val dragKey = draggedApp!!.uniqueKey
                            if (dragKey != targetKey) {
                                var dragPage = -1
                                var dragIdx = -1
                                outerDrag@for ((p, slots) in pagePositionMap) {
                                    for ((idx, slotKey) in slots) {
                                        if (slotKey == dragKey) {
                                            dragPage = p
                                            dragIdx = idx
                                            break@outerDrag
                                        }
                                    }
                                }
                                var targetPage = -1
                                var targetIdx = -1
                                outerTarget@for ((p, slots) in pagePositionMap) {
                                    for ((idx, slotKey) in slots) {
                                        if (slotKey == targetKey) {
                                            targetPage = p
                                            targetIdx = idx
                                            break@outerTarget
                                        }
                                    }
                                }
                                if (dragPage != -1) pagePositionMap[dragPage]?.remove(dragIdx)
                                if (targetPage != -1) pagePositionMap[targetPage]?.remove(targetIdx)
                                if (dragPage != -1) pagePositionMap[dragPage]?.put(dragIdx, targetKey)
                                getCurrentPageMap()[index] = dragKey
                                savePositions()
                                refreshGrid()
                                draggedView?.alpha = 1.0f
                                draggedView = null
                                draggedApp = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val iconContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconPx + 8.dpToPx(), iconPx + 8.dpToPx()).apply {
                gravity = Gravity.CENTER
            }
            background = createStrokeDrawable(cornerRadiusDp.dpToPx().toFloat())
        }
        iconContainer.addView(ImageView(this).apply {
            setImageDrawable(app.icon)
            layoutParams = FrameLayout.LayoutParams(iconPx, iconPx).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        itemView.addView(iconContainer)
        itemView.addView(TextView(this).apply {
            text = displayName
            textSize = textSizeSp
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
        })
        itemView.setOnLongClickListener {
            if (isEditMode) {
                cancelEdit()
            } else {
                showAppMenu(app, itemView)
            }
            true
        }
        return itemView
    }

    private fun startEditMode() {
        if (isEditMode) return
        isEditMode = true
        refreshGrid()
    }

    private fun cancelEdit() {
        if (!isEditMode) return
        isEditMode = false
        try {
            draggedView?.alpha = 1.0f
        } catch (_: Exception) {}
        draggedView = null
        draggedApp = null
        cleanupEmptyPages()
        savePositions()
        refreshGrid()
    }

    private fun showPagePicker() {
        val visibleCount = appList.filter { it.uniqueKey !in hiddenList }.size
        val appCountPages = maxOf(1, (visibleCount + pageSize - 1) / pageSize)
        val positionMapMaxPage = if (pagePositionMap.isEmpty()) 0 else pagePositionMap.keys.max() + 1
        val totalPages = maxOf(appCountPages, positionMapMaxPage)
        if (totalPages <= 1) return
        val items = (0 until totalPages).map { "第 ${it + 1} 页" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择页面")
            .setItems(items) { _, which ->
                currentPage = which
                refreshGrid()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把未在 pagePositionMap 中的可见应用填入空白位置 */
    private fun fillEmptySlots() {
        val visibleApps = appList.filter { it.uniqueKey !in hiddenList }
        val allPlacedKeys = pagePositionMap.values.flatMap { it.values }.toSet()
        val unplacedApps = visibleApps.filter { it.uniqueKey !in allPlacedKeys }.toMutableList()
        if (unplacedApps.isEmpty()) return
        val storedPages = pagePositionMap.keys.sorted()
        var filled = false
        for (pageNum in 0..storedPages.size) {
            val map = pagePositionMap.getOrPut(pageNum) { mutableMapOf() }
            for (idx in 0 until pageSize) {
                if (map[idx] == null && unplacedApps.isNotEmpty()) {
                    map[idx] = unplacedApps.removeAt(0).uniqueKey
                    filled = true
                }
            }
            if (unplacedApps.isEmpty()) break
        }
        if (filled) savePositions()
    }

    /** 只删除完全空白页面，不重排页码 */
    private fun cleanupEmptyPages() {
        val visibleApps = appList.filter { it.uniqueKey !in hiddenList }
        val visibleKeys = visibleApps.map { it.uniqueKey }.toSet()
        val emptyPages = pagePositionMap.entries.filter { (_, slots) ->
            slots.values.all { it !in visibleKeys }
        }.map { it.key }
        emptyPages.forEach { pagePositionMap.remove(it) }
        savePositions()
        val maxValidPage = if (pagePositionMap.isEmpty()) 0 else pagePositionMap.keys.max()
        if (currentPage > maxValidPage) currentPage = maxValidPage
    }

    /** 重置布局：恢复原生顺序 */
    private fun resetPositions() {
        pagePositionMap.clear()
        val visibleApps = appList.filter { it.uniqueKey !in hiddenList }
        visibleApps.forEachIndexed { idx, app ->
            val page = idx / pageSize
            val slot = idx % pageSize
            pagePositionMap.getOrPut(page) { mutableMapOf() }[slot] = app.uniqueKey
        }
        savePositions()
        cleanupEmptyPages()
        fillEmptySlots()
    }

    // ========== 设置面板 ==========
    private fun toggleSettingsPanel() {
        if (settingsPanel != null) {
            (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
            settingsPanel = null
            return
        }
        val root = findViewById<ViewGroup>(android.R.id.content)
        val panel = createSettingsPanel()
        settingsPanel = panel
        root.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun createSettingsPanel(): View {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            gravity = Gravity.CENTER
            setOnClickListener {
                (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
                settingsPanel = null
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24.dpToPx(), 20.dpToPx(), 24.dpToPx(), 20.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }
        card.addView(TextView(this).apply {
            text = "桌面设置"
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dpToPx() }
        })
        val iconSeek = createSeekBarRow("图标大小", iconSizeDp, 32, 80)
        card.addView(iconSeek.view)
        val textSeek = createSeekBarRow("文字大小", textSizeSp.toInt(), 8, 20)
        card.addView(textSeek.view)
        val clockSeek = createSeekBarRow("时钟大小", clockSizeSp.toInt(), 40, 120)
        card.addView(clockSeek.view)
        val strokeSeek = createSeekBarRow("描边粗细", strokeWidthDp, 0, 8)
        card.addView(strokeSeek.view)
        val cornerSeek = createSeekBarRow("描边圆角", cornerRadiusDp, 0, 20)
        card.addView(cornerSeek.view)
        card.addView(createWallpaperRow())
        card.addView(createHiddenAppsEntry())
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20.dpToPx() }
        }
        val btnLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val gapLp = LinearLayout.LayoutParams(8.dpToPx(), 1)
        btnRow.addView(TextView(this).apply {
            text = "保存配置"
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            background = createStrokeDrawable(4.dpToPx().toFloat())
            setOnClickListener {
                iconSizeDp = iconSeek.getValue()
                textSizeSp = textSeek.getValue().toFloat()
                clockSizeSp = clockSeek.getValue().toFloat()
                strokeWidthDp = strokeSeek.getValue()
                cornerRadiusDp = cornerSeek.getValue()
                saveSettings()
                (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
                settingsPanel = null
                recreate()
            }
        }, btnLp)
        btnRow.addView(View(this), gapLp)
        btnRow.addView(TextView(this).apply {
            text = "布局重置"
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            background = createStrokeDrawable(4.dpToPx().toFloat())
            setOnClickListener {
                resetPositions()
                (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
                settingsPanel = null
                recreate()
            }
        }, btnLp)
        btnRow.addView(View(this), gapLp)
        btnRow.addView(TextView(this).apply {
            text = "清理壁纸"
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            background = createStrokeDrawable(4.dpToPx().toFloat())
            setOnClickListener {
                clearWallpaperBg()
            }
        }, btnLp)
        card.addView(btnRow)
        overlay.addView(card)
        return overlay
    }

    private fun createHiddenAppsEntry(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dpToPx(); bottomMargin = 12.dpToPx() }
        }
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply { bottomMargin = 12.dpToPx() }
            setBackgroundColor(Color.BLACK)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            setOnClickListener { showHiddenAppsDialog() }
        }
        row.addView(TextView(this).apply {
            text = "隐藏列表"
            textSize = 14f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(row)
        return container
    }

    private fun showHiddenAppsDialog() {
        if (hiddenList.isEmpty()) {
            Toast.makeText(this, "没有已隐藏的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val hiddenApps = appList.filter { it.uniqueKey in hiddenList }
        val names = hiddenApps.map { renameMap[it.uniqueKey] ?: it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("已隐藏应用（点击解除隐藏）")
            .setItems(names) { _, which ->
                val app = hiddenApps[which]
                hiddenList.remove(app.uniqueKey)
                saveSettings()
                fillEmptySlots()
                refreshGrid()
                Toast.makeText(this, "已显示 ${renameMap[app.uniqueKey] ?: app.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun createStrokeDrawable(cornerRadius: Float = cornerRadiusDp.dpToPx().toFloat()): Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setStroke(strokeWidthDp.dpToPx(), Color.BLACK)
            setColor(Color.TRANSPARENT)
            this.cornerRadius = cornerRadius
        }
    }

    data class SeekBarRow(val view: View, private val seekBar: SeekBar, private val min: Int) {
        fun getValue() = seekBar.progress + min
    }

    private fun createSeekBarRow(label: String, current: Int, min: Int, max: Int): SeekBarRow {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dpToPx() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvValue = TextView(this).apply {
            text = current.toString()
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.END
        }
        row.addView(tvLabel)
        row.addView(tvValue)
        container.addView(row)
        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvValue.text = (progress + min).toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(seekBar)
        return SeekBarRow(container, seekBar, min)
    }

    private fun createWallpaperRow(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dpToPx() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { pickWallpaperFile() }
        }
        val tvLabel = TextView(this).apply {
            text = "选择壁纸"
            textSize = 14f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvStatus = TextView(this).apply {
            text = if (wallpaperPath != null) File(wallpaperPath).name else "未设置"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.END
        }
        row.addView(tvLabel)
        row.addView(tvStatus)
        container.addView(row)
        return container
    }

    /** 清理壁纸：删除文件、清空路径、持久化，并实时恢复白色背景（注意不能与 ContextWrapper.clearWallpaper 同名） */
    private fun clearWallpaperBg() {
        if (wallpaperPath == null) {
            Toast.makeText(this, "未设置壁纸", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            wallpaperPath?.let { File(it).delete() }
        } catch (_: Exception) {
        }
        wallpaperPath = null
        saveSettings()
        if (wallpaperBgViewId != -1) {
            findViewById<View>(wallpaperBgViewId)?.setBackgroundColor(Color.WHITE)
        }
        (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
        settingsPanel = null
        Toast.makeText(this, "壁纸已清理", Toast.LENGTH_SHORT).show()
    }

    // ========== 直接调用项目自己的 FilePickerDialog ==========
    private fun pickWallpaperFile() {
        isPickingWallpaper = true
        pendingIconKey = null
        FilePickerDialog.show(
            supportFragmentManager,
            mode = HandleFileContract.FILE,
            allowExtensions = arrayOf("jpg", "jpeg", "png", "webp", "gif")
        )
    }

    private fun pickCustomIcon(app: AppInfo) {
        isPickingWallpaper = false
        pendingIconKey = app.uniqueKey
        FilePickerDialog.show(
            supportFragmentManager,
            mode = HandleFileContract.FILE,
            allowExtensions = arrayOf("png", "jpg", "jpeg", "webp")
        )
    }

    // ========== 应用长按菜单 ==========
    private val customIconMap = mutableMapOf<String, String>()

    private fun loadCustomIcons() {
        customIconMap.clear()
        val iconStr = prefs.getString("custom_icons", "") ?: ""
        iconStr.split(";").forEach { pair ->
            val parts = pair.split("=")
            if (parts.size == 2) customIconMap[parts[0]] = parts[1]
        }
    }

    private fun saveCustomIcons() {
        val iconStr = customIconMap.entries.joinToString(";") { entry ->
            "${entry.key}=${entry.value}"
        }
        prefs.edit().putString("custom_icons", iconStr).apply()
    }

    private fun loadCustomIconDrawable(key: String): Drawable? {
        val path = customIconMap[key] ?: return null
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                android.graphics.drawable.BitmapDrawable(resources, bitmap)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 恢复应用默认图标
     */
    private fun restoreDefaultIcon(app: AppInfo) {
        val key = app.uniqueKey
        // 删除自定义图标文件
        customIconMap[key]?.let { path ->
            try {
                File(path).delete()
            } catch (_: Exception) {}
        }
        // 从映射中移除
        customIconMap.remove(key)
        saveCustomIcons()
        // 重新加载该应用的默认图标
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = app.packageName
            }
            val resolveList = pm.queryIntentActivities(intent, 0)
            val defaultIcon = resolveList.find {
                it.activityInfo.name == app.activityName
            }?.loadIcon(pm)
            val idx = appList.indexOfFirst { it.uniqueKey == key }
            if (idx >= 0 && defaultIcon != null) {
                appList[idx] = appList[idx].copy(icon = defaultIcon)
            }
        } catch (_: Exception) {}
        refreshGrid()
        Toast.makeText(this, "已恢复默认图标", Toast.LENGTH_SHORT).show()
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val isSystemApp = try {
            val appInfo = packageManager.getApplicationInfo(app.packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    || (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }

        // 判断是否自定义了图标
        val hasCustomIcon = app.uniqueKey in customIconMap

        // 构建菜单项
        val menuItems = mutableListOf<String>()
        if (hasCustomIcon) {
            menuItems.add("恢复图标")
        }
        menuItems.add("更换图标")
        menuItems.add("更改名称")
        if (!isSystemApp) {
            menuItems.add("卸载应用")
        }
        menuItems.add(if (app.uniqueKey in hiddenList) "显示应用" else "隐藏应用")

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(menuItems.toTypedArray()) { _, which ->
                var index = 0
                when {
                    // 恢复图标（如果有）/ 更换图标
                    which == index++ -> {
                        if (hasCustomIcon) {
                            restoreDefaultIcon(app)
                        } else {
                            pickCustomIcon(app)
                        }
                    }
                    // 更换图标
                    hasCustomIcon && which == index++ -> pickCustomIcon(app)
                    // 更改名称
                    which == index++ -> renameApp(app)
                    // 卸载应用（非系统应用）
                    !isSystemApp && which == index++ -> uninstallApp(app)
                    // 显示/隐藏应用
                    else -> toggleHideApp(app)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleHideApp(app: AppInfo) {
        val key = app.uniqueKey
        if (key in hiddenList) {
            hiddenList.remove(key)
        } else {
            hiddenList.add(key)
            pagePositionMap.forEach { (_, slots) ->
                val removeKeys = slots.filter { it.value == key }.keys
                removeKeys.forEach { slots.remove(it) }
            }
        }
        saveSettings()
        savePositions()
        fillEmptySlots()
        refreshGrid()
    }

    private fun uninstallApp(app: AppInfo) {
        val pkgUri = Uri.parse("package:${app.packageName}")
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkgUri).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (uninstallIntent.resolveActivity(packageManager) != null) {
            startActivity(uninstallIntent)
            return
        }
        val deleteIntent = Intent(Intent.ACTION_DELETE, pkgUri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (deleteIntent.resolveActivity(packageManager) != null) {
            startActivity(deleteIntent)
            return
        }
        Toast.makeText(this, "当前设备无法唤起卸载弹窗，请前往系统设置卸载", Toast.LENGTH_LONG).show()
    }

    private fun renameApp(app: AppInfo) {
        val edit = EditText(this).apply {
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            setText(renameMap[app.uniqueKey] ?: app.label)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renameMap[app.uniqueKey] = newName
                    saveSettings()
                    refreshGrid()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== SVG 绘制 ==========
    private fun createArrowDrawable(left: Boolean): Drawable {
        return createVectorDrawable(18.dpToPx(), 18.dpToPx()) { canvas, paint ->
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            val path = Path()
            if (left) {
                path.moveTo(w * 0.65f, h * 0.25f)
                path.lineTo(w * 0.35f, h * 0.5f)
                path.lineTo(w * 0.65f, h * 0.75f)
            } else {
                path.moveTo(w * 0.35f, h * 0.25f)
                path.lineTo(w * 0.65f, h * 0.5f)
                path.lineTo(w * 0.35f, h * 0.75f)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun createVectorDrawable(w: Int, h: Int, draw: (Canvas, Paint) -> Unit): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) { draw(canvas, paint) }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: ColorFilter?) {}
            override fun getOpacity() = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = w
            override fun getIntrinsicHeight() = h
        }
    }

    // ========== 布局辅助 ==========
    private fun createNavButton(drawable: Drawable, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(drawable)
                setColorFilter(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx())
            })
        }
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        tvClock.text = String.format("%02d:%02d", hour, minute)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val weekDay = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "周日"
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> ""
        }
        tvDate.text = "${year}年${month}月${day}日 $weekDay"
    }

    private fun getAppKey(pkg: String, act: String) = "$pkg|$act"

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(intent, 0)
        appList.clear()
        for (info in resolveList) {
            val pkgName = info.activityInfo.packageName
            if (pkgName == packageName) continue
            try {
                val label = info.loadLabel(pm).toString()
                val activityName = info.activityInfo.name
                val key = getAppKey(pkgName, activityName)
                val icon = loadCustomIconDrawable(key) ?: info.loadIcon(pm)
                appList.add(AppInfo(label, pkgName, activityName, icon))
            } catch (e: Exception) {}
        }
        appList.sortBy { it.label }
        fillEmptySlots()
    }

    private fun launchApp(app: AppInfo) {
        val intent = Intent().apply {
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun updatePageIndicator(current: Int, total: Int) {
        tvPageIndicator.text = "$current / $total"
    }

    override fun onBackPressed() {
        if (isEditMode) {
            cancelEdit()
            return
        }
        if (settingsPanel != null) {
            (settingsPanel?.parent as? ViewGroup)?.removeView(settingsPanel)
            settingsPanel = null
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeRunnable)
        try { draggedView?.alpha = 1.0f } catch (_: Exception) {}
        draggedView = null
        draggedApp = null
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
    }

    data class AppInfo(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable
    ) {
        val uniqueKey: String by lazy { "$packageName|$activityName" }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // 编辑模式相关
    private var isEditMode = false
    private var draggedView: View? = null
    private var draggedApp: AppInfo? = null
}
