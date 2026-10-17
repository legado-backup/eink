package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.BuildConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.file.FilePickerDialog
import io.legado.app.ui.file.HandleFileContract
import org.json.JSONObject
import java.io.File

/**
 * 标题样式制作页
 *
 * - WebView 加载 assets/book_title_style_editor.html
 * - HTML 里点"选择图片" → AndroidBridge.pickImage()
 *   → 弹"自带文件 / 系统文件"选择框
 *   → 选好后读成 base64 → 分块发给 HTML
 * - HTML 里点"保存" → AndroidBridge.saveToEink(html)
 *   → 弹输入框让用户输入样式名
 *   → 写 /sdcard/E-ink/样式名.txt（同名直接覆盖）
 *   → setResult 回传给 ReadBookActivity
 */
class TitleStyleMakerActivity : AppCompatActivity(),
    FilePickerDialog.CallBack {

    companion object {
        const val RESULT_HTML = "result_html"
        private const val EINK_DIR_NAME = "E-ink"
        private const val ASSET_NAME = "book_title_style_editor.html"
        private const val DEFAULT_FILE_NAME = "标题样式"
        private const val CHUNK_SIZE = 400_000
    }

    private lateinit var webView: WebView

    /** 系统文件选择器 */
    private val systemImagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        handlePickedImage(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    injectScreenInfo(view)
                }
            }

            addJavascriptInterface(JsBridge(), "AndroidBridge")

            val html = try {
                assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Toast.makeText(
                    this@TitleStyleMakerActivity,
                    "加载编辑器失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
            loadDataWithBaseURL(
                "https://appassets.local/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        }

        setContentView(webView)
    }

    /** 把 ChapterProvider 的真实尺寸注入 HTML */
    private fun injectScreenInfo(view: WebView) {
        try {
            val obj = JSONObject().apply {
                put("screenWidth", resources.displayMetrics.widthPixels)
                put("screenHeight", resources.displayMetrics.heightPixels)
                put("visibleWidth", ChapterProvider.visibleWidth)
                put("visibleHeight", ChapterProvider.visibleHeight)
                put("paddingLeft", ChapterProvider.paddingLeft)
                put("paddingRight", ChapterProvider.paddingRight)
                put("paddingTop", ChapterProvider.paddingTop)
                put("density", resources.displayMetrics.density)
                put("viewWidth", ChapterProvider.viewWidth)
                put("doublePage", ChapterProvider.doublePage)
                // 关键：把正文 paint 的字号传过去，让预览的 1em 与阅读页一致
                put("contentTextSize", ChapterProvider.contentPaint.textSize.toDouble())
            }
            val js = "window.__setScreenInfo && window.__setScreenInfo(${JSONObject.quote(obj.toString())})"
            view.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    inner class JsBridge {
        /** HTML 里点"选择图片"调用 */
        @JavascriptInterface
        fun pickImage() {
            runOnUiThread { showPickSourceDialog() }
        }

        /** HTML 里点"保存"调用 */
        @JavascriptInterface
        fun saveToEink(html: String) {
            runOnUiThread { doSaveToEink(html) }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@TitleStyleMakerActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 弹"自带文件 / 系统文件" */
    private fun showPickSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("选择图片来源")
            .setItems(arrayOf("自带文件", "系统文件")) { _, which ->
                when (which) {
                    0 -> {
                        FilePickerDialog.show(
                            supportFragmentManager,
                            mode = HandleFileContract.FILE,
                            allowExtensions = arrayOf(
                                "png", "jpg", "jpeg", "webp", "gif", "bmp"
                            )
                        )
                    }
                    1 -> {
                        systemImagePicker.launch(arrayOf("image/*"))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 自带文件选择器回调 */
    override fun onResult(data: Intent) {
        val uri = data.data ?: return
        handlePickedImage(uri)
    }

    /** 读取图片 → base64 → 分块发送给 HTML */
    private fun handlePickedImage(uri: Uri) {
        try {
            val inputStream = if (uri.scheme == "file") {
                File(uri.path!!).inputStream()
            } else {
                contentResolver.openInputStream(uri)
                    ?: throw Exception("无法打开图片")
            }
            inputStream.use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) {
                    Toast.makeText(this, "图片内容为空", Toast.LENGTH_SHORT).show()
                    return
                }
                val mime = contentResolver.getType(uri) ?: guessMime(uri)
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:$mime;base64,$base64"
                val fileName = getFileName(uri)
                val fileSize = bytes.size

                sendBgImageChunked(dataUri, fileName, fileSize)
                Toast.makeText(this, "已选择：$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取图片失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 分块发送 base64 图片数据到 HTML。
     * 单次 evaluateJavascript 有长度限制（约 1~2MB），
     * 大图直接发会被静默截断，导致 Image.onload 失败。
     * 这里按 CHUNK_SIZE 分块，JS 端按索引拼接。
     */
    private fun sendBgImageChunked(dataUri: String, fileName: String, fileSize: Int) {
        val chunks = dataUri.chunked(CHUNK_SIZE)
        webView.post {
            try {
                // 1. 初始化
                webView.evaluateJavascript(
                    "window.__bgSetTotal && window.__bgSetTotal(${chunks.size});",
                    null
                )
                // 2. 分块发送
                chunks.forEachIndexed { index, chunk ->
                    val js = "window.__bgSetChunk && window.__bgSetChunk($index, ${JSONObject.quote(chunk)});"
                    webView.evaluateJavascript(js, null)
                }
                // 3. 通知结束，JS 拼接并显示
                val finishJs = "window.__bgFinish && window.__bgFinish(" +
                        JSONObject.quote(fileName) + ", " + fileSize + ");"
                webView.evaluateJavascript(finishJs, null)
            } catch (e: Exception) {
                Toast.makeText(
                    this@TitleStyleMakerActivity,
                    "发送图片失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path!!).name
        }
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "image"
    }

    private fun guessMime(uri: Uri): String {
        val name = getFileName(uri).lowercase()
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".bmp") -> "image/bmp"
            else -> "image/*"
        }
    }

    /** HTML 里点"保存" → 弹输入框让用户输入样式名 → 保存（同名直接覆盖） */
    private fun doSaveToEink(html: String) {
        val edit = android.widget.EditText(this).apply {
            setText(DEFAULT_FILE_NAME)
            setSelection(text.length)
            setPadding(60, 40, 60, 40)
            hint = "样式名"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("保存样式")
            .setMessage("保存到 /sdcard/$EINK_DIR_NAME/")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                var name = edit.text.toString().trim()
                if (name.isBlank()) name = DEFAULT_FILE_NAME
                name = name.replace(Regex("""[\\/:*?"<>|\s]"""), "_")
                if (!name.endsWith(".txt", ignoreCase = true)) name = "$name.txt"
                writeToEinkDir(name, html)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 真正写文件，同名直接覆盖 */
    private fun writeToEinkDir(fileName: String, html: String) {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), EINK_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "无法创建目录：${dir.absolutePath}", Toast.LENGTH_LONG).show()
                return
            }
            val file = File(dir, fileName)
            if (file.exists()) {
                file.delete()
            }
            file.writeText(html, Charsets.UTF_8)

            Toast.makeText(this, "已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()

            setResult(RESULT_OK, Intent().apply {
                putExtra(RESULT_HTML, html)
            })
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        try {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}