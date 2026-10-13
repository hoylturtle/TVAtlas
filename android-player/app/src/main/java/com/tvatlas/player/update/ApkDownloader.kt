package com.tvatlas.player.update

import com.tvatlas.player.network.readCancellable

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.tvatlas.player.debug.DebugLog
import java.io.File
import java.security.MessageDigest

const val MAX_UPDATE_APK_BYTES = 160L * 1024 * 1024

class ApkDownloader(private val client: OkHttpClient, private val log: DebugLog? = null,
    private val assetApiPrefix: String = "https://api.github.com/repos/hoylturtle/TVAtlas/releases/assets/") {
    suspend fun download(release: PublishedUpdate, destination: File, progress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (release.apkSize !in 1..MAX_UPDATE_APK_BYTES || !release.apkSha256.matches(Regex("[a-f0-9]{64}")))
            throw UpdateException("更新校验信息无效，请重新检查更新")
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            suspend fun fetch(url: String): File {
                val request = Request.Builder().url(url).header("User-Agent", "TVAtlas-UpdateChecker")
                    .header("Accept", "application/octet-stream").build()
                log?.event("INFO", "UPDATE_DOWNLOAD_HTTP", "start host=${request.url.host}")
                return client.newCall(request).readCancellable { response ->
                    log?.event("INFO", "UPDATE_DOWNLOAD_HTTP", "host=${response.request.url.host} status=${response.code} protocol=${response.protocol}")
                    if (!response.isSuccessful) throw UpdateException("下载安装包失败（${response.request.url.host}，HTTP ${response.code}）", response.code, response.protocol)
                    val body = response.body ?: throw UpdateException("安装包内容为空")
                    if (body.contentLength() > release.apkSize) throw UpdateException("安装包大小与发布信息不符")
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    var previous = -1
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > release.apkSize) throw UpdateException("安装包超过预期大小")
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                            val value = (size * 100 / release.apkSize).toInt()
                            if (value != previous) { progress(value); previous = value }
                        }
                    } }
                    val digest = hash.digest().joinToString("") { "%02x".format(it) }
                    if (size != release.apkSize || digest != release.apkSha256)
                        throw UpdateException("安装包校验失败，请重新下载")
                    if (!partial.renameTo(destination)) throw UpdateException("无法保存安装包，请检查存储空间")
                    destination
                }
            }
            try { fetch(release.downloadUrl) }
            catch (error: UpdateException) {
                val id = release.assetApiUrl.removePrefix(assetApiPrefix)
                if (error.httpStatus !in listOf(400, 403, 404, 429, 502, 503) ||
                    !release.assetApiUrl.startsWith(assetApiPrefix) || !id.matches(Regex("[1-9][0-9]*"))) throw error
                log?.event("WARN", "UPDATE_ASSET", "APK browser endpoint failed; use public asset API")
                fetch(release.assetApiUrl)
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }
}
