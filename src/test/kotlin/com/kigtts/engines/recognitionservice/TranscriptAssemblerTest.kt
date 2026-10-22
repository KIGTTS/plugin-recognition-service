package com.kigtts.engines.recognitionservice

import org.junit.Assert.*
import org.junit.Test

class TranscriptAssemblerTest {
    @Test fun partialRevisionOnlyReplacesTrailingHypothesis() {
        val transcript = TranscriptAssembler(256)
        assertEquals("第一句", transcript.finalize("第一句")!!.committed)
        assertEquals("第一句", transcript.partial("没听清").committed)
        assertEquals("改好了", transcript.partial("改好了").partial)
        val final = transcript.finalize("第二句")!!
        assertEquals("第一句\n第二句", final.committed); assertEquals("", final.partial)
        assertEquals(2L, final.sequence); assertEquals("第二句", final.finalized)
    }
    @Test fun repeatedFinalsAreDistinctUtterancesAndBlankFinalClearsHypothesisWithoutAddingSequence() {
        val transcript = TranscriptAssembler(256)
        transcript.finalize("好"); assertEquals("好\n好", transcript.finalize("好")!!.committed)
        transcript.partial("也许")
        val clear = transcript.finalize(" ")!!
        assertEquals("好\n好", clear.committed); assertEquals("", clear.partial)
        assertEquals(2L, clear.sequence); assertNull(clear.finalized); assertNull(transcript.finalize(""))
    }
    @Test fun displayBudgetIsCombinedAndUtf16CutsNeverLeaveHalfEmoji() {
        val transcript = TranscriptAssembler(257)
        transcript.finalize("😀".repeat(200))
        val partial = transcript.partial("😀".repeat(80))
        assertTrue(partial.committed.length + partial.partial.length + 1 <= 257)
        assertFalse(partial.committed.first().isLowSurrogate()); assertFalse(partial.partial.first().isLowSurrogate())
        assertFalse(partial.committed.last().isHighSurrogate()); assertFalse(partial.partial.last().isHighSurrogate())
        val large = transcript.partial("😀".repeat(200))
        assertEquals("", large.committed); assertEquals(256, large.partial.length)
    }
    @Test fun shortDisplayBudgetDoesNotTruncateTheFinalizedSentenceForSendingOrReading() {
        val sentence = "完整句".repeat(200)
        val final = TranscriptAssembler(256).finalize(sentence)!!
        assertEquals(256, final.committed.length); assertEquals(sentence, final.finalized)
        val huge = TranscriptAssembler(256).finalize("😀".repeat(16_001))!!
        val finalized = requireNotNull(huge.finalized)
        assertEquals(32_000, finalized.length); assertFalse(finalized.last().isHighSurrogate())
    }
}
