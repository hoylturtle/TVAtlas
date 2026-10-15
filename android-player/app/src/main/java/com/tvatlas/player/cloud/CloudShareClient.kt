package com.tvatlas.player.cloud

import com.tvatlas.core.cloud.*
import com.tvatlas.player.network.readCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Read-only share listing. No transfer/save/delete endpoints, scripts or third-party parsers. */
class CloudShareClient(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun list(share: CloudShare, parent: String = "0", offset: Int = 0): CloudDirectory {
        require(offset in 0..200000)
        return try {
            when (share.provider) {
                CloudProvider.PAN115 -> list115(share, parent, offset)
                CloudProvider.QUARK -> listQuark(share, parent, offset)
                CloudProvider.BAIDU -> throw CloudException(CloudFailure.UNSUPPORTED, "百度分享目录尚未接入，可先打开官方页面查看；不代表链接失效")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: CloudException) { throw known }
        catch (_: Exception) { throw CloudException(CloudFailure.NETWORK, "未能读取网盘目录，请检查网络或稍后重试；原分享记录已保留") }
    }
    private suspend fun request(url: String, body: JsonObject? = null): JsonObject {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Referer", if (url.toHttpUrl().host.endsWith("quark.cn")) "https://pan.quark.cn/" else "https://115.com/")
        if (body != null) req.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(req.build()).readCancellable { response ->
            if (response.code == 401) throw CloudException(CloudFailure.NEED_LOGIN, "网盘要求登录，当前尚未建立账号授权")
            if (response.code == 403 || response.code == 429) throw CloudException(CloudFailure.LIMITED, "平台暂不允许当前请求，可能需要登录、验证或稍后重试")
            if (!response.isSuccessful) throw CloudException(CloudFailure.NETWORK, "目录请求返回 HTTP ${response.code}；不能据此判断分享失效")
            val stream = requireNotNull(response.body).byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                if (out.size() + n > 2 * 1024 * 1024) throw CloudException(CloudFailure.LIMITED, "目录响应过大，请缩小文件夹范围")
                out.write(buffer, 0, n)
            }
            json.parseToJsonElement(out.toString("UTF-8")).jsonObject
        }
    }
    private suspend fun list115(share: CloudShare, parent: String, offset: Int): CloudDirectory {
        val url = "https://webapi.115.com/share/snap".toHttpUrl().newBuilder()
            .addQueryParameter("share_code", share.shareId).addQueryParameter("receive_code", share.code)
            .addQueryParameter("cid", parent).addQueryParameter("offset", offset.toString()).addQueryParameter("limit", "100").build()
        return parse115(request(url.toString()), offset)
    }
    private suspend fun listQuark(share: CloudShare, parent: String, offset: Int): CloudDirectory {
        val token = request("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc", buildJsonObject {
            put("pwd_id", share.shareId); put("passcode", share.code)
        })
        val data = token["data"] as? JsonObject
        val stoken = data?.get("stoken")?.jsonPrimitive?.contentOrNull
        if (stoken.isNullOrBlank()) throw CloudException(CloudFailure.LIMITED, "夸克未允许读取该分享，请检查提取码、登录或平台验证要求")
        val page = offset / 100 + 1
        val url = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail".toHttpUrl().newBuilder()
            .addQueryParameter("pr", "ucpro").addQueryParameter("fr", "pc").addQueryParameter("pwd_id", share.shareId)
            .addQueryParameter("stoken", stoken).addQueryParameter("pdir_fid", parent).addQueryParameter("_page", page.toString())
            .addQueryParameter("_size", "100").addQueryParameter("_fetch_total", "1").build()
        return parseQuark(request(url.toString()), offset)
    }
    companion object {
        private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun parse115(root: JsonObject, offset: Int): CloudDirectory {
            if ((root["state"] as? JsonPrimitive)?.booleanOrNull != true) throw CloudException(CloudFailure.LIMITED, "115 未允许读取目录，请检查访问码、登录或平台验证要求")
            val data = root["data"] as? JsonObject ?: throw CloudException(CloudFailure.NETWORK, "115 返回了无法识别的目录结构")
            val rows = data["list"] as? JsonArray ?: throw CloudException(CloudFailure.NETWORK, "115 返回了无法识别的文件列表")
            val files = rows.mapNotNull { value ->
                val o = value as? JsonObject ?: return@mapNotNull null
                val directory = o.text("fid").isBlank(); val id = o.text(if (directory) "cid" else "fid"); val name = o.text("n")
                if (id.isBlank() || name.isBlank()) null else CloudFile(id, name.take(512), directory, o.text("s").toLongOrNull() ?: 0)
            }
            val total = data.text("count").toIntOrNull()
            return CloudDirectory(files, (offset + rows.size).takeIf { rows.isNotEmpty() && if (total == null) rows.size == 100 else it < total })
        }
        fun parseQuark(root: JsonObject, offset: Int): CloudDirectory {
            if ((root["code"] as? JsonPrimitive)?.intOrNull != 0) throw CloudException(CloudFailure.LIMITED, "夸克未允许读取目录，请检查提取码、登录或平台验证要求")
            val data = root["data"] as? JsonObject ?: throw CloudException(CloudFailure.NETWORK, "夸克返回了无法识别的目录结构")
            val rows = data["list"] as? JsonArray ?: throw CloudException(CloudFailure.NETWORK, "夸克返回了无法识别的文件列表")
            val files = rows.mapNotNull { value ->
                val o = value as? JsonObject ?: return@mapNotNull null
                val id = o.text("fid"); val name = o.text("file_name")
                if (id.isBlank() || name.isBlank()) null else CloudFile(id, name.take(512), (o["dir"] as? JsonPrimitive)?.booleanOrNull == true, o.text("size").toLongOrNull() ?: 0)
            }
            val total = (root["metadata"] as? JsonObject)?.text("_total")?.toIntOrNull()
            return CloudDirectory(files, (offset + rows.size).takeIf { rows.isNotEmpty() && if (total == null) rows.size == 100 else it < total })
        }
    }
}
