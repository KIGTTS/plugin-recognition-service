package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.RecognitionEvent

/** Never diff hypotheses into old sentences. Keep a bounded display tail without retaining audio/history. */
internal class TranscriptAssembler(private val limit: Int) {
    init { require(limit in 256..32_000) }
    private var committed = ""
    private var partial = ""
    private var sequence = 0L
    fun partial(text: String): RecognitionEvent.Transcript {
        partial = tail(text.trim(), limit)
        return snapshot()
    }
    fun finalize(text: String): RecognitionEvent.Transcript? {
        val final = text.trim()
        val hadPartial = partial.isNotEmpty()
        partial = ""
        if (final.isEmpty()) return if (hadPartial) snapshot() else null
        committed = tail(join(committed, final), limit)
        sequence++
        return snapshot(head(final, 32_000))
    }
    private fun snapshot(final: String? = null): RecognitionEvent.Transcript {
        // Reserve room for the live utterance: combined IPC/display text is bounded, not each half.
        val separator = if (partial.isNotEmpty() && committed.isNotEmpty()) 1 else 0
        val prefix = tail(committed, (limit - partial.length - separator).coerceAtLeast(0))
        return RecognitionEvent.Transcript(prefix, partial, sequence, final)
    }
    companion object {
        private fun tail(text: String, size: Int): String = text.takeLast(size).dropWhile { it.isLowSurrogate() }
        private fun head(text: String, size: Int): String = text.take(size).dropLastWhile { it.isHighSurrogate() }
        fun join(prefix: String, suffix: String) = when {
            prefix.isBlank() -> suffix
            suffix.isBlank() -> prefix
            else -> "$prefix\n$suffix"
        }
    }
}
