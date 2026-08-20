package io.legado.app.ui.book.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI 阅读助手
 * 加载本地 HTML，通过 JS Bridge 注入阅读数据
 */
class AiChatActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        webView.addJavascriptInterface(AiBridge(), "LegadoJSBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectReadData()
            }
        }

        // 读取 assets 中的 HTML 文件
        val htmlContent = assets.open("ai_chat.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
    }

    private fun injectReadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = buildJsonData()
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("""
                        if (window.setReadData) {
                            window.setReadData($json);
                        } else {
                            window.legadoReadData = $json;
                        }
                    """.trimIndent(), null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AiChatActivity, "数据注入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildJsonData(): String {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter

        val json = JSONObject().apply {
            put("bookName", book?.name ?: "")
            put("bookAuthor", book?.author ?: "")
            put("chapterTitle", chapter?.title ?: "")
            put("chapterContent", chapter?.getContent() ?: "")
            put("durChapterIndex", book?.durChapterIndex ?: 0)
            put("durChapterTitle", book?.durChapterTitle ?: "")
            put("totalChapterNum", book?.totalChapterNum ?: 0)
            put("durChapterTime", book?.durChapterTime ?: 0L)
            put("readProgress", "${(book?.durChapterIndex ?: 0) + 1}/${book?.totalChapterNum ?: 0}章")
            put("deviceModel", android.os.Build.MODEL)
        }

        return json.toString()
    }

    inner class AiBridge {
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@AiChatActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AiChatActivity::class.java))
        }
    }
}
