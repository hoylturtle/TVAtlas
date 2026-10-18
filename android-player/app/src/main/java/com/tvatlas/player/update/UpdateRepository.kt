package com.tvatlas.player.update

import com.tvatlas.player.network.readCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import com.tvatlas.player.debug.DebugLog
import java.io.ByteArrayOutputStream
import java.io.IOException

class UpdateException(message: String, val httpStatus: Int? = null, val protocol: Protocol? = null) : IOException(message)
const val REPOSITORY_URL = "https://github.com/hoylturtle/TVAtlas"
const val RELEASE_CERTIFICATE_SHA256 = "7de8ec4d38a6c7854981b73fe34161c17d97fd93555358bd11f46fecde4ab878"
data class PublishedUpdate(val versionCode: Int, val versionName: String, val minSdk: Int, val notes: String,
    val downloadUrl: String, val apkSha256: String = "", val apkSize: Long = 0, val coreVersion: String = "", val checkComplete: Boolean = true, val assetApiUrl: String = "")
data class UpdateState(val checking: Boolean = false, val checked: Boolean = false, val release: PublishedUpdate? = null,
    val error: String? = null, val downloading: Boolean = false, val downloadProgress: Int = 0, val apkReady: Boolean = false)

/** Public verified stable releases. Preview builds do not connect to this update channel. */
class UpdateRepository(
    private val client: OkHttpClient,
    private val api: String = "https://api.github.com/repos/hoylturtle/TVAtlas",
    private val web: String = REPOSITORY_URL,
    private val signingCertificateSha256: String = RELEASE_CERTIFICATE_SHA256,
    private val log: DebugLog? = null,
    private val includePrereleases: Boolean = false,
) {
    fun latest(): PublishedUpdate = runBlocking { latestAsync() }
    suspend fun latestAsync(): PublishedUpdate = withContext(Dispatchers.IO) {
        // Inspect recent stable assets so one incomplete release does not erase a verified update.
        val releases = read("$api/releases?per_page=10", stage = "release-list") as? JsonArray
            ?: throw UpdateException("更新信息无效，请稍后重试")
        val deadline = System.nanoTime() + 20_000_000_000L
        var newest: PublishedUpdate? = null
        var complete = true
        for (element in releases) {
            if (System.nanoTime() > deadline) { complete = false; break }
            if (element !is JsonObject) continue
            val release = element
            if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) continue
            val tag = release.text("tag_name")
            if (!includePrereleases && (release["prerelease"]?.jsonPrimitive?.booleanOrNull != false || tag.contains("-"))) continue
            if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-beta\\.[0-9]+)?"))) continue
            val assets = release["assets"] as? JsonArray ?: continue
            val uploaded = assets.mapNotNull { it as? JsonObject }.filter {
                it.text("state") == "uploaded" && (it["size"]?.jsonPrimitive?.longOrNull ?: 0) > 0
            }
            val prefix = "$web/releases/download/$tag/"
            val metadata = uploaded.firstOrNull {
                it.text("name") == "release-info.json" && it.text("browser_download_url") == prefix + "release-info.json"
            } ?: continue
            val metadataId = metadata["id"]?.jsonPrimitive?.longOrNull
            val info = try {
                try { read(metadata.text("browser_download_url"), optional = metadataId == null || metadataId <= 0, stage = "release-metadata") as? JsonObject }
                catch (error: UpdateException) {
                    val id = metadataId
                    if (error.httpStatus !in listOf(400, 403, 404, 429, 502, 503) || id == null || id <= 0) throw error
                    log?.event("WARN", "UPDATE_ASSET", "metadata browser endpoint failed; use public asset API")
                    read("$api/releases/assets/$id", stage = "metadata-asset-api", accept = "application/octet-stream") as? JsonObject
                }
            } catch (error: IOException) {
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
            val apkId = apk["id"]?.jsonPrimitive?.longOrNull
            val verified = candidate.copy(assetApiUrl = if (apkId != null && apkId > 0) "$api/releases/assets/$apkId" else "")
            if (newest == null || verified.versionCode > newest.versionCode) newest = verified
        }
        newest?.copy(checkComplete = complete) ?: throw UpdateException("暂未找到公开的安装包，请打开仓库查看 Releases")
    }
    private suspend fun read(url: String, optional: Boolean = false, stage: String, accept: String = "application/json"): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "TVAtlas-UpdateChecker")
            .header("Accept", if (stage == "release-list") "application/vnd.github+json" else accept)
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        log?.event("INFO", "UPDATE_HTTP", "stage=$stage start host=${request.url.host}")
        try { return client.newCall(request).readCancellable { response ->
            log?.event("INFO", "UPDATE_HTTP", "stage=$stage host=${response.request.url.host} status=${response.code} protocol=${response.protocol}")
            if (optional && response.code == 404) return@readCancellable null
            if (response.code == 403 || response.code == 429) throw UpdateException("更新服务请求受限（${response.request.url.host}，HTTP ${response.code}），请稍后重试", response.code, response.protocol)
            if (!response.isSuccessful) throw UpdateException("${if (stage == "release-list") "更新列表" else "版本信息"}请求失败（${response.request.url.host}，HTTP ${response.code}）", response.code, response.protocol)
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
        } } catch (cancelled: kotlinx.coroutines.CancellationException) {
            log?.event("INFO", "UPDATE_HTTP", "stage=$stage host=${request.url.host} cancelled=true")
            throw cancelled
        } catch (error: IOException) {
            log?.event("ERROR", "UPDATE_HTTP", "stage=$stage host=${request.url.host} exception=${error.javaClass.simpleName}")
            throw error
        }
    }
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}
