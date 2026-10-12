package com.tvatlas.player.update

import com.tvatlas.player.network.readCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

class UpdateException(message: String) : IOException(message)
const val REPOSITORY_URL = "https://github.com/hoylturtle/TVAtlas"
const val RELEASE_CERTIFICATE_SHA256 = "7de8ec4d38a6c7854981b73fe34161c17d97fd93555358bd11f46fecde4ab878"
data class PublishedUpdate(val versionCode: Int, val versionName: String, val minSdk: Int, val notes: String,
    val downloadUrl: String, val apkSha256: String = "", val apkSize: Long = 0, val coreVersion: String = "", val checkComplete: Boolean = true)
data class UpdateState(val checking: Boolean = false, val checked: Boolean = false, val release: PublishedUpdate? = null,
    val error: String? = null, val downloading: Boolean = false, val downloadProgress: Int = 0, val apkReady: Boolean = false)

/** Public Release assets only; previews are included for this testing build. */
class UpdateRepository(
    private val client: OkHttpClient,
    private val api: String = "https://api.github.com/repos/hoylturtle/TVAtlas",
    private val web: String = REPOSITORY_URL,
    private val signingCertificateSha256: String = RELEASE_CERTIFICATE_SHA256,
) {
    fun latest(): PublishedUpdate = runBlocking { latestAsync() }
    suspend fun latestAsync(): PublishedUpdate = withContext(Dispatchers.IO) {
        // /latest excludes prereleases. This app deliberately follows the public testing channel.
        val releases = read("$api/releases?per_page=10") as? JsonArray
            ?: throw UpdateException("更新信息无效，请稍后重试")
        val deadline = System.nanoTime() + 20_000_000_000L
        var newest: PublishedUpdate? = null
        var complete = true
        for (element in releases) {
            if (System.nanoTime() > deadline) { complete = false; break }
            val release = element as? JsonObject ?: continue
            if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) continue
            val tag = release.text("tag_name")
            if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-beta\\.[0-9]+)?"))) continue
            val assets = release["assets"] as? JsonArray ?: continue
            val uploaded = assets.mapNotNull { it as? JsonObject }.filter {
                it.text("state") == "uploaded" && (it["size"]?.jsonPrimitive?.longOrNull ?: 0) > 0
            }
            val prefix = "$web/releases/download/$tag/"
            val metadata = uploaded.firstOrNull {
                it.text("name") == "release-info.json" && it.text("browser_download_url") == prefix + "release-info.json"
            } ?: continue
            val info = try { read(metadata.text("browser_download_url"), optional = true) as? JsonObject }
                catch (error: IOException) {
                    if (newest == null) throw error
                    complete = false
                    break
                }
            if (info == null) { complete = false; continue }
            val code = info["versionCode"]?.jsonPrimitive?.intOrNull ?: continue
            val version = info.text("versionName")
            val sdk = info["minSdk"]?.jsonPrimitive?.intOrNull ?: continue
            val commit = info.text("commit")
            if (code <= 0 || sdk < 23 || !version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) continue
            if (info.text("releaseTag") != tag || tag.substringBefore("-beta.") != "v$version") continue
            if (!commit.matches(Regex("[a-f0-9]{40}")) || release.text("target_commitish") != commit) continue
            if (info.text("signingCertificateSha256").lowercase() != signingCertificateSha256) continue
            if (!info.text("apkSha256").matches(Regex("[a-f0-9]{64}"))) continue
            val apkName = "TVAtlas-Player-v$version-release.apk"
            val apk = uploaded.firstOrNull {
                it.text("name") == apkName && it.text("browser_download_url") == prefix + apkName
            } ?: continue
            val size = info["apkSize"]?.jsonPrimitive?.longOrNull ?: continue
            if (size !in 1..MAX_UPDATE_APK_BYTES || apk["size"]?.jsonPrimitive?.longOrNull != size) continue
            val candidate = PublishedUpdate(code, version, sdk, info.text("notes").take(1000), apk.text("browser_download_url"), info.text("apkSha256"), size, info.text("coreVersion").takeIf { it.matches(Regex("v?[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")) } ?: "")
            if (newest == null || candidate.versionCode > newest.versionCode) newest = candidate
        }
        newest?.copy(checkComplete = complete) ?: throw UpdateException("暂未找到公开的测试版安装包，请打开仓库查看 Releases")
    }
    private suspend fun read(url: String, optional: Boolean = false): JsonElement? {
        return client.newCall(Request.Builder().url(url).header("User-Agent", "TVAtlas-UpdateChecker")
            .header("Accept", "application/json").build()).readCancellable { response ->
            if (optional && response.code == 404) return@readCancellable null
            if (response.code == 403 || response.code == 429) throw UpdateException("更新服务请求受限，请稍后重试")
            if (!response.isSuccessful) throw UpdateException("更新服务暂不可用（HTTP ${response.code}）")
            val body = response.body ?: throw UpdateException("更新信息为空")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 1024 * 1024) throw UpdateException("更新信息过大")
                    output.write(buffer, 0, count)
                }
            }
            try { Json.parseToJsonElement(output.toString("UTF-8")) }
            catch (_: Exception) { throw UpdateException("更新信息无效，请稍后重试") }
        }
    }
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}
