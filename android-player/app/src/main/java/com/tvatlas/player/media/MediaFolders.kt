package com.tvatlas.player.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.tvatlas.core.model.stableId
import com.tvatlas.player.storage.CredentialVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Proxy
import java.util.concurrent.TimeUnit

@Serializable data class MediaFolder(val id: String, val name: String, val uri: String, val webDav: Boolean = false,
    val username: String = "", val password: String = "") {
    override fun toString() = "MediaFolder(id=$id, webDav=$webDav)"
}
data class MediaEntry(val name: String, val uri: String, val directory: Boolean, val size: Long = 0)
data class MediaFolderState(val roots: List<MediaFolder> = emptyList(), val selected: MediaFolder? = null,
    val trail: List<MediaEntry> = emptyList(), val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = false, val error: String? = null)

/** Lazy, read-only directory access. Credentials and folder addresses never enter backups or logs. */
class MediaFolders(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(MediaFolderState())
    val state = _state.asStateFlow()
    private val vault by lazy { CredentialVault(context, "media_folders", "tvatlas.media-folders.v1") }
    private var job: Job? = null
    private var generation = 0L
    private var damaged = false
    private val mutations = Mutex()
    private val initialized = scope.launch {
        try {
            val saved = withContext(Dispatchers.IO) { vault.secret("roots")?.let { Json.decodeFromString<List<MediaFolder>>(it) }.orEmpty() }
            _state.value = _state.value.copy(roots = saved.take(64))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { damaged = true; message("文件夹记录无法读取，请保留原数据并重新检查设备存储") }
    }
    fun message(text: String) { _state.value = _state.value.copy(error = text) }
    private suspend fun persist(change: (List<MediaFolder>) -> List<MediaFolder>) = mutations.withLock {
        val roots = change(_state.value.roots)
        check(!damaged)
        require(roots.size <= 64)
        withContext(Dispatchers.IO) { vault.putSecret("roots", Json.encodeToString(roots)) }
        _state.value = _state.value.copy(roots = roots, error = null)
    }
    fun addLocal(uri: Uri) = scope.launch {
        initialized.join()
        try {
            require(DocumentsContract.isTreeUri(uri))
            withContext(Dispatchers.IO) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val name = withContext(Dispatchers.IO) {
                context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } ?: "本地文件夹"
            val folder = MediaFolder(stableId(uri.toString()), name.take(100), uri.toString())
            persist { listOf(folder) + it.filterNot { old -> old.id == folder.id } }
            open(folder)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message("无法保存目录授权，请重新选择 U 盘或本机文件夹") }
    }
    fun addWebDav(name: String, address: String, username: String, password: String, done: () -> Unit) = scope.launch {
        initialized.join()
        try {
            val url = address.trim().toHttpUrlOrNull() ?: error("address")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(username.length <= 256 && password.length <= 1024 && name.trim().isNotBlank())
            val clean = url.newBuilder().apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }.build().toString()
            val folder = MediaFolder(stableId(clean + "\u0000" + username), name.trim().take(100), clean, true, username, password)
            // Verify the specified folder before saving credentials or replacing an existing connection.
            withContext(Dispatchers.IO) { WebDavDirectory.list(folder, clean) }
            persist { listOf(folder) + it.filterNot { old -> old.id == folder.id } }; done(); open(folder)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message("WebDAV 连接失败，请检查目录地址、账号及访问权限") }
    }
    fun remove(folder: MediaFolder) = scope.launch {
        initialized.join()
        try {
            persist { it.filterNot { old -> old.id == folder.id } }
            if (_state.value.selected?.id == folder.id) close()
            if (!folder.webDav) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message("未能移除文件夹记录，请重试") }
    }
    fun open(folder: MediaFolder) {
        val root = try {
            if (folder.webDav) folder.uri else Uri.parse(folder.uri).let {
                DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)).toString()
            }
        } catch (_: Exception) { message("目录记录已失效，请移除后重新添加文件夹"); return }
        load(folder, listOf(MediaEntry(folder.name, root, true)))
    }
    fun enter(entry: MediaEntry) { if (entry.directory && !_state.value.loading) _state.value.selected?.let { load(it, _state.value.trail + entry) } }
    fun back() { val s = _state.value; if (s.trail.size <= 1) close() else s.selected?.let { load(it, s.trail.dropLast(1)) } }
    fun close() { generation++; job?.cancel(); _state.value = _state.value.copy(selected = null, trail = emptyList(), entries = emptyList(), loading = false, error = null) }
    fun pause() { generation++; job?.cancel(); _state.value = _state.value.copy(loading = false) }
    fun refresh() { val s = _state.value; s.selected?.let { load(it, s.trail) } }
    private fun load(folder: MediaFolder, trail: List<MediaEntry>) {
        if (trail.size > 64) { message("目录层级过深，请返回上一级"); return }
        job?.cancel(); val token = ++generation
        _state.value = _state.value.copy(selected = folder, trail = trail, entries = emptyList(), loading = true, error = null)
        job = scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    if (folder.webDav) WebDavDirectory.list(folder, trail.last().uri) else readLocal(folder, trail.last().uri)
                }
                if (token == generation) _state.value = _state.value.copy(entries = entries.sortedWith(compareByDescending<MediaEntry> { it.directory }.thenBy { it.name.lowercase() }), loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) _state.value = _state.value.copy(loading = false,
                error = if (folder.webDav) "NAS 目录暂时无法读取，请检查连接后刷新" else "目录无法读取，可能已拔出 U 盘或授权失效；请重新插入或重新添加文件夹") }
        }
    }
    private suspend fun readLocal(folder: MediaFolder, path: String): List<MediaEntry> {
        val uri = Uri.parse(path); val tree = Uri.parse(folder.uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        return context.contentResolver.query(children, columns, null, null, null)?.use { cursor -> buildList {
            while (cursor.moveToNext() && size < 2000) {
                currentCoroutineContext().ensureActive()
                val name = cursor.getString(1).orEmpty(); val directory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (directory || MediaFileNames.video(name)) add(MediaEntry(name.take(200), DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(), directory, cursor.getLong(3)))
            }
        } } ?: error("directory unavailable")
    }
}

object MediaFileNames {
    fun video(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "webm", "mpg", "mpeg", "flv", "3gp")
}
