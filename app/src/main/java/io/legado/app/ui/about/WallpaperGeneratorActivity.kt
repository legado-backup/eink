package io.legado.app.ui.about

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * 阅读账单壁纸生成器 - 本地 HTML 版本
 * 加载 assets 中的单文件 HTML，无需服务器
 */
class WallpaperGeneratorActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var screenWidth = 1080
    private var screenHeight = 2400

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initScreenSize()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            // 本地 HTML 加载本地资源需要开启
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        webView.addJavascriptInterface(LegadoBridge(), "LegadoBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAllData()
            }
        }

        // 加载本地 HTML
        webView.loadUrl("file:///android_asset/wallpaper/index.html")
    }

    private fun initScreenSize() {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun injectAllData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = buildJsonData()
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("""
                        if (window.onLegadoData) {
                            window.onLegadoData($json);
                        } else {
                            window.legadoData = $json;
                        }
                    """.trimIndent(), null)
                    Toast.makeText(this@WallpaperGeneratorActivity, "数据已同步", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WallpaperGeneratorActivity, "数据同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildJsonData(): String {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        val readRecords = appDb.readRecordDao.allShow
        val readMap = readRecords.associateBy { it.bookName }
        val totalAll = appDb.readRecordDao.allTime

        val dailyRaw = appDb.readRecordDailyDao.allDesc.mapNotNull {
            runCatching {
                JSONObject().apply {
                    put("date", it.date)
                    put("readTime", it.readTime)
                }
            }.getOrNull()
        }
        val dailyMap = dailyRaw.associate { it.getString("date") to it.getLong("readTime") }

        val books = appDb.bookDao.all
            .filter { it.durChapterTime > 0L && it.name.isNotBlank() }
            .sortedByDescending { it.durChapterTime }
            .take(10)

        val todayTime = dailyMap[today.format(formatter)] ?: 0L
        val monthTime = dailyRaw.filter {
            it.getString("date").startsWith(today.format(monthFormatter))
        }.sumOf { it.getLong("readTime") }

        val thirtyDaysAgo = today.minusDays(29)
        val activeDays = dailyRaw.filter {
            val date = LocalDate.parse(it.getString("date"))
            !date.isBefore(thirtyDaysAgo) && it.getLong("readTime") > 0
        }.size

        val json = JSONObject().apply {
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("deviceModel", android.os.Build.MODEL)
            put("today", today.format(formatter))
            put("todayTime", todayTime)
            put("monthTime", monthTime)
            put("totalTime", totalAll)
            put("activeDays", activeDays)
            put("bookCount", books.size)

            put("books", JSONArray().apply {
                books.forEach { book ->
                    put(JSONObject().apply {
                        put("name", book.name)
                        put("author", book.author)
                        put("durChapterIndex", book.durChapterIndex)
                        put("durChapterTitle", book.durChapterTitle ?: "")
                        put("totalChapterNum", book.totalChapterNum)
                        put("durChapterTime", book.durChapterTime)
                    })
                }
            })

            put("daily", JSONArray().apply {
                dailyRaw.forEach { put(it) }
            })
        }

        return json.toString()
    }

    inner class LegadoBridge {
        @JavascriptInterface
        fun getScreenWidth(): Int = screenWidth

        @JavascriptInterface
        fun getScreenHeight(): Int = screenHeight

        @JavascriptInterface
        fun getDeviceModel(): String = android.os.Build.MODEL

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread {
                Toast.makeText(this@WallpaperGeneratorActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveBitmap(base64Data: String, filename: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val pureBase64 = base64Data.substringAfter(",")
                    val bytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)

                    val name = filename.ifEmpty { "reading_bill_${System.currentTimeMillis()}.png" }
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { out ->
                            out.write(bytes)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cv.clear()
                            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, cv, null, null)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@WallpaperGeneratorActivity, "保存成功", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WallpaperGeneratorActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
