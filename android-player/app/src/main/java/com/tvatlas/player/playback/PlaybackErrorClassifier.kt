package com.tvatlas.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.tvatlas.core.player.*
import java.io.IOException
import java.net.SocketException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackErrorClassifier {
    fun httpStatus(error: Throwable): Int? = causes(error)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    fun transportFailure(error: Throwable): Boolean {
        val http = httpStatus(error)
        if (http != null) return http == 408 || http == 429 || http in 500..599
        return causes(error).any { it is SSLException || it is SocketException || it is InterruptedIOException ||
            it is UnknownHostException || it is HttpDataSource.HttpDataSourceException }
    }
    fun classify(error: PlaybackException, recentSegmentFailure: Boolean): PlaybackFailure {
        return classifyCode(error.errorCode, error, recentSegmentFailure)
    }
    internal fun classifyCode(code: Int, error: Throwable, recentSegmentFailure: Boolean): PlaybackFailure {
        val http = httpStatus(error)
        val kind = when {
            code in 6000..6999 -> when {
                transportFailure(error) -> FailureKind.NETWORK
                http == 401 || http == 403 -> FailureKind.HTTP
                else -> FailureKind.DRM
            }
            code in 4000..4999 -> FailureKind.DECODER
            code in listOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) -> FailureKind.MANIFEST
            http != null -> FailureKind.HTTP
            recentSegmentFailure -> FailureKind.SEGMENT
            else -> FailureKind.NETWORK
        }
        return PlaybackFailure(kind, http)
    }
    private fun causes(error: Throwable) = generateSequence(error) { it.cause }.take(16)
}
