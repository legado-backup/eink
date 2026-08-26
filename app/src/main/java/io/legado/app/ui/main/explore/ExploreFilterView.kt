package io.legado.app.ui.main.explore

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setSelectionSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Renders ExploreKind as fixed-label, horizontally scrollable filter rows. */
class ExploreFilterView(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val scope: CoroutineScope,
    private val callBack: CallBack
) {

    private data class Category(val header: ExploreKind, val items: List<ExploreKind>)
    private data class Channel(val header: ExploreKind?, val categories: List<Category>)
    private data class Model(
        val shortcuts: List<ExploreKind>,
        val looseItems: List<ExploreKind>,
        val channels: List<Channel>
    )

    private data class Selection(
        var channelIndex: Int = 0,
        var categoryIndex: Int = 0,
        var selectedKey: String? = null
    )

    interface CallBack {
        fun openExplore(title: String, url: String)
        fun refreshExplore()
        fun showSourceMenu()
    }

    private val selections = hashMapOf<String, Selection>()
    private var source: BookSource? = null
    private var infoMap: InfoMap? = null
    private var model = Model(emptyList(), emptyList(), emptyList())
    private var renderVersion = 0
    private val scrollPositions = hashMapOf<String, List<Int>>()
    private var restoreScrollPositions: List<Int> = emptyList()
    private var renderRowIndex = 0
    private var resetScrollOnNextRender = false

    fun saveState() {
        val map = infoMap ?: return
        if (map.needSave) scope.launch(IO) { map.saveNow() }
    }

    fun setData(source: BookSource, kinds: List<ExploreKind>) {
        this.source = source
        infoMap = ExploreAdapter.exploreInfoMapList[source.bookSourceUrl]
            ?: InfoMap(source.bookSourceUrl).also {
                ExploreAdapter.exploreInfoMapList.put(source.bookSourceUrl, it)
            }
        model = parse(kinds)
        val selection = selections.getOrPut(source.bookSourceUrl) {
            loadSelection(source.bookSourceUrl)
        }
        normalize(selection)
        val validKeys = buildList {
            addAll(model.shortcuts.map(::keyOf))
            addAll(model.looseItems.map(::keyOf))
            addAll(currentItems(selection).map(::keyOf))
        }
        if (selection.selectedKey !in validKeys) selection.selectedKey = null
        render(selection)
        findSelectedOpenable(selection)?.let { selectAndOpen(it, selection) }
    }

    fun clear() {
        renderVersion++
        source = null
        infoMap = null
        model = Model(emptyList(), emptyList(), emptyList())
        container.removeAllViews()
    }

    private fun parse(kinds: List<ExploreKind>): Model {
        val shortcuts = arrayListOf<ExploreKind>()
        val loose = arrayListOf<ExploreKind>()
        val channels = arrayListOf<Channel>()
        var currentChannelHeader: ExploreKind? = null
        var currentCategories = arrayListOf<Category>()
        var index = 0

        fun commitChannel() {
            if (currentCategories.isNotEmpty()) {
                channels.add(Channel(currentChannelHeader, currentCategories.toList()))
            }
            currentCategories = arrayListOf()
        }

        while (index < kinds.size) {
            val kind = kinds[index]
            if (isFullWidth(kind) && !kind.url.isNullOrBlank()) {
                shortcuts.add(kind)
                index++
                continue
            }
            if (isGroupHeader(kind)) {
                val next = kinds.getOrNull(index + 1)
                if (next != null && isGroupHeader(next)) {
                    commitChannel()
                    currentChannelHeader = kind
                    index++
                    continue
                }
                val items = arrayListOf<ExploreKind>()
                index++
                while (index < kinds.size && !isFullWidth(kinds[index])) {
                    items.add(kinds[index])
                    index++
                }
                currentCategories.add(Category(kind, items))
                continue
            }
            loose.add(kind)
            index++
        }
        commitChannel()
        return Model(shortcuts, loose, channels)
    }

    private fun isFullWidth(kind: ExploreKind): Boolean =
        kind.style().layout_flexBasisPercent >= 0.95f

    private fun isGroupHeader(kind: ExploreKind): Boolean =
        kind.type == ExploreKind.Type.url && kind.url.isNullOrBlank() && isFullWidth(kind)

    private fun normalize(selection: Selection) {
        selection.channelIndex = selection.channelIndex.coerceIn(0, (model.channels.size - 1).coerceAtLeast(0))
        val categories = model.channels.getOrNull(selection.channelIndex)?.categories.orEmpty()
        selection.categoryIndex = selection.categoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
    }

    private fun render(selection: Selection) {
        if (resetScrollOnNextRender) {
            resetScrollOnNextRender = false
        } else {
            saveScrollPositions()
        }
        restoreScrollPositions = source?.bookSourceUrl?.let { scrollPositions[it] }.orEmpty()
        renderRowIndex = 0
        renderVersion++
        container.removeAllViews()
        if (model.shortcuts.isNotEmpty()) {
            addRow(activity.getString(R.string.discovery_shortcut), model.shortcuts, selection)
        }
        if (model.channels.size > 1) {
            addRow(
                activity.getString(R.string.discovery_channel),
                model.channels.mapNotNull { it.header },
                selection,
                selection.channelIndex
            ) { index, _ ->
                selection.channelIndex = index
                selection.categoryIndex = 0
                selection.selectedKey = null
                clearScrollPositions()
                saveSelection(selection)
                render(selection)
                currentItems(selection).firstOrNull(::isOpenable)?.let { selectAndOpen(it, selection) }
            }
        }
        val categories = model.channels.getOrNull(selection.channelIndex)?.categories.orEmpty()
        if (categories.size > 1) {
            addRow(
                activity.getString(R.string.discovery_category),
                categories.map { it.header },
                selection,
                selection.categoryIndex
            ) { index, _ ->
                selection.categoryIndex = index
                selection.selectedKey = null
                clearScrollPositions()
                saveSelection(selection)
                render(selection)
                currentItems(selection).firstOrNull(::isOpenable)?.let { selectAndOpen(it, selection) }
            }
        }
        if (categories.isNotEmpty()) {
            currentItems(selection).takeIf { it.isNotEmpty() }?.let {
                addRow(activity.getString(R.string.discovery_filter), it, selection)
            }
        }
        model.looseItems.takeIf { it.isNotEmpty() }?.let {
            addRow(activity.getString(R.string.discovery_filter), it, selection)
        }
    }

    private fun currentItems(selection: Selection): List<ExploreKind> =
        model.channels.getOrNull(selection.channelIndex)
            ?.categories?.getOrNull(selection.categoryIndex)?.items.orEmpty()

    private fun addRow(
        label: String,
        items: List<ExploreKind>,
        selection: Selection,
        selectedIndex: Int = -1,
        onSelect: ((Int, ExploreKind) -> Unit)? = null
    ) {
        if (items.isEmpty()) return
        val rowIndex = renderRowIndex++
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 36.dpToPx())
        }
        val itemContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
        }
        items.forEachIndexed { index, kind ->
            val selected = index == selectedIndex || selection.selectedKey == keyOf(kind)
            itemContainer.addView(createControl(kind, selected) {
                if (onSelect == null) handleKind(kind, selection) else onSelect(index, kind)
            })
        }
        val scrollView = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(itemContainer)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        row.addView(scrollView)
        // 给当前行及所有子视图设置长按监听，确保任何位置长按都能弹出书源菜单
        val longClickListener = View.OnLongClickListener {
            callBack.showSourceMenu()
            true
        }
        row.setOnLongClickListener(longClickListener)
        scrollView.setOnLongClickListener(longClickListener)
        itemContainer.setOnLongClickListener(longClickListener)
        repeat(itemContainer.childCount) { i ->
            itemContainer.getChildAt(i).setOnLongClickListener(longClickListener)
        }
        container.addView(row)
        val restoreX = restoreScrollPositions.getOrNull(rowIndex) ?: 0
        if (restoreX > 0) scrollView.post { scrollView.scrollTo(restoreX, 0) }
    }

    private fun createControl(kind: ExploreKind, selected: Boolean, onClick: () -> Unit): View =
        when (kind.type) {
            ExploreKind.Type.text -> createTextInput(kind)
            ExploreKind.Type.select -> createSelect(kind)
            else -> createChip(kind, selected, onClick)
        }

    private fun createChip(kind: ExploreKind, selected: Boolean, onClick: () -> Unit): TextView {
        val title = displayTitle(kind)
        val textView = TextView(activity).apply {
            text = title
            gravity = Gravity.CENTER
            maxLines = 1
            textSize = 14f
            setTextColor(Color.BLACK)
            background = chipBackground(selected)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                24.dpToPx()
            ).apply { marginEnd = 6.dpToPx() }
            setOnClickListener { onClick() }
        }
        // 计算padding使文字居中，不足4字时按4字宽度填充
        textView.post {
            val paint = textView.paint
            val textWidth = paint.measureText(title)
            val fourCharWidth = paint.measureText("中中中中")
            val targetWidth = maxOf(textWidth, fourCharWidth)
            val horizontalPadding = ((targetWidth - textWidth) / 2 + 8 * activity.resources.displayMetrics.density).toInt()
            val verticalPadding = (2 * activity.resources.displayMetrics.density).toInt()
            textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        updateDynamicTitle(kind, textView)
        return textView
    }

    private fun createTextInput(kind: ExploreKind): AutoCompleteTextView {
        val map = infoMap ?: return AutoCompleteTextView(activity)
        return AutoCompleteTextView(activity).apply {
            hint = displayTitle(kind)
            setText(map[kind.title])
            maxLines = 1
            textSize = 11f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.BLACK)
            setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 2.dpToPx())
            background = chipBackground(false)
            layoutParams = LinearLayout.LayoutParams(150.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 6.dpToPx()
            }
            var changeJob: Job? = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    map[kind.title] = s?.toString().orEmpty()
                    map.save()
                    val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                    changeJob?.cancel()
                    changeJob = scope.launch(IO) {
                        delay(500)
                        evalAction(action, kind.title)
                    }
                }
            })
        }
    }

    private fun createSelect(kind: ExploreKind): LinearLayout {
        val map = infoMap ?: return LinearLayout(activity)
        val values = kind.chars?.filterNotNull().orEmpty()
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, R.layout.item_text_common, values).also {
                it.setDropDownViewResource(R.layout.item_spinner_dropdown)
            }
            // 设置选中项背景透明，避免遮挡
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            // 选中文字靠右对齐
            post {
                val selectedView = findViewById<TextView>(android.R.id.text1)
                selectedView?.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }
            val selected = map[kind.title] ?: kind.default ?: values.firstOrNull().orEmpty()
            map[kind.title] = selected
            setSelectionSafely(values.indexOf(selected))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var initializing = true
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initializing) {
                        initializing = false
                        return
                    }
                    map[kind.title] = values.getOrNull(position).orEmpty()
                    map.save()
                    kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                        scope.launch(IO) { evalAction(action, kind.title) }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dpToPx(), 0, 4.dpToPx(), 0)
            background = chipBackground(false)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                24.dpToPx()
            ).apply { marginEnd = 6.dpToPx() }
            // 点击整个区域弹出下拉菜单
            setOnClickListener { spinner.performClick() }
            addView(TextView(activity).apply {
                text = displayTitle(kind)
                setTextColor(Color.BLACK)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                updateDynamicTitle(kind, this)
                // 点击标签也弹出下拉菜单
                setOnClickListener { spinner.performClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = 12.dpToPx()
            })
            addView(spinner, LinearLayout.LayoutParams(120.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun handleKind(kind: ExploreKind, selection: Selection) {
        when (kind.type) {
            ExploreKind.Type.url -> selectAndOpen(kind, selection)
            ExploreKind.Type.button -> {
                selection.selectedKey = keyOf(kind)
                saveSelection(selection)
                render(selection)
                kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                    scope.launch(IO) { evalAction(action, kind.title) }
                }
            }
            ExploreKind.Type.toggle -> {
                val values = kind.chars?.filterNotNull().orEmpty()
                if (values.isEmpty()) return
                val map = infoMap ?: return
                val current = map[kind.title] ?: kind.default ?: values.first()
                map[kind.title] = values[(values.indexOf(current).coerceAtLeast(0) + 1) % values.size]
                map.save()
                selection.selectedKey = keyOf(kind)
                saveSelection(selection)
                render(selection)
                kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                    scope.launch(IO) { evalAction(action, kind.title) }
                }
            }
        }
    }

    private fun selectAndOpen(kind: ExploreKind, selection: Selection) {
        val url = kind.url?.takeIf { it.isNotBlank() } ?: return
        selection.selectedKey = keyOf(kind)
        saveSelection(selection)
        render(selection)
        callBack.openExplore(displayTitle(kind), url)
    }

    private fun isOpenable(kind: ExploreKind): Boolean =
        kind.type == ExploreKind.Type.url && !kind.url.isNullOrBlank()

    private fun findSelectedOpenable(selection: Selection): ExploreKind? {
        val allItems = model.shortcuts + model.looseItems + currentItems(selection)
        return allItems.firstOrNull {
            selection.selectedKey == keyOf(it) && isOpenable(it)
        } ?: currentItems(selection).firstOrNull(::isOpenable)
        ?: model.shortcuts.firstOrNull(::isOpenable)
        ?: model.looseItems.firstOrNull(::isOpenable)
    }

    private fun loadSelection(sourceUrl: String): Selection {
        val json = activity.getPrefString(selectionPrefKey(sourceUrl)) ?: return Selection()
        return runCatching {
            JSONObject(json).run {
                Selection(
                    channelIndex = optInt("channelIndex", 0),
                    categoryIndex = optInt("categoryIndex", 0),
                    selectedKey = optString("selectedKey").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(Selection())
    }

    private fun saveSelection(selection: Selection) {
        val sourceUrl = source?.bookSourceUrl ?: return
        val json = JSONObject().apply {
            put("channelIndex", selection.channelIndex)
            put("categoryIndex", selection.categoryIndex)
            put("selectedKey", selection.selectedKey.orEmpty())
        }
        activity.putPrefString(selectionPrefKey(sourceUrl), json.toString())
    }

    private fun selectionPrefKey(sourceUrl: String): String =
        "exploreSelection_${MD5Utils.md5Encode(sourceUrl)}"

    private fun displayTitle(kind: ExploreKind): String {
        val viewName = kind.viewName
        return if (!viewName.isNullOrBlank() && viewName.length >= 2 &&
            viewName.first() == '\'' && viewName.last() == '\'') {
            viewName.substring(1, viewName.lastIndex)
        } else cleanTitle(kind.title)
    }

    private fun cleanTitle(title: String): String = title
        .replace("༺", "").replace("༻", "")
        .replace("ˇ»`ʚ", "").replace("ɞ´«ˇ", "")
        .replace(Regex("^=+\\s*|\\s*=+$"), "")
        .trim()

    private fun saveScrollPositions() {
        val sourceKey = source?.bookSourceUrl ?: return
        if (container.childCount == 0) return
        scrollPositions[sourceKey] = buildList {
            repeat(container.childCount) { index ->
                val row = container.getChildAt(index) as? LinearLayout
                add((row?.getChildAt(0) as? HorizontalScrollView)?.scrollX ?: 0)
            }
        }
    }

    private fun clearScrollPositions() {
        source?.bookSourceUrl?.let(scrollPositions::remove)
        restoreScrollPositions = emptyList()
        resetScrollOnNextRender = true
    }

    private fun updateDynamicTitle(kind: ExploreKind, textView: TextView) {
        val viewName = kind.viewName?.takeIf {
            it.isNotBlank() && !(it.first() == '\'' && it.last() == '\'')
        } ?: return
        val version = renderVersion
        scope.launch(IO) {
            val value = evalJs(viewName)
            withContext(Dispatchers.Main) {
                if (version == renderVersion && !value.isNullOrBlank()) textView.text = value
            }
        }
    }

    private suspend fun evalAction(action: String, title: String) {
        val currentSource = source ?: return
        val map = infoMap ?: return
        val java = SourceLoginJsExtensions(activity, currentSource,
            callback = object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit
                override fun reUiView(deltaUp: Boolean) {
                    scope.launch { callBack.refreshExplore() }
                }
            })
        runCatching {
            runScriptWithContext {
                currentSource.evalJS(action) {
                    put("java", java)
                    put("infoMap", map)
                }
            }
        }.onFailure { AppLog.put("ExploreUI Button $title JavaScript error", it) }
    }

    private suspend fun evalJs(js: String): String? {
        val currentSource = source ?: return null
        val map = infoMap ?: return null
        return runCatching {
            runScriptWithContext {
                currentSource.evalJS(js) { put("infoMap", map) }.toString()
            }
        }.onFailure { AppLog.put(currentSource.getTag() + " exploreUi err", it) }.getOrNull()
    }

    private fun chipBackground(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12.dpToPx().toFloat()
        setColor(Color.WHITE)
        setStroke(1.dpToPx(), Color.BLACK)
        if (selected) {
            setColor(Color.parseColor("#F5F5F5"))
            setStroke(2.dpToPx(), Color.BLACK)
        }
    }

    private fun keyOf(kind: ExploreKind): String =
        "${kind.title}|${kind.type}|${kind.url}|${kind.action}"
}