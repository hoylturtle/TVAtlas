package com.tvatlas.player.source

import com.tvatlas.core.player.PlaybackFailure
import java.io.IOException

/** Controlled source rejection is different from a broken proxy transport. */
class SourceResolutionException(message: String, val failure: PlaybackFailure, cause: Throwable? = null) : IOException(message, cause)
