package com.tvatlas.player.cloud

import com.tvatlas.core.cloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

 data class CloudFolder(val id: String, val name: String)
 data class CloudLibraryState(val shares: List<CloudShare> = emptyList(), val selected: CloudShare? = null,
    val folders: List<CloudFolder> = emptyList(), val files: List<CloudFile> = emptyList(), val nextOffset: Int? = null,
    val loading: Boolean = false, val error: String? = null)

/** Selection generation and cancellation prevent stale directory requests replacing a newer selection. */
class CloudLibraryController(private val scope: CoroutineScope, private val storeFactory: () -> CloudShareStorage,
    private val client: CloudDirectoryReader, private val beforeRead: () -> Unit) {
    private val _state = MutableStateFlow(CloudLibraryState())
    val state = _state.asStateFlow()
    private var readJob: Job? = null
    private var generation = 0L
    private data class DirectoryKey(val share: CloudShare, val path: List<String>)
    private data class CachedDirectory(val files: List<CloudFile>, val nextOffset: Int?)
    // Memory only: never persist share paths or temporary service tokens in history.
    private val history = LinkedHashMap<DirectoryKey, CachedDirectory>()
    private fun invalidate(key: String) { history.keys.removeAll { it.share.key == key } }
    private fun remember(key: DirectoryKey, directory: CachedDirectory) {
        history.remove(key)
        if (directory.files.size <= 1000) history[key] = directory
        while (history.size > 12 || history.values.sumOf { it.files.size } > 3000) {
            history.remove(history.keys.first())
        }
    }
    private val mutations = kotlinx.coroutines.sync.Mutex()
    private var store: CloudShareStorage? = null
    init { scope.launch {
        mutations.lock()
        try {
            val loaded = withContext(Dispatchers.IO) { storeFactory().also { store = it } }
            _state.value = _state.value.copy(shares = loaded.items.value,
                error = if (loaded.damaged) "本机分享记录无法解密，请重新导入；未改动直播数据" else null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.value = _state.value.copy(error = "无法读取本机分享记录") }
        finally { mutations.unlock() }
    } }
    fun importShare(text: String, code: String, name: String, done: () -> Unit) {
        scope.launch {
            mutations.lock()
            try {
                val share = CloudShareParser.parse(text, code, name)
                val storage = withContext(Dispatchers.IO) {
                    (store ?: storeFactory().also { store = it }).also { it.save(share) }
                }
                invalidate(share.key)
                _state.value = _state.value.copy(shares = storage.items.value, error = null); done()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: IllegalArgumentException) { _state.value = _state.value.copy(error = e.message ?: "分享格式无效") }
            catch (_: Exception) { _state.value = _state.value.copy(error = "分享记录未能保存，请重试") }
            finally { mutations.unlock() }
        }
    }
    fun remove(share: CloudShare) {
        scope.launch {
            mutations.lock()
            try {
                val storage = withContext(Dispatchers.IO) { (store ?: storeFactory().also { store = it }).also { it.remove(share.key) } }
                invalidate(share.key)
                if (_state.value.selected?.key == share.key) close()
                _state.value = _state.value.copy(shares = storage.items.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(error = "未能移除本机记录，请重试") }
            finally { mutations.unlock() }
        }
    }
    fun open(share: CloudShare) = load(share, listOf(CloudFolder("0", "分享根目录")), useHistory = true)
    fun enter(file: CloudFile) {
        val current = _state.value
        if (!file.directory || current.loading) return
        if (current.folders.size >= 64) { _state.value = current.copy(error = "目录层级过深，请返回上一级"); return }
        current.selected?.let { load(it, current.folders + CloudFolder(file.id, file.name), useHistory = true) }
    }
    fun back() {
        val current = _state.value
        if (current.folders.size <= 1) close() else current.selected?.let { load(it, current.folders.dropLast(1), useHistory = true) }
    }
    fun refresh() { val current = _state.value; current.selected?.let { load(it, current.folders) } }
    fun more() { val s = _state.value; if (!s.loading) s.selected?.let { share -> s.nextOffset?.let { load(share, s.folders, it) } } }
    fun cancelReading(message: String) { generation++; readJob?.cancel(); _state.value = _state.value.copy(loading = false, error = message) }
    fun close() { generation++; readJob?.cancel(); _state.value = _state.value.copy(selected = null, folders = emptyList(), files = emptyList(), nextOffset = null, loading = false, error = null) }
    private fun load(share: CloudShare, path: List<CloudFolder>, offset: Int = 0, useHistory: Boolean = false) {
        readJob?.cancel(); val token = ++generation
        val key = DirectoryKey(share, path.map { it.id })
        val cached = if (useHistory) history[key] else null
        if (cached != null) {
            _state.value = _state.value.copy(selected = share, folders = path, files = cached.files,
                nextOffset = cached.nextOffset, loading = false, error = null)
            return
        }
        val current = _state.value
        val sameDirectory = current.selected == share && current.folders == path
        val oldFiles = if (offset > 0) current.files else emptyList()
        _state.value = current.copy(selected = share, folders = path,
            files = if (sameDirectory) current.files else emptyList(),
            nextOffset = if (sameDirectory) current.nextOffset else null, loading = true, error = null)
        readJob = scope.launch {
            try {
                beforeRead()
                val directory = client.list(share, path.lastOrNull()?.id ?: "0", offset)
                if (token == generation) {
                    val files = (oldFiles + directory.files).distinctBy { it.directory to it.id }.episodeOrder()
                    remember(key, CachedDirectory(files, directory.nextOffset))
                    _state.value = _state.value.copy(files = files, nextOffset = directory.nextOffset, loading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (token == generation) _state.value = _state.value.copy(loading = false, error = if (e is CloudException || e is IllegalArgumentException) e.message else "目录读取失败，请重试") }
        }
    }
}
