package io.legado.app.ui.book.read.config

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.app.AlertDialog
import android.app.DownloadManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.legado.app.R
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.startActivity
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import kotlin.concurrent.thread
import kotlin.math.abs

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
class LanTransferActivity : AppCompatActivity() {
    private val port = 54321
    private val socketPort = 54322
    private val saveDir by lazy { File(Environment.getExternalStorageDirectory(), "E-ink") }
    private var webServer: NanoHTTPD? = null
    private var socketServer: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tvInfo: TextView
    private lateinit var llFileList: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var tvPageInfo: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnPrev: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var btnQrCode: ImageView
    private lateinit var btnAddDevice: ImageView
    private lateinit var fileListScrollView: ScrollView

    private var htmlCache = ""
    private var searchKeyword = ""
    private val pendingDownloads = mutableMapOf<Long, String>()
    private var downloadReceiver: BroadcastReceiver? = null

    private var pageSize = 5
    private var currentPage = 0
    private var totalPages = 0
    private var allFilesList: List<File> = emptyList()
    private var itemHeight = 0

    private var remoteDeviceIp: String? = null
    private var remoteFilesList: List<RemoteFileInfo> = emptyList()
    private var isRemoteMode = false
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ========== WakeLock：保持屏幕常亮 + CPU 不休眠 ==========
    private var wakeLock: PowerManager.WakeLock? = null

    // ========== 滑动切页相关（Activity 级别，优先级最高）==========
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f  // 水平滑动触发阈值（px）
    private var isSwipePaging = false  // 标记是否正在滑动翻页

    data class RemoteFileInfo(
        val name: String,
        val size: Long,
        val time: Long,
        val ip: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        if (!saveDir.exists()) saveDir.mkdirs()

        val ip = getWifiIp()
        tvInfo.text = "访问地址：$ip:$port\n保存目录：/内置储存/E-ink"

        rootLayout.post {
            calculatePageSize()
            refreshListAndHtml()
        }
        startServer()
        startSocketServer()
        keepWakeLock()

        // ========== 获取 WakeLock，保持屏幕常亮 + CPU 不休眠 ==========
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "legado:LanTransfer"
        )
        wakeLock?.acquire()

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val fileName = pendingDownloads.remove(id) ?: return
                val file = File(saveDir, fileName)
                if (file.exists()) {
                    if (fileName.lowercase().endsWith(".txt") || fileName.lowercase().endsWith(".epub")) {
                        addToBookshelf(file)
                    }
                    if (!isRemoteMode) {
                        mainHandler.post { refreshListAndHtml() }
                    }
                    mainHandler.post {
                        Toast.makeText(this@LanTransferActivity, "下载完成：$fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun initView() {
        val screenHeight = resources.displayMetrics.heightPixels
        val statusBarHeight = getStatusBarHeight()
        val ipCardHeight = dp(100)
        val paddingTotal = dp(16 * 2 + 16)
        val listHeight = screenHeight - statusBarHeight - ipCardHeight - paddingTotal

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f5f5f5"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cardIp = createCardLayout()
        val ipInnerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        tvInfo = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipInnerLayout.addView(tvInfo)

        val btnSearch = ImageView(this).apply {
            setImageDrawable(createSvgSearchDrawable())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { showSearchDialog() }
        }
        ipInnerLayout.addView(btnSearch)

        // ===== ADDED: 二维码按钮 =====
        btnQrCode = ImageView(this).apply {
            setImageResource(R.drawable.ic_8)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { showQrCodeDialog() }
        }
        ipInnerLayout.addView(btnQrCode)

        btnAddDevice = ImageView(this).apply {
            setImageDrawable(createSvgPlusDrawable())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (isRemoteMode) {
                    isRemoteMode = false
                    remoteDeviceIp = null
                    remoteFilesList = emptyList()
                    btnAddDevice.setImageDrawable(createSvgPlusDrawable())
                    refreshListAndHtml()
                    Toast.makeText(this@LanTransferActivity, "已断开连接", Toast.LENGTH_SHORT).show()
                } else {
                    showAddDeviceDialog()
                }
            }
        }
        ipInnerLayout.addView(btnAddDevice)
        cardIp.addView(ipInnerLayout)
        rootLayout.addView(cardIp)

        val cardList = createCardLayout()
        cardList.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (listHeight > dp(200)) listHeight else dp(200)
        ).apply { setMargins(0, dp(16), 0, 0) }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        tvTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(tvTitle)

        btnPrev = ImageView(this).apply {
            setImageDrawable(createSvgArrowLeftDrawable())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (currentPage > 0) {
                    currentPage--
                    if (isRemoteMode) renderRemotePage() else renderPage()
                }
            }
        }
        headerLayout.addView(btnPrev)

        tvPageInfo = TextView(this).apply {
            text = "1/1"
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        headerLayout.addView(tvPageInfo)

        btnNext = ImageView(this).apply {
            setImageDrawable(createSvgArrowRightDrawable())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (currentPage < totalPages - 1) {
                    currentPage++
                    if (isRemoteMode) renderRemotePage() else renderPage()
                }
            }
        }
        headerLayout.addView(btnNext)

        cardList.addView(headerLayout)

        llFileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }

        fileListScrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(llFileList)
        }
        cardList.addView(fileListScrollView)
        rootLayout.addView(cardList)

        setContentView(rootLayout)
    }


    // ===== ADDED: 显示二维码弹窗 =====
    private fun showQrCodeDialog() {
        val ip = getWifiIp()
        val url = "http://$ip:$port/"
        val qrSize = dp(220)
        val bitmap = createQrCodeBitmap(url, qrSize)

        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(qrSize, qrSize)
            setImageBitmap(bitmap)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setView(imageView)
            .show()
    }

    // ===== ADDED: 生成二维码 Bitmap =====
    private fun createQrCodeBitmap(content: String, size: Int): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showSearchDialog() {
        val editText = EditText(this).apply {
            hint = "输入文件名关键词"
            setText(searchKeyword)
            setSelection(searchKeyword.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("搜索文件")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                searchKeyword = editText.text.toString().trim()
                currentPage = 0
                refreshListAndHtml()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddDeviceDialog() {
        val editText = EditText(this).apply {
            hint = "输入设备IP地址，如 192.168.1.20"
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("连接其他设备")
            .setView(editText)
            .setPositiveButton("连接") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotEmpty()) connectToRemoteDevice(ip)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectToRemoteDevice(ip: String) {
        val url = "http://$ip:$port/"
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    Toast.makeText(this@LanTransferActivity, "连接失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        Toast.makeText(this@LanTransferActivity, "连接失败：HTTP ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val html = response.body?.string() ?: ""
                val remoteFiles = parseRemoteFileList(html, ip)
                mainHandler.post {
                    if (remoteFiles.isEmpty()) {
                        Toast.makeText(this@LanTransferActivity, "该设备暂无文件", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    remoteDeviceIp = ip
                    remoteFilesList = remoteFiles
                    isRemoteMode = true
                    btnAddDevice.setImageDrawable(createSvgCloseDrawable())
                    refreshListAndHtml()
                    Toast.makeText(this@LanTransferActivity, "已连接 $ip，共 ${remoteFiles.size} 个文件", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun parseRemoteFileList(html: String, ip: String): List<RemoteFileInfo> {
        val list = mutableListOf<RemoteFileInfo>()
        try {
            val doc: Document = Jsoup.parse(html)
            val fileItems: Elements = doc.select(".file-item")
            for (item in fileItems) {
                val nameElem = item.selectFirst(".file-name")
                val descElem = item.selectFirst(".file-desc")
                if (nameElem != null && descElem != null) {
                    val name = nameElem.text().trim()
                    val descText = descElem.text().trim()
                    val sizeMatch = Regex("""大小：([^|]+)""").find(descText)
                    val sizeStr = sizeMatch?.groupValues?.get(1)?.trim() ?: "0 B"
                    val size = parseSizeToBytes(sizeStr)
                    val timeMatch = Regex("""\|\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})""").find(descText)
                    val timeStr = timeMatch?.groupValues?.get(1) ?: "2024-01-01 00:00"
                    val time = parseTimeToMillis(timeStr)
                    list.add(RemoteFileInfo(name, size, time, ip))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun parseSizeToBytes(sizeStr: String): Long {
        val trimmed = sizeStr.trim()
        val regex = Regex("""(\d+\.?\d*)\s*(B|KB|MB|GB)""")
        val match = regex.find(trimmed) ?: return 0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0
        return when (match.groupValues[2].uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse(timeStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    private fun downloadRemoteFile(remoteFile: RemoteFileInfo) {
        val ip = remoteFile.ip
        val fileName = remoteFile.name
        val safeName = getUniqueFileName(fileName)
        val targetFile = File(saveDir, safeName)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在下载")
            .setMessage("$fileName\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            var socket: Socket? = null
            try {
                socket = Socket(ip, socketPort)
                socket.soTimeout = 30000
                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val inputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
                outputStream.writeUTF(fileName)
                outputStream.flush()
                val status = inputStream.readUTF()
                if (status != "OK") throw IOException("服务器返回错误: $status")
                val fileSize = inputStream.readLong()
                FileOutputStream(targetFile).use { fileOut ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fileOut.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (fileSize > 0) {
                            val progress = (totalBytes * 100 / fileSize).toInt()
                            mainHandler.post { progressDialog.setMessage("$fileName\n$progress%") }
                        }
                    }
                }
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(this@LanTransferActivity, "下载完成：$safeName", Toast.LENGTH_SHORT).show()
                    if (safeName.lowercase().endsWith(".txt") || safeName.lowercase().endsWith(".epub")) {
                        addToBookshelf(targetFile)
                    }
                    if (!isRemoteMode) refreshListAndHtml()
                }
            } catch (e: Exception) {
                targetFile.delete()
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(this@LanTransferActivity, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startSocketServer() {
        thread(name = "SocketFileServer") {
            try {
                socketServer = ServerSocket(socketPort)
                while (!isDestroyed) {
                    try {
                        val client = socketServer?.accept() ?: break
                        thread { handleSocketClient(client) }
                    } catch (e: Exception) { if (!isDestroyed) e.printStackTrace() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleSocketClient(client: Socket) {
        try {
            client.use { socket ->
                val inputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val fileName = inputStream.readUTF()
                val file = File(saveDir, fileName)
                if (!file.exists() || !file.isFile) {
                    outputStream.writeUTF("NOT_FOUND")
                    outputStream.flush()
                    return
                }
                outputStream.writeUTF("OK")
                outputStream.writeLong(file.length())
                outputStream.flush()
                file.inputStream().use { fileIn ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun getUniqueFileName(originalName: String): String {
        if (!File(saveDir, originalName).exists()) return originalName
        val lastDotIndex = originalName.lastIndexOf('.')
        val namePart = if (lastDotIndex > 0) originalName.substring(0, lastDotIndex) else originalName
        val extPart = if (lastDotIndex > 0) originalName.substring(lastDotIndex) else ""
        var counter = 1
        var newName: String
        do {
            newName = "${namePart}($counter)${extPart}"
            counter++
        } while (File(saveDir, newName).exists())
        return newName
    }

    private fun calculatePageSize() {
        val scrollViewHeight = fileListScrollView.height
        if (scrollViewHeight <= 0) return
        val tempItem = createFileItemView(File("test.txt"), 0, true)
        tempItem.measure(
            View.MeasureSpec.makeMeasureSpec(fileListScrollView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        itemHeight = tempItem.measuredHeight
        val dividerHeight = dp(1)
        val availableHeight = scrollViewHeight - dp(16)
        if (itemHeight > 0) {
            val itemTotalHeight = itemHeight + dividerHeight
            pageSize = maxOf(1, availableHeight / itemTotalHeight)
        }
    }

    private fun createFileItemView(file: File, index: Int, isMeasureOnly: Boolean): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textWrap.addView(TextView(this).apply {
            text = file.name
            setTextColor(Color.BLACK)
            textSize = 16f
        })
        textWrap.addView(TextView(this).apply {
            text = "大小：0 B | 2024-01-01 00:00"
            setTextColor(Color.parseColor("#444444"))
            textSize = 12f
        })
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
        }
        val btnEdit = ImageView(this).apply {
            setImageResource(R.drawable.ic_1)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val btnDel = ImageView(this).apply {
            setImageResource(R.drawable.ic_2)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { setMargins(dp(4), 0, 0, 0) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        actionsLayout.addView(btnEdit)
        actionsLayout.addView(btnDel)
        item.addView(textWrap)
        item.addView(actionsLayout)
        return item
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.BLACK)
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    @Suppress("DEPRECATION")
    private fun getWifiIp(): String {
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wm.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xFF,
            ipInt shr 8 and 0xFF,
            ipInt shr 16 and 0xFF,
            ipInt shr 24 and 0xFF
        )
    }

    private fun refreshListAndHtml() {
        if (isRemoteMode) {
            allFilesList = emptyList()
            val displayList = if (searchKeyword.isEmpty()) {
                remoteFilesList
            } else {
                remoteFilesList.filter { it.name.lowercase().contains(searchKeyword.lowercase()) }
            }
            totalPages = if (displayList.isEmpty()) 1 else (displayList.size + pageSize - 1) / pageSize
            if (currentPage >= totalPages) currentPage = totalPages - 1
            if (currentPage < 0) currentPage = 0
            renderRemotePage(displayList)
        } else {
            val allFiles = saveDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            allFilesList = if (searchKeyword.isEmpty()) {
                allFiles
            } else {
                allFiles.filter { it.name.lowercase().contains(searchKeyword.lowercase()) }
            }
            totalPages = if (allFilesList.isEmpty()) 1 else (allFilesList.size + pageSize - 1) / pageSize
            if (currentPage >= totalPages) currentPage = totalPages - 1
            if (currentPage < 0) currentPage = 0
            renderPage()
        }
        refreshWebHtml()
    }

    // ===== MODIFIED: 删除按钮添加确认弹窗 =====
    private fun renderPage() {
        tvTitle.text = "本机文件列表"
        llFileList.removeAllViews()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        tvPageInfo.text = "${currentPage + 1}/${totalPages}"
        btnPrev.isEnabled = currentPage > 0
        btnNext.isEnabled = currentPage < totalPages - 1
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, allFilesList.size)
        val pageFiles = if (allFilesList.isEmpty()) emptyList() else allFilesList.subList(startIndex, endIndex)

        if (pageFiles.isEmpty()) {
            llFileList.addView(TextView(this).apply {
                text = "暂无上传文件"
                textSize = 14f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            })
            return
        }

        pageFiles.forEachIndexed { index, f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val textWrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textWrap.addView(TextView(this).apply {
                text = f.name
                setTextColor(Color.BLACK)
                textSize = 16f
            })
            textWrap.addView(TextView(this).apply {
                text = "大小：${formatSize(f.length())} | ${dateFmt.format(Date(f.lastModified()))}"
                setTextColor(Color.parseColor("#444444"))
                textSize = 12f
            })
            val actionsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(8), 0, 0, 0) }
            }
            val btnEdit = ImageView(this).apply {
                setImageResource(R.drawable.ic_1)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { showRenameDialog(f) }
            }
            val btnDel = ImageView(this).apply {
                setImageResource(R.drawable.ic_2)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { setMargins(dp(4), 0, 0, 0) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    // ===== MODIFIED: 删除前弹窗确认 =====
                    AlertDialog.Builder(this@LanTransferActivity)
                        .setTitle("删除文件")
                        .setMessage("确定要删除此文件吗？")
                        .setPositiveButton("确定") { _, _ ->
                            if (f.exists()) {
                                f.delete()
                                refreshListAndHtml()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            actionsLayout.addView(btnEdit)
            actionsLayout.addView(btnDel)
            item.addView(textWrap)
            item.addView(actionsLayout)
            llFileList.addView(item)

            if (index != pageFiles.lastIndex) {
                llFileList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(Color.BLACK)
                })
            }

            item.setOnClickListener {
                when {
                    f.name.lowercase().endsWith(".txt") || f.name.lowercase().endsWith(".epub") -> addToBookshelfAndOpen(f)
                    f.name.lowercase().endsWith(".apk") -> installApk(f)
                    isImageFile(f.name) -> showImagePreview(f)
                }
            }
        }
    }

    private fun renderRemotePage(files: List<RemoteFileInfo> = remoteFilesList) {
        tvTitle.text = "互联文件列表"
        llFileList.removeAllViews()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        tvPageInfo.text = "${currentPage + 1}/${totalPages}"
        btnPrev.isEnabled = currentPage > 0
        btnNext.isEnabled = currentPage < totalPages - 1
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, files.size)
        val pageFiles = if (files.isEmpty()) emptyList() else files.subList(startIndex, endIndex)

        if (pageFiles.isEmpty()) {
            llFileList.addView(TextView(this).apply {
                text = "该设备暂无文件"
                textSize = 14f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            })
            return
        }

        pageFiles.forEachIndexed { index, remoteFile ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val textWrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textWrap.addView(TextView(this).apply {
                text = remoteFile.name
                setTextColor(Color.BLACK)
                textSize = 16f
            })
            textWrap.addView(TextView(this).apply {
                text = "大小：${formatSize(remoteFile.size)} | ${dateFmt.format(Date(remoteFile.time))}"
                setTextColor(Color.parseColor("#444444"))
                textSize = 12f
            })
            val actionsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(8), 0, 0, 0) }
            }
            val btnDownload = ImageView(this).apply {
                setImageDrawable(createSvgDownloadDrawable())
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { downloadRemoteFile(remoteFile) }
            }
            actionsLayout.addView(btnDownload)
            item.addView(textWrap)
            item.addView(actionsLayout)
            llFileList.addView(item)

            if (index != pageFiles.lastIndex) {
                llFileList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(Color.BLACK)
                })
            }
            item.setOnClickListener { downloadRemoteFile(remoteFile) }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val illegalChars = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        val sb = StringBuilder()
        for (c in name) {
            if (c in illegalChars) sb.append('_') else sb.append(c)
        }
        return sb.toString()
    }

    private fun showRenameDialog(file: File) {
        val editText = EditText(this).apply {
            setText(file.name)
            setSelection(0, file.name.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名文件")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) renameFile(file, newName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameFile(file: File, newName: String) {
        try {
            val safeName = sanitizeFileName(newName)
            if (safeName.isEmpty()) {
                Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                return
            }
            val newFile = File(file.parentFile, safeName)
            if (newFile.exists() && newFile.absolutePath != file.absolutePath) {
                Toast.makeText(this, "该文件名已存在", Toast.LENGTH_SHORT).show()
                return
            }
            val success = file.renameTo(newFile)
            if (success) {
                Toast.makeText(this, "重命名成功", Toast.LENGTH_SHORT).show()
                refreshListAndHtml()
            } else {
                Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "重命名出错：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制内容到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun refreshWebHtml() {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val allFiles = allFilesList

        val fileItemHtml = if (allFiles.isEmpty()) {
            "<div class=\"empty-tip\">暂无上传文件</div>"
        } else {
            buildString {
                allFiles.forEachIndexed { index, file ->
                    val sizeText = formatSize(file.length())
                    val timeText = dateFmt.format(Date(file.lastModified()))
                    val borderStyle = if (index == allFiles.lastIndex) "border-bottom:none;" else ""
                    val fileNameEncoded = try {
                        Base64.encodeToString(file.name.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
                            .replace("\n", "")
                    } catch (_: Exception) { "" }
                    append("""
                        <div class="file-item" style="$borderStyle">
                            <div style="min-width:0;flex:1">
                                <div class="file-name">${file.name}</div>
                                <div class="file-desc">大小：$sizeText | $timeText</div>
                            </div>
                            <div class="file-actions">
                                <a class="btn-icon" href="/download?f=${fileNameEncoded}" title="下载">
                                    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                                </a>
                                <a class="btn-icon delete" href="/delete?f=${fileNameEncoded}" title="删除">
                                    <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>
                                </a>
                            </div>
                        </div>
                    """.trimIndent())
                }
            }
        }

        htmlCache = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,minimum-scale=1.0,user-scalable=no">
<title>局域网文件传输</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;font-family:system-ui}
body{background:#f2f2f8;padding:16px}
.container{max-width:600px;margin:0 auto}
.card{background:#fff;border:2px solid #000;border-radius:16px;padding:20px;margin-bottom:16px}
.card-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}
.card-header h3{margin:0}
.clipboard-btn{width:32px;height:32px;display:flex;align-items:center;justify-content:center;border:1px solid #000;border-radius:8px;background:#fff;cursor:pointer;flex-shrink:0;margin-left:8px}
.clipboard-btn:hover{background:#f0f0f0}
.clipboard-btn svg{display:block}
.upload-box{border:2px solid #000;border-radius:10px;padding:12px;margin-bottom:16px;position:relative;overflow:hidden;transition:all 0.2s}
.upload-box.drag-over{border-color:#28a745;background:#f0fff4}
.upload-box input{position:absolute;left:0;top:0;width:100%;height:100%;opacity:0;cursor:pointer}
.btn-submit{width:100%;padding:14px;border:2px solid #000;border-radius:10px;background:#fff;font-size:16px;font-weight:bold;color:#000;cursor:pointer}
.btn-submit:disabled{opacity:0.5;cursor:not-allowed}
.progress-bar{width:100%;height:10px;background:#ddd;border-radius:5px;margin:10px 0;display:none}
.progress-fill{height:100%;width:0%;background:#28a745;border-radius:5px;transition:width 0.2s}
.file-list-card{height:calc(100vh - 260px);min-height:200px;overflow:hidden;display:flex;flex-direction:column;margin-bottom:0}
.file-list-scroll{overflow-y:auto;flex:1}
.file-item{display:flex;align-items:center;padding:14px 0;border-bottom:1px solid #ddd}
.file-name{font-size:16px;word-break:break-all}
.file-desc{font-size:12px;color:#444;margin-top:4px}
.file-actions{display:flex;gap:8px;flex-shrink:0;margin-left:12px}
.btn-icon{width:32px;height:32px;display:flex;align-items:center;justify-content:center;border:1px solid #000;border-radius:8px;background:#fff;text-decoration:none;color:#000;cursor:pointer}
.btn-icon:hover{background:#f0f0f0}
.btn-icon svg{display:block}
.empty-tip{text-align:center;padding:20px;color:#666}
.modal-overlay{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);display:none;align-items:center;justify-content:center;z-index:1000}
.modal-overlay.active{display:flex}
.modal-box{background:#fff;border:2px solid #000;border-radius:16px;padding:20px;width:90%;max-width:400px}
.modal-box h4{margin:0 0 12px 0;font-size:18px}
.modal-box textarea{width:100%;min-height:120px;border:2px solid #000;border-radius:8px;padding:12px;font-size:14px;resize:vertical;outline:none}
.modal-box textarea:focus{border-color:#333}
.modal-btns{display:flex;gap:10px;margin-top:16px;justify-content:flex-end}
.modal-btns button{padding:10px 20px;border:2px solid #000;border-radius:8px;background:#fff;font-size:14px;font-weight:bold;cursor:pointer}
.modal-btns button.primary{background:#000;color:#fff}
.modal-btns button:hover{opacity:0.8}
</style>
</head>
<body>
<div class="container">
    <div class="card">
        <div class="card-header">
            <h3>上传文件至 /E-ink</h3>
            <div class="clipboard-btn" onclick="openClipboardModal()" title="发送到剪贴板">
                <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
            </div>
        </div>
        <div class="upload-box" id="uploadBox">
            <span id="tip">选择文件或拖拽文件到此处</span>
            <input type="file" id="file" multiple>
        </div>
        <div class="progress-bar">
            <div class="progress-fill" id="progress"></div>
        </div>
        <button class="btn-submit" id="uploadBtn">确认上传</button>
    </div>
    <div class="card file-list-card">
        <h3>已上传文件列表</h3>
        <div class="file-list-scroll" id="list">$fileItemHtml</div>
    </div>
</div>

<div class="modal-overlay" id="clipboardModal">
    <div class="modal-box">
        <h4>发送到剪贴板</h4>
        <textarea id="clipboardText" placeholder="在此输入要复制的内容..."></textarea>
        <div class="modal-btns">
            <button onclick="closeClipboardModal()">取消</button>
            <button class="primary" onclick="sendToClipboard()">发送</button>
        </div>
    </div>
</div>

<script>
let fileList = [];
const uploadBox = document.getElementById('uploadBox');
const fileInput = document.getElementById('file');
const tip = document.getElementById('tip');

fileInput.onchange = function(e) {
    fileList = Array.from(this.files);
    updateTip();
};

uploadBox.addEventListener('dragover', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.classList.add('drag-over');
});

uploadBox.addEventListener('dragleave', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.classList.remove('drag-over');
});

uploadBox.addEventListener('drop', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.classList.remove('drag-over');
    const files = Array.from(e.dataTransfer.files);
    if (files.length > 0) {
        fileList = files;
        const dt = new DataTransfer();
        files.forEach(f => dt.items.add(f));
        fileInput.files = dt.files;
        updateTip();
    }
});

function updateTip() {
    if (fileList.length === 0) {
        tip.innerText = "选择文件或拖拽文件到此处";
    } else {
        tip.innerText = "已选择 " + fileList.length + " 个文件";
    }
}

async function uploadSingleFile(fileObj, progressFill) {
    return new Promise(function(resolve, reject) {
        const formData = new FormData();
        formData.append("file", fileObj);
        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/upload", true);
        xhr.setRequestHeader("X-File-Name", btoa(unescape(encodeURIComponent(fileObj.name))));
        xhr.upload.onprogress = function(e) {
            if (e.lengthComputable) {
                let pct = Math.round((e.loaded / e.total) * 100);
                progressFill.style.width = pct + "%";
            }
        };
        xhr.onload = function() {
            if (xhr.status === 200) {
                resolve(xhr.response);
            } else {
                reject(new Error("上传失败: " + xhr.status));
            }
        };
        xhr.onerror = function() { reject(new Error("网络错误")); };
        xhr.ontimeout = function() { reject(new Error("上传超时")); };
        xhr.send(formData);
    });
}

document.getElementById('uploadBtn').onclick = async function() {
    if (fileList.length === 0) return alert("请选择文件");
    const btn = this;
    const progressWrap = document.querySelector('.progress-bar');
    const progressFill = document.getElementById('progress');
    btn.disabled = true;
    btn.innerText = "上传中...";
    progressWrap.style.display = "block";
    let successCount = 0;
    let failCount = 0;
    for (let i = 0; i < fileList.length; i++) {
        btn.innerText = "上传中... (" + (i + 1) + "/" + fileList.length + ")";
        progressFill.style.width = "0";
        try {
            await uploadSingleFile(fileList[i], progressFill);
            successCount++;
        } catch (err) {
            failCount++;
            console.error(fileList[i].name + " 上传失败:", err);
        }
    }
    btn.disabled = false;
    btn.innerText = "确认上传";
    progressWrap.style.display = "none";
    fileList = [];
    fileInput.value = "";
    updateTip();
    let msg = "上传完成：" + successCount + " 个成功";
    if (failCount > 0) msg += "，" + failCount + " 个失败";
    alert(msg);
    location.reload();
};

document.querySelectorAll('.btn-icon.delete').forEach(function(btn) {
    btn.addEventListener('click', function(e) {
        e.preventDefault();
        if (!confirm('确定要删除这个文件吗？')) return;
        fetch(this.href).then(function(r) {
            if (r.ok) location.reload();
            else alert('删除失败');
        }).catch(function() {
            alert('删除出错');
        });
    });
});

function openClipboardModal() {
    document.getElementById('clipboardModal').classList.add('active');
    document.getElementById('clipboardText').focus();
}

function closeClipboardModal() {
    document.getElementById('clipboardModal').classList.remove('active');
    document.getElementById('clipboardText').value = '';
}

async function sendToClipboard() {
    const text = document.getElementById('clipboardText').value;
    if (!text.trim()) {
        alert('请输入内容');
        return;
    }
    try {
        const base64Text = btoa(unescape(encodeURIComponent(text)));
        const response = await fetch('/clipboard', {
            method: 'POST',
            headers: { 'X-Clipboard-Text': base64Text }
        });
        if (response.ok) {
            alert('已发送');
            closeClipboardModal();
        } else {
            alert('发送失败');
        }
    } catch (e) {
        alert('网络错误');
    }
}

document.getElementById('clipboardModal').addEventListener('click', function(e) {
    if (e.target === this) closeClipboardModal();
});
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun addToBookshelfAndOpen(bookFile: File) {
        try {
            val fileName = bookFile.name
            val bookName = fileName.substringBeforeLast(".")
            val bookUrl = if (bookFile.absolutePath.startsWith("/")) {
                "file://${bookFile.absolutePath}"
            } else {
                bookFile.absolutePath
            }
            val book = Book(
                bookUrl = bookUrl,
                tocUrl = "",
                origin = BookType.localTag,
                originName = fileName,
                name = bookName,
                author = "本地",
                type = BookType.local,
                latestChapterTime = System.currentTimeMillis(),
                lastCheckTime = System.currentTimeMillis(),
                durChapterTime = System.currentTimeMillis()
            )
            book.save()
            Toast.makeText(this, "《$bookName》已添加到书架", Toast.LENGTH_SHORT).show()
            startActivity<ReadBookActivity> {
                putExtra("bookUrl", bookUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "添加到书架失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToBookshelf(bookFile: File) {
        try {
            val fileName = bookFile.name
            val bookName = fileName.substringBeforeLast(".")
            val bookUrl = if (bookFile.absolutePath.startsWith("/")) {
                "file://${bookFile.absolutePath}"
            } else {
                bookFile.absolutePath
            }
            val book = Book(
                bookUrl = bookUrl,
                tocUrl = "",
                origin = BookType.localTag,
                originName = fileName,
                name = bookName,
                author = "本地",
                type = BookType.local,
                latestChapterTime = System.currentTimeMillis(),
                lastCheckTime = System.currentTimeMillis(),
                durChapterTime = System.currentTimeMillis()
            )
            book.save()
            Toast.makeText(this, "《$bookName》已添加到书架", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "添加到书架失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSvgPlusDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(2f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.2f
                canvas.drawLine(pad, h / 2f, w - pad, h / 2f, paint)
                canvas.drawLine(w / 2f, pad, w / 2f, h - pad, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun createSvgArrowLeftDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(3f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.25f
                canvas.drawLine(w - pad, h / 2f, pad + w * 0.1f, h / 2f, paint)
                canvas.drawLine(pad + w * 0.1f, h / 2f, pad + w * 0.3f, pad, paint)
                canvas.drawLine(pad + w * 0.1f, h / 2f, pad + w * 0.3f, h - pad, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun createSvgArrowRightDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(3f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.25f
                canvas.drawLine(pad, h / 2f, w - pad - w * 0.1f, h / 2f, paint)
                canvas.drawLine(w - pad - w * 0.1f, h / 2f, w - pad - w * 0.3f, pad, paint)
                canvas.drawLine(w - pad - w * 0.1f, h / 2f, w - pad - w * 0.3f, h - pad, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun createSvgSearchDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(2.5f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cx = w * 0.4f
                val cy = h * 0.4f
                val r = w * 0.22f
                canvas.drawCircle(cx, cy, r, paint)
                val angle = Math.PI / 4
                val handleStartX = cx + r * kotlin.math.cos(angle).toFloat()
                val handleStartY = cy + r * kotlin.math.sin(angle).toFloat()
                val handleEndX = cx + r * 1.8f * kotlin.math.cos(angle).toFloat()
                val handleEndY = cy + r * 1.8f * kotlin.math.sin(angle).toFloat()
                canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun createSvgCloseDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(2f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.25f
                canvas.drawLine(pad, pad, w - pad, h - pad, paint)
                canvas.drawLine(w - pad, pad, pad, h - pad, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun createSvgDownloadDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = dp(2f).toFloat()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.2f
                canvas.drawLine(w / 2f, pad, w / 2f, h - pad - w * 0.1f, paint)
                canvas.drawLine(w / 2f - w * 0.15f, h - pad - w * 0.25f, w / 2f, h - pad - w * 0.1f, paint)
                canvas.drawLine(w / 2f + w * 0.15f, h - pad - w * 0.25f, w / 2f, h - pad - w * 0.1f, paint)
                canvas.drawLine(pad, h - pad, w - pad, h - pad, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes.toDouble() / gb)
            bytes >= mb -> String.format("%.2f MB", bytes.toDouble() / mb)
            bytes >= kb -> String.format("%.2f KB", bytes.toDouble() / kb)
            else -> "$bytes B"
        }
    }

    private fun startServer() {
        webServer = object : NanoHTTPD(port) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                return try {
                    when {
                        session.uri == "/" -> {
                            NanoHTTPD.newFixedLengthResponse(htmlCache).apply {
                                mimeType = "text/html;charset=UTF-8"
                            }
                        }
                        session.method == NanoHTTPD.Method.POST && session.uri == "/upload" -> {
                            val files = HashMap<String, String>()
                            session.parseBody(files)
                            val tempFilePath = files["file"]
                                ?: return NanoHTTPD.newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.BAD_REQUEST,
                                    "text/plain",
                                    "no file"
                                )
                            val tempFile = File(tempFilePath)
                            if (!tempFile.exists()) {
                                return NanoHTTPD.newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.BAD_REQUEST,
                                    "text/plain",
                                    "temp file not found: $tempFilePath"
                                )
                            }
                            val fileName = try {
                                val base64Name = session.headers["x-file-name"] ?: session.headers["X-File-Name"] ?: "unknown"
                                val decodedBytes = Base64.decode(base64Name, Base64.DEFAULT)
                                String(decodedBytes, Charsets.UTF_8)
                            } catch (e: Exception) {
                                val rawName = session.parameters["file"]?.firstOrNull() ?: "unknown"
                                try {
                                    URLDecoder.decode(rawName, "UTF-8")
                                } catch (_: Exception) {
                                    rawName
                                }
                            }
                            val safeName = getUniqueFileName(sanitizeFileName(fileName))
                            val targetFile = File(saveDir, safeName)
                            tempFile.copyTo(targetFile, overwrite = true)
                            if (safeName.lowercase().endsWith(".txt") || safeName.lowercase().endsWith(".epub")) {
                                mainHandler.post { addToBookshelf(targetFile) }
                            }
                            mainHandler.post { refreshListAndHtml() }
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
                        }
                        session.method == NanoHTTPD.Method.POST && session.uri == "/clipboard" -> {
                            val base64Text = session.headers["x-clipboard-text"] ?: session.headers["X-Clipboard-Text"] ?: ""
                            val text = try {
                                val decodedBytes = Base64.decode(base64Text, Base64.DEFAULT)
                                String(decodedBytes, Charsets.UTF_8)
                            } catch (e: Exception) {
                                ""
                            }
                            if (text.isNotEmpty()) {
                                mainHandler.post { copyToClipboard(text) }
                                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{\"success\":true}")
                            } else {
                                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"success\":false,\"error\":\"empty text\"}")
                            }
                        }
                        session.uri.startsWith("/download") -> {
                            handleDownload(session)
                        }
                        session.uri.startsWith("/delete") -> {
                            handleDelete(session)
                        }
                        else -> {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "err: ${e.message}"
                    )
                }
            }

            private fun handleDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                return try {
                    val fileName = decodeFileNameFromQuery(session)
                    val file = File(saveDir, fileName)
                    if (!file.exists() || !file.isFile) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "file not found")
                    }
                    val response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/octet-stream",
                        file.inputStream(),
                        file.length()
                    )
                    val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                    response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"; filename*=UTF-8''$encodedName")
                    response
                } catch (e: Exception) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "err: ${e.message}")
                }
            }

            private fun handleDelete(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                return try {
                    val fileName = decodeFileNameFromQuery(session)
                    val file = File(saveDir, fileName)
                    if (file.exists()) {
                        file.delete()
                        mainHandler.post { refreshListAndHtml() }
                    }
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
                } catch (e: Exception) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "err: ${e.message}")
                }
            }

            private fun decodeFileNameFromQuery(session: NanoHTTPD.IHTTPSession): String {
                val query = session.queryParameterString ?: ""
                val fParam = query.split("&")
                    .find { it.startsWith("f=") }
                    ?.substringAfter("f=")
                    ?: ""
                return try {
                    val decodedBytes = Base64.decode(Uri.decode(fParam), Base64.DEFAULT)
                    String(decodedBytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    Uri.decode(fParam)
                }
            }
        }
        webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    private fun keepWakeLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val ext = fileName.lowercase()
        return ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg") ||
               ext.endsWith(".gif") || ext.endsWith(".webp") || ext.endsWith(".bmp")
    }

    private fun showImagePreview(file: File) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val imgWidth = (screenWidth * 0.85).toInt()
        val imgHeight = (screenHeight * 0.85).toInt()
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(imgWidth, imgHeight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageURI(Uri.fromFile(file))
        }
        AlertDialog.Builder(this)
            .setView(imageView)
            .setCancelable(true)
            .show()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(imgWidth + dp(8), imgHeight + dp(8))
                window?.setGravity(Gravity.CENTER)
            }
    }

    private fun installApk(apk: File) {
        AlertDialog.Builder(this)
            .setTitle("安装应用")
            .setMessage("确定要安装此应用吗？")
            .setPositiveButton("确定") { _, _ ->
                performInstallApk(apk)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performInstallApk(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", apk)
        } else {
            Uri.fromFile(apk)
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "安装失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isSwipePaging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwipePaging) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 2) {
                        isSwipePaging = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipePaging) {
                    val dx = event.x - touchStartX
                    isSwipePaging = false
                    if (dx < 0) {
                        if (currentPage < totalPages - 1) {
                            currentPage++
                            if (isRemoteMode) renderRemotePage() else renderPage()
                        }
                    } else {
                        if (currentPage > 0) {
                            currentPage--
                            if (isRemoteMode) renderRemotePage() else renderPage()
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isSwipePaging = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // ========== 释放 WakeLock ==========
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        webServer?.stop()
        try {
            socketServer?.close()
        } catch (_: Exception) {}
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver)
        }
    }
}
