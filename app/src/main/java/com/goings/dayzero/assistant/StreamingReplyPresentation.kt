package com.goings.dayzero.assistant

internal const val STREAMING_REPLY_FRAME_DELAY_MS = 30L

/** Shared pacing used only for text received from a successful SSE reply_delta event. */
internal fun streamingReplyStep(remainingChars: Int): Int = when {
    remainingChars > 140 -> 3
    remainingChars > 56 -> 2
    else -> 1
}
