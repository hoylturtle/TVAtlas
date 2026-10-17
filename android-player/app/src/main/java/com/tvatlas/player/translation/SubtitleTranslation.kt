package com.tvatlas.player.translation

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.*
import android.os.Process
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.tvatlas.core.player.TranslationGate
import com.tvatlas.core.player.RenderedPlaybackProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

internal data class TranslationState(val source: String = "en", val target: String = "zh", val busy: Boolean = false,
    val progress: Float = 0f, val message: String = "尚未测试，翻译未启用", val eligible: Boolean = false,
    val enabled: Boolean = false, val bilingual: Boolean = true, val original: String = "", val translated: String = "",
    val report: String = "", val phase: String = "idle", val downloadedBytes: Long = 0,
    val totalBytes: Long = 0, val elapsedSeconds: Long = 0, val etaSeconds: Long? = null, val waitingDownload: Boolean = false, val modelsReady: Boolean = false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleTranslation(private val context: Context, private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?, private val networkAllowed: () -> Boolean) {
    private val _state = MutableStateFlow(TranslationState())
    val state = _state.asStateFlow()
    private val downloadProgress = ModelDownloadProgress(context)
    private var downloadStarted = 0L
    private var downloadElapsedStart = 0L
    private var initialDownloadBytes = -1L
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val reportFile = File(context.noBackupFilesDir, "translation/admission.json")
    private var engine: Translator? = null
    private var job: Job? = null
    private var translating: Job? = null
    private var watched: ExoPlayer? = null
    private var revision = 0L
    private var approvedAt = 0L
    private var lastCueAt = 0L
    private var pendingCue: String? = null
    private var testRevision = 0L
    private var configurationRevision = 0L
    private var originalParameters: TrackSelectionParameters? = null
    private var monitorAt = 0L
    private var monitorRendered = 0
    private var monitorDropped = 0
    private var slowWindows = 0
    private val listener = object : Player.Listener {
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            if (_state.value.enabled && C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) {
                originalParameters = null
                disable("字幕已关闭，翻译也已暂停")
            }
        }
        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").take(1000)
            cue(text)
        }
    }
    init {
        scope.launch {
            val initialRevision = configurationRevision
            val cached = withContext(Dispatchers.IO) { runCatching { JSONObject(reportFile.readText()) }.getOrNull() }
            if (configurationRevision == initialRevision && cached != null && cached.optString("device") == Build.FINGERPRINT && cached.optInt("policy") == TranslationGate.POLICY &&
                System.currentTimeMillis() - cached.optLong("at") in 0..604800000L && cached.optBoolean("passed")) {
                approvedAt = cached.optLong("at")
                _state.value = _state.value.copy(source = cached.optString("source", "en"), target = cached.optString("target", "zh"),
                    eligible = true, report = cached.optString("report"), message = "本机已通过测试，可手动启用；更换语言需重测")
            }
        }
        scope.launch {
            while (isActive) {
                delay(2000)
                if (_state.value.busy && _state.value.phase == "download") {
                    val snapshot = downloadProgress.read(downloadStarted)
                    val seconds = (SystemClock.elapsedRealtime() - downloadElapsedStart) / 1000
                    if (snapshot != null) {
                        if (initialDownloadBytes < 0) initialDownloadBytes = snapshot.downloaded
                        val rate = if (seconds >= 3) (snapshot.downloaded - initialDownloadBytes).coerceAtLeast(0) / seconds else 0
                        _state.value = _state.value.copy(downloadedBytes = snapshot.downloaded, totalBytes = snapshot.total,
                            elapsedSeconds = seconds, waitingDownload = snapshot.waiting,
                            etaSeconds = if (rate > 0 && snapshot.total > snapshot.downloaded) (snapshot.total - snapshot.downloaded) / rate else null)
                    } else _state.value = _state.value.copy(elapsedSeconds = seconds, etaSeconds = null)
                }
                if (_state.value.enabled) {
                    val counters = watched?.videoDecoderCounters
                    if (counters != null && SystemClock.elapsedRealtime() - monitorAt >= 10000) {
                        counters.ensureUpdated()
                        val frames = counters.renderedOutputBufferCount - monitorRendered
                        val dropped = counters.droppedBufferCount - monitorDropped
                        slowWindows = if (frames > 100 && dropped.toDouble() / (frames + dropped).coerceAtLeast(1) > .05) slowWindows + 1 else 0
                        monitorAt = SystemClock.elapsedRealtime(); monitorRendered = counters.renderedOutputBufferCount; monitorDropped = counters.droppedBufferCount
                        if (slowWindows >= 2) disable("播放掉帧持续偏高，已暂停翻译")
                    }
                    if (preflight() != null) disable("设备资源或温度异常，已暂停翻译")
                    else if (watched?.playbackState == Player.STATE_BUFFERING) {
                        delay(5000)
                        if (_state.value.enabled && watched?.playbackState == Player.STATE_BUFFERING) disable("播放持续缓冲，已暂停翻译")
                    }
                }
            }
        }
    }
    private suspend fun <T> Task<T>.waitResult(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
    private suspend fun memory(): Long = withContext(Dispatchers.IO) { Debug.getPss() * 1024 }
    private fun preflight(): String? {
        val m = ActivityManager.MemoryInfo(); am.getMemoryInfo(m)
        return when {
            am.isLowRamDevice || m.totalMem < 1536L * 1024 * 1024 -> "设备内存偏低，暂不开放本机翻译"
            m.lowMemory || m.availMem < 384L * 1024 * 1024 -> "当前可用内存不足，请关闭其他应用后重试"
            StatFs(context.filesDir.path).availableBytes < 300L * 1024 * 1024 -> "剩余存储不足 300 MiB，不能下载测试模型"
            Build.VERSION.SDK_INT >= 29 && (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "设备温度过高，请冷却后重试"
            else -> null
        }
    }
    fun bind(value: ExoPlayer?) {
        if (watched === value) return
        if (_state.value.phase != "download") cancelTest()
        if (!_state.value.busy) disable("频道已变化，翻译保持关闭")
        else if (_state.value.enabled) disable()
        watched?.removeListener(listener); watched = value; value?.addListener(listener)
    }
    fun language(source: String, target: String) {
        if (source !in LANGUAGES.keys || target !in LANGUAGES.keys || source == target) return
        configurationRevision++; cancelTest(); disable("语言已变化，请重新测试")
        engine?.close(); engine = null; approvedAt = 0
        _state.value = _state.value.copy(source = source, target = target, eligible = false, report = "", modelsReady = false)
    }
    fun bilingual(value: Boolean) { _state.value = _state.value.copy(bilingual = value) }
    private fun translator(): Translator = engine ?: Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(_state.value.source).setTargetLanguage(_state.value.target).build()).also { engine = it }
    fun prepareModels() = runTest(downloadOnly = true)
    fun prepareChineseModels() = runTest(downloadOnly = true, chinesePack = true)
    fun test() = runTest(downloadOnly = false)
    private fun runTest(downloadOnly: Boolean, chinesePack: Boolean = false) {
        if (_state.value.busy) return
        configurationRevision++; disable("准备检测")
        val live = player()
        val reason = preflight()
        if (reason != null) { _state.value = _state.value.copy(message = reason, eligible = false); return }
        downloadStarted = System.currentTimeMillis(); downloadElapsedStart = SystemClock.elapsedRealtime(); initialDownloadBytes = -1
        _state.value = _state.value.copy(busy = true, phase = "download", downloadedBytes = 0, totalBytes = 0, elapsedSeconds = 0, etaSeconds = null,
            eligible = false, report = "", message = "正在准备或下载语言模型；切换页面不会中断", progress = .03f)
        val token = ++testRevision
        job = scope.launch {
            var owned: Translator? = null
            try {
                withContext(Dispatchers.IO) { reportFile.delete() }
                val translator = translator().also { owned = it }
                val pairs = if (chinesePack) listOf("en" to "zh", "ja" to "zh", "ko" to "zh")
                    else listOf(_state.value.source to _state.value.target)
                for ((index, pair) in pairs.withIndex()) {
                    val (source, target) = pair
                    _state.value = _state.value.copy(message = "准备 ${LANGUAGES[source]} → ${LANGUAGES[target]}（${index + 1}/${pairs.size}）；切换页面不会中断")
                    val installed = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).waitResult().map { it.language }.toSet()
                    val cached = setOf(source, target).filter { it != "en" }.all { it in installed }
                    if (!cached) {
                        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        check(networkAllowed() && !connectivity.isActiveNetworkMetered) { "请连接允许使用的非计费网络后下载模型；已下载的模型可离线使用" }
                        val candidate = if (!chinesePack) translator else Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
                        try { withTimeout(300000) { candidate.downloadModelIfNeeded(DownloadConditions.Builder().build()).waitResult() } }
                        finally { if (candidate !== translator) candidate.close() }
                    }
                }
                _state.value = _state.value.copy(modelsReady = true)
                if (downloadOnly) {
                    _state.value = _state.value.copy(phase = "ready", progress = 1f,
                        message = if (chinesePack) "英／日／韩 → 中文模型已准备，可离线使用。请回到直播画面，点击“翻译”进行本机性能测试。"
                            else "本组模型已准备，可离线使用。请回到直播画面，点击“翻译”进行本机性能测试。")
                    return@launch
                }
                if (live == null || player() !== live || !live.isPlaying || live.playbackState != Player.STATE_READY) {
                    _state.value = _state.value.copy(phase = "ready", progress = 1f, message = "模型已准备完成。请播放稳定直播后，再点击开始性能测试。")
                    return@launch
                }
                _state.value = _state.value.copy(phase = "benchmark", message = "模型准备完成，测量原直播基线：15 秒", progress = .1f)
                val baseline = sample(requireNotNull(live), 15000, null)
                _state.value = _state.value.copy(message = "翻译负载测试：30 秒，请勿换台或暂停", progress = .4f)
                val work = sample(live, 30000, translator)
                check(baseline.peakMemory > 0 && work.peakMemory > 0) { "无法测量本机内存占用，测试无效" }
                val latencies = work.latencies.sorted()
                val metrics = TranslationGate.Metrics(max(0, (work.peakMemory - baseline.peakMemory) / (1024 * 1024)),
                    max(0.0, work.cpu - baseline.cpu), latencies.getOrElse((latencies.size * .95).toInt().coerceAtMost(latencies.lastIndex)) { Long.MAX_VALUE },
                    baseline.dropRate, work.dropRate, work.frames, latencies.size, baseline.progress, work.progress, baseline.buffered + work.buffered)
                val rejection = TranslationGate.rejection(metrics)
                val summary = "内存增量 ${metrics.extraMemoryMiB} MiB · CPU 增量 ${"%.1f".format(metrics.extraCpuPercent)}%\n" +
                    "视频帧 ${baseline.frames} → ${work.frames} · 有效播放 ${baseline.progress / 1000}s → ${work.progress / 1000}s\n" +
                    "翻译 P95 ${metrics.p95LatencyMs} ms · 掉帧 ${"%.1f".format(metrics.baselineDropRate * 100)}% → ${"%.1f".format(metrics.workDropRate * 100)}% · 样本 ${metrics.samples}"
                approvedAt = if (rejection == null) System.currentTimeMillis() else 0
                _state.value = _state.value.copy(busy = false, phase = "complete", progress = 1f, eligible = rejection == null, report = summary,
                    message = rejection ?: "本语言组合测试通过，可手动启用（不保证所有频道都适用）")
                val record = JSONObject().put("device", Build.FINGERPRINT).put("policy", TranslationGate.POLICY)
                    .put("source", _state.value.source).put("target", _state.value.target).put("at", approvedAt).put("passed", rejection == null).put("report", summary)
                withContext(Dispatchers.IO) { reportFile.parentFile?.mkdirs(); reportFile.writeText(record.toString()) }
            } catch (timeout: TimeoutCancellationException) {
                _state.value = _state.value.copy(message = "模型下载或翻译超时，测试未通过，请检查网络和设备后重试", eligible = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(message = "测试未通过：${error.message?.take(100) ?: "模型无法下载或执行"}", eligible = false) }
            finally {
                if (token == testRevision) _state.value = _state.value.copy(busy = false, phase = if (_state.value.phase == "download") "idle" else _state.value.phase)
                if (engine === owned) engine = null
                owned?.close()
            }
        }
    }
    private data class Sample(val peakMemory: Long, val cpu: Double, val dropRate: Double, val frames: Int,
        val progress: Long, val buffered: Long, val latencies: List<Long>)
    private suspend fun sample(live: ExoPlayer, duration: Long, translator: Translator?): Sample {
        val started = SystemClock.elapsedRealtime(); val cpuStart = Process.getElapsedCpuTime()
        val counters = live.videoDecoderCounters ?: error("无法获取视频解码统计，请换频道后重测")
        counters.ensureUpdated(); val dropped = counters.droppedBufferCount; val rendered = counters.renderedOutputBufferCount
        val renderedProgress = RenderedPlaybackProgress(started, rendered)
        var peak = memory(); var buffered = 0L; val latency = mutableListOf<Long>(); var n = 0
        while (SystemClock.elapsedRealtime() - started < duration) {
            currentCoroutineContext().ensureActive()
            check(live.videoDecoderCounters === counters) { "播放解码器已变化，测试无效，请稳定播放后重测" }
            check(player() === live && live.isPlaying && live.playWhenReady && live.playbackState != Player.STATE_IDLE && live.playbackState != Player.STATE_ENDED) { "播放已停止或切换，测试无效" }
            preflight()?.let { error(it) }
            val begin = SystemClock.elapsedRealtime()
            counters.ensureUpdated()
            renderedProgress.observe(begin, counters.renderedOutputBufferCount, live.isPlaying && live.playbackState == Player.STATE_READY)
            if (live.playbackState == Player.STATE_BUFFERING) buffered += 500
            if (translator != null && n++ % 2 == 0) {
                val text = SAMPLES.getValue(_state.value.source)[(n / 2) % 3]
                val t = SystemClock.elapsedRealtime()
                withTimeout(5000) { translator.translate(text).waitResult() }
                latency += SystemClock.elapsedRealtime() - t
            }
            peak = max(peak, memory())
            _state.value = _state.value.copy(progress = if (translator == null) .1f + .3f * (SystemClock.elapsedRealtime() - started) / duration else .4f + .6f * (SystemClock.elapsedRealtime() - started) / duration)
            delay((500 - (SystemClock.elapsedRealtime() - begin)).coerceAtLeast(1))
        }
        counters.ensureUpdated()
        renderedProgress.observe(SystemClock.elapsedRealtime(), counters.renderedOutputBufferCount, live.isPlaying && live.playbackState == Player.STATE_READY)
        val frames = (counters.renderedOutputBufferCount - rendered).coerceAtLeast(0)
        val drops = (counters.droppedBufferCount - dropped).coerceAtLeast(0)
        return Sample(peak, 100.0 * (Process.getElapsedCpuTime() - cpuStart) / (SystemClock.elapsedRealtime() - started) / Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            drops.toDouble() / (frames + drops).coerceAtLeast(1), frames, renderedProgress.millis, buffered, latency)
    }
    fun cancelTest() { testRevision++; job?.cancel(); job = null; if (_state.value.busy) _state.value = _state.value.copy(busy = false, phase = "idle", eligible = false, message = "本次测试已取消，翻译未解锁") }
    fun enable() {
        if (!_state.value.eligible || System.currentTimeMillis() - approvedAt !in 0..604800000L) return
        preflight()?.let { _state.value = _state.value.copy(message = it); return }
        val live = player() ?: return
        val candidates = live.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) && group.getTrackFormat(it).sampleMimeType !in
                setOf("application/dvbsubs", "application/pgs", "application/vobsub") }.map { group to it }
        }
        if (candidates.isEmpty()) { _state.value = _state.value.copy(message = "此频道没有可读取的独立字幕；画面内字幕无法翻译"); return }
        fun language(value: String?): String = when (val code = value.orEmpty().substringBefore('-').lowercase()) {
            "eng" -> "en"; "jpn" -> "ja"; "kor" -> "ko"; "chi", "zho" -> "zh"; "und", "" -> ""; else -> code
        }
        val chosen = candidates.firstOrNull { (g, i) -> language(g.getTrackFormat(i).language) == _state.value.source }
            ?: candidates.firstOrNull { (g, i) -> language(g.getTrackFormat(i).language).isBlank() }
        if (chosen == null) { _state.value = _state.value.copy(message = "字幕轨道语言与所选原文不一致，请更改原文语言并重测"); return }
        val (group, index) = chosen
        monitorAt = SystemClock.elapsedRealtime(); slowWindows = 0
        monitorRendered = live.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        monitorDropped = live.videoDecoderCounters?.droppedBufferCount ?: 0
        originalParameters = live.trackSelectionParameters
        live.trackSelectionParameters = live.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
        translator()
        _state.value = _state.value.copy(enabled = true, message = "本机翻译已开启，字幕不上传；译文可能稍有延迟")
    }
    fun disable(reason: String = "翻译已关闭") {
        revision++; pendingCue = null; translating?.cancel(); translating = null
        val wasEnabled = _state.value.enabled
        _state.value = _state.value.copy(enabled = false, original = "", translated = "", message = reason)
        if (wasEnabled) {
            val saved = originalParameters
            if (saved != null) watched?.let { live ->
                val b = live.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT in saved.disabledTrackTypes)
                saved.overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { b.addOverride(it) }
                live.trackSelectionParameters = b.build()
            }
        }
        originalParameters = null
        _state.value = _state.value.copy(enabled = false, original = "", translated = "", message = reason)
        if (!_state.value.busy) { engine?.close(); engine = null }
    }
    private fun cue(text: String) {
        if (!_state.value.enabled || text == _state.value.original) return
        revision++
        pendingCue = text.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(original = text, translated = "")
        if (translating?.isActive == true || pendingCue == null) return
        translating = scope.launch {
            // One inference at a time; only the newest pending cue survives.
            while (_state.value.enabled) {
                val input = pendingCue ?: break
                pendingCue = null
                val token = revision
                delay((250 - (SystemClock.elapsedRealtime() - lastCueAt)).coerceAtLeast(0))
                if (token != revision) continue
                lastCueAt = SystemClock.elapsedRealtime()
                try {
                    val translated = withTimeout(4000) { translator().translate(input).waitResult() }
                    if (token == revision && _state.value.enabled) _state.value = _state.value.copy(translated = translated)
                } catch (cancelled: CancellationException) {
                    if (cancelled is TimeoutCancellationException) disable("翻译过慢，已暂停以保护播放") else throw cancelled
                } catch (_: Exception) { disable("翻译模型不可用，请重新测试") }
            }
        }
    }
    fun removeModels() {
        configurationRevision++; cancelTest(); disable(); _state.value = _state.value.copy(busy = true, progress = 0f, eligible = false, modelsReady = false, message = "正在移除语言模型")
        job = scope.launch {
            try {
                for (code in setOf(_state.value.source, _state.value.target).filter { it != "en" }) RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).waitResult()
                withContext(Dispatchers.IO) { reportFile.delete() }
                _state.value = _state.value.copy(message = "模型已移除，需重新下载测试", report = "")
            } catch (_: Exception) { _state.value = _state.value.copy(message = "移除未完成，请重试") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
    fun close() { cancelTest(); disable(); watched?.removeListener(listener); watched = null; engine?.close() }
    companion object {
        val LANGUAGES = linkedMapOf("en" to "英语", "zh" to "中文", "ja" to "日语", "ko" to "韩语")
        private val SAMPLES = mapOf(
            "en" to listOf("Welcome to the evening news. Here are today's main stories.", "The train will arrive at the station in five minutes.", "Thank you for watching. We will return after a short break."),
            "zh" to listOf("欢迎收看晚间新闻，下面是今天的主要消息。", "列车将在五分钟后抵达车站，请注意安全。", "感谢收看，稍后我们将继续为您报道。"),
            "ja" to listOf("こんばんは。今日の主なニュースをお伝えします。", "電車は五分後に駅に到着する予定です。", "ご覧いただきありがとうございます。まもなく番組を再開します。"),
            "ko" to listOf("안녕하세요. 오늘의 주요 뉴스를 전해드립니다.", "열차가 오 분 후에 역에 도착할 예정입니다.", "시청해 주셔서 감사합니다. 잠시 후에 돌아오겠습니다."))
    }
}
