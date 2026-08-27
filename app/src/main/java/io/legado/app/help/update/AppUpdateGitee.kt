package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitee : AppUpdate.AppUpdateInterface {

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl = "https://gitee.com/api/v5/repos/lyj09x/legado/releases/latest"
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return GSON.fromJsonObject<GiteeRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val allAssets = getLatestRelease()
            allAssets
                .firstOrNull { it.versionName > AppConst.appInfo.versionName }
                ?.let {
                    val zipAsset = allAssets.firstOrNull { asset ->
                        asset.name.endsWith(".zip") && !asset.name.contains("Source")
                    }
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name,
                        zipAsset?.downloadUrl ?: "",
                        zipAsset?.name ?: ""
                    )
                }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }
}
