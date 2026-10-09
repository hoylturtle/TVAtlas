package com.tvatlas.player.update

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

class UpdateException(message: String) : IOException(message)
data class PublishedUpdate(val versionCode: Int, val versionName: String, val minSdk: Int, val notes: String, val downloadUrl: String)
data class UpdateState(val checking: Boolean = false, val checked: Boolean = false, val release: PublishedUpdate? = null, val error: String? = null)

/** Only successful builds with unexpired, version-matched APK artifacts are offered. */
class UpdateRepository(
    private val client: OkHttpClient,
    private val api: String = "https://api.github.com/repos/hoylturtle/TVAtlas",
    private val raw: String = "https://raw.githubusercontent.com/hoylturtle/TVAtlas",
    private val web: String = "https://github.com/hoylturtle/TVAtlas",
) {
    fun latest(): PublishedUpdate {
        val runs = read("$api/actions/workflows/android.yml/runs?status=success&event=push&per_page=5")!!["workflow_runs"]?.jsonArray
            ?: throw UpdateException("更新信息无效，请稍后重试")
        val deadline = System.nanoTime() + 20_000_000_000L
        for (element in runs) {
            if (System.nanoTime() > deadline) break
            val run = element.jsonObject
            if (run.text("status") != "completed" || run.text("conclusion") != "success" || run.text("event") != "push") continue
            val branch = run.text("head_branch")
            if (branch != "main" && !branch.startsWith("codex/tvatlas-player-")) continue
            val sha = run.text("head_sha")
            if (!sha.matches(Regex("[a-f0-9]{40}"))) continue
            val info = read("$raw/$sha/android-player/release-info.json", optional = true) ?: continue
            val code = info["versionCode"]?.jsonPrimitive?.intOrNull ?: continue
            val version = info.text("versionName")
            val sdk = info["minSdk"]?.jsonPrimitive?.intOrNull ?: continue
            if (code <= 0 || sdk < 23 || !version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) continue
            val runId = run["id"]?.jsonPrimitive?.longOrNull ?: continue
            val artifacts = read("$api/actions/runs/$runId/artifacts?per_page=100")!!["artifacts"]?.jsonArray ?: continue
            val artifact = artifacts.map { it.jsonObject }.firstOrNull {
                it.text("name") == "TVAtlas-Player-v$version-debug" && it["expired"]?.jsonPrimitive?.booleanOrNull == false
            } ?: continue
            val id = artifact["id"]?.jsonPrimitive?.longOrNull ?: continue
            if (id <= 0 || runId <= 0) continue
            return PublishedUpdate(code, version, sdk, info.text("notes").take(1000), "$web/actions/runs/$runId/artifacts/$id")
        }
        throw UpdateException("暂未找到可下载的版本，请稍后重试")
    }
    private fun read(url: String, optional: Boolean = false): JsonObject? {
        client.newCall(Request.Builder().url(url).header("User-Agent", "TVAtlas-UpdateChecker")
            .header("Accept", "application/json").build()).execute().use { response ->
            if (optional && response.code == 404) return null
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
            return try { Json.parseToJsonElement(output.toString("UTF-8")).jsonObject }
            catch (_: Exception) { throw UpdateException("更新信息无效，请稍后重试") }
        }
    }
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}
