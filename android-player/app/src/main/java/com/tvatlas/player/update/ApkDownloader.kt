package com.tvatlas.player.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

const val MAX_UPDATE_APK_BYTES = 160L * 1024 * 1024

class ApkDownloader(private val client: OkHttpClient) {
    suspend fun download(release: PublishedUpdate, destination: File, progress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (release.apkSize !in 1..MAX_UPDATE_APK_BYTES || !release.apkSha256.matches(Regex("[a-f0-9]{64}")))
            throw UpdateException("更新校验信息无效，请重新检查更新")
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            client.newCall(Request.Builder().url(release.downloadUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("下载安装包失败（HTTP ${response.code}）")
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
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }
}
