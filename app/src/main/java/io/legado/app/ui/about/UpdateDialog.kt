package io.legado.app.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogUpdateBinding
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class UpdateDialog() : BaseDialogFragment(R.layout.dialog_update) {

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("apkUrl", updateInfo.downloadUrl)
            putString("apkName", updateInfo.fileName)
            putString("zipUrl", updateInfo.zipUrl)
            putString("zipName", updateInfo.zipName)
        }
    }

    val binding by viewBinding(DialogUpdateBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = arguments?.getString("newVersion")

        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            toastOnUi("没有数据")
            dismiss()
            return
        }

        // 设置更新日志，确保显示完整
        binding.textView.post {
            Markwon.builder(requireContext())
                .usePlugin(GlideImagesPlugin.create(requireContext()))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(TablePlugin.create(requireContext()))
                .build()
                .setMarkdown(binding.textView, updateBody)
        }

        binding.btnDownloadZip.setOnClickListener {
            val url = arguments?.getString("zipUrl")
            val name = arguments?.getString("zipName")
            if (!url.isNullOrBlank() && !name.isNullOrBlank()) {
                startDownload(url, name, false)
            } else {
                toastOnUi("没有模块下载地址")
            }
        }

        binding.btnDownloadApk.setOnClickListener {
            val url = arguments?.getString("apkUrl")
            val name = arguments?.getString("apkName")
            if (!url.isNullOrBlank() && !name.isNullOrBlank()) {
                startDownload(url, name, true)
            } else {
                toastOnUi("没有应用下载地址")
            }
        }
    }

    private fun startDownload(url: String, fileName: String, isApk: Boolean) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvProgress.visibility = View.VISIBLE
                binding.btnDownloadZip.isEnabled = false
                binding.btnDownloadApk.isEnabled = false

                val file = withContext(Dispatchers.IO) {
                    downloadFile(url, fileName)
                }

                if (isApk) {
                    installApk(file)
                    toastOnUi("下载完成，开始安装")
                } else {
                    toastOnUi("下载完成: /E-ink/$fileName")
                }
                dismiss()
            } catch (e: Exception) {
                toastOnUi("下载失败: ${e.message}")
                binding.progressBar.visibility = View.GONE
                binding.tvProgress.visibility = View.GONE
                binding.btnDownloadZip.isEnabled = true
                binding.btnDownloadApk.isEnabled = true
            }
        }
    }

    private suspend fun downloadFile(url: String, fileName: String): File {
        return withContext(Dispatchers.IO) {
            val einkDir = File(Environment.getExternalStorageDirectory(), "E-ink")
            if (!einkDir.exists()) {
                einkDir.mkdirs()
            }

            val file = File(einkDir, fileName)

            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("下载失败: ${response.code}")
                }

                val body = response.body ?: throw Exception("下载失败: 空响应")
                val totalLength = body.contentLength()

                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read

                            if (totalLength > 0) {
                                val progress = (downloaded * 100 / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = progress
                                    binding.tvProgress.text = "$progress%"
                                }
                            }
                        }
                    }
                }
            }
            file
        }
    }

    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
