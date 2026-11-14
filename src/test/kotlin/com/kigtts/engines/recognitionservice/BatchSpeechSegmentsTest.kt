package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchSpeechSegmentsTest {
    private val format = AudioFormat()
    private fun signal(frames: Int, level: Int) = ByteArray(frames * 640) {
        if (it % 2 == 0) level.toByte() else (level shr 8).toByte()
    }

    @Test fun separatesOnPauseBeforeEofAndKeepsTheNextSentence() = runTest {
        val first = CompletableDeferred<Unit>()
        val sound = signal(15, 2300)
        val audio = flow {
            for (chunk in (signal(27, 0) + sound + signal(35, 0)).asList().chunked(150))
                emit(PcmAudio(chunk.toByteArray()))
            first.await()
            emit(PcmAudio(sound))
        }
        val lengths = mutableListOf<Int>()
        withTimeout(2_000) {
            batchSpeechSegments(audio, format, 15).collect {
                lengths += it.bytes.size
                first.complete(Unit)
            }
        }
        assertEquals(listOf((7 + 15 + 7) * 640, (5 + 15) * 640), lengths)
    }

    @Test fun microphoneCapsApplyAndQuietOrIsolatedClicksAreNotSent() = runTest {
        val results = batchSpeechSegments(flowOf(PcmAudio(signal(3, 2500) + signal(100, 0) + signal(110, 2500))),
            format, 1).toList()
        assertEquals(3, results.size)
        assertTrue(results.all { it.bytes.size in 1..32_000 })
    }

    @Test fun cancellingDiscardsIncompleteSpeechInsteadOfFlushingIt() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            batchSpeechSegments(flow {
                emit(PcmAudio(signal(15, 2500)))
                waiting.complete(Unit)
                awaitCancellation()
            }, format, 2).collect { fail("Cancelled phrase was sent") }
        }
        waiting.await()
        job.cancelAndJoin()
    }
}
