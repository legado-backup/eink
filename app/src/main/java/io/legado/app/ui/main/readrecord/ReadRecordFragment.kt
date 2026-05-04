package io.legado.app.ui.main.readrecord

import kotlinx.coroutines.Dispatchers
import android.app.DatePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDaySummaryBinding
import io.legado.app.databinding.ItemReadRecordRecentBookBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.about.ReadHeatmapCell
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class ReadRecordFragment() : BaseFragment(R.layout.activity_read_record), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")
    private val binding by viewBinding(ActivityReadRecordBinding::bind)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
    private val showFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault())

    private var selectedDate: LocalDate = LocalDate.now()
    private var loadJob: Job? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.visibility = View.GONE
        binding.tvRecordDate.applyStatusBarPadding(true)
        binding.tvRecordDate.setOnClickListener { showDatePicker() }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.book_read_record, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
                item.isChecked = AppConfig.enableReadRecord
            }
            R.id.menu_clear_record -> {
                alert(R.string.delete, R.string.sure_del) {
                    yesButton {
                        lifecycleScope.launch(IO) {
                            appDb.readRecordDailyDao.clear()
                            appDb.readRecordDao.clear()
                        }
                        loadData()
                    }
                }
            }
        }
    }

    private fun loadData() {
        if (loadJob?.isActive == true) return
        loadJob = lifecycleScope.launch(IO) {
            // 1. 拿到所有每日记录
            val allList = appDb.readRecordDailyDao.allDesc
                .mapNotNull {
                    runCatching {
                        LocalDate.parse(it.date) to it.readTime
                    }.getOrNull()
                }
            val dateTimeMap = allList.toMap()

            // ========== 你要的正确逻辑 ==========
            // 今日：选中当天时长
            val todayTime = dateTimeMap[selectedDate] ?: 0L

            // 本月：本月1号到月底 全部总和
            val currMonth = YearMonth.from(selectedDate)
            val monthStart = currMonth.atDay(1)
            val monthEnd = currMonth.atEndOfMonth()
            val monthTime = allList
                .filter { (date, _) -> date >= monthStart && date <= monthEnd }
                .sumOf { it.second }

            // 累计：所有历史全部总和
            val totalTime = allList.sumOf { it.second }

            // 活跃天数：有时长的天数
            val activeDays = allList.count { it.second > 0L }

            // 传给热力图：全部日期时长
            val heatCells = allList.map { ReadHeatmapCell(it.first, it.second) }

            // 近14天列表
            val last14List = allList.take(14).map { DailyReadSummary(it.first, it.second) }

            // 最近书籍
            val bookMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
            val recentBooks = appDb.bookDao.all
                .filter { it.name.isNotBlank() }
                .map {
                    RecentReadBook(it, bookMap[it.name]?.readTime ?: 0L)
                }
                .filter { it.totalReadTime > 0 }
                .sortedByDescending { it.totalReadTime }
                .take(6)

            withContext(Dispatchers.Main) {
                renderUI(todayTime, monthTime, totalTime, activeDays, heatCells, last14List, recentBooks)
            }
        }
    }

    private fun renderUI(
        todayTime: Long,
        monthTime: Long,
        monthTotal: Long,
        activeDays: Int,
        heatCells: List<ReadHeatmapCell>,
        last14: List<DailyReadSummary>,
        books: List<RecentReadBook>
    ) {
        binding.tvRecordDate.text = selectedDate.format(showFormatter)

        // 今日
        binding.tvTodayValue.text = formatDuring(todayTime)
        // 本月
        binding.tvMonthValue.text = formatDuring(monthTotal)
        // 累计
        binding.tvTotalValue.text = formatDuring(monthTotal)
        // 活跃天数
        binding.tvActiveDaysValue.text = activeDays.toString()

        binding.tvHeatmapEmpty.isVisible = heatCells.isEmpty()

        renderDailyList(last14)
        renderBookList(books)
        applyPageChrome(heatCells)
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedDate = LocalDate.of(y, m + 1, d)
                loadData()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    private fun renderDailyList(list: List<DailyReadSummary>) {
        binding.llDailyRecords.removeAllViews()
        binding.tvDailyRecordsEmpty.isVisible = list.isEmpty()
        list.forEachIndexed { idx, item ->
            val itemBinding = ItemReadRecordDaySummaryBinding
                .inflate(layoutInflater, binding.llDailyRecords, false)
            itemBinding.tvDayTitle.text = item.date.format(showFormatter)
            itemBinding.tvDaySubtitle.text = item.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            itemBinding.tvDayTime.text = formatDuring(item.readTime)
            binding.llDailyRecords.addView(itemBinding.root)
            if (idx != list.lastIndex) binding.llDailyRecords.addView(createDivider())
        }
    }

    private fun renderBookList(list: List<RecentReadBook>) {
        binding.llRecentBooks.removeAllViews()
        binding.tvRecentBooksEmpty.isVisible = list.isEmpty()
        list.forEachIndexed { idx, item ->
            val itemBinding = ItemReadRecordRecentBookBinding
                .inflate(layoutInflater, binding.llRecentBooks, false)
            itemBinding.vAccent.background = createFillDrawable(accentColor, 3f)
            itemBinding.tvBookName.text = item.book.name
            itemBinding.tvBookMeta.text = item.book.author
            itemBinding.tvBookTime.text = formatDuring(item.totalReadTime)
            itemBinding.root.setOnClickListener { startActivityForBook(item.book) }
            binding.llRecentBooks.addView(itemBinding.root)
            if (idx != list.lastIndex) binding.llRecentBooks.addView(createDivider())
        }
    }

    private fun applyPageChrome(heatCells: List<ReadHeatmapCell>) {
        val baseColor = backgroundColor
        val baseIsLight = ColorUtils.isColorLight(baseColor)
        val panelSurfaceColor = if (baseIsLight) {
            ColorUtils.blendColors(baseColor,
                ContextCompat.getColor(requireContext(), R.color.background_card), 0.82f)
        } else {
            ColorUtils.blendColors(baseColor,
                ContextCompat.getColor(requireContext(), R.color.white), 0.1f)
        }

        binding.heatmapView.submit(heatCells, accentColor, panelSurfaceColor)
    }

    private fun createDivider(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply { marginStart = 15.dpToPx() }
            setBackgroundColor(ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
    }

    private fun createFillDrawable(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dpToPx()
            setColor(color)
        }
    }

    private fun formatDuring(mss: Long): String {
        val h = mss / 1000 / 60 / 60
        val m = mss / 1000 / 60 % 60
        return "${h}小时${m}分钟"
    }

}

private data class DailyReadSummary(val date: LocalDate, val readTime: Long)
private data class RecentReadBook(val book: Book, val totalReadTime: Long)