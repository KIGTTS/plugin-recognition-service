package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Frame-based endpointing for batch-only engines. Capture can continue while a sentence is sent. */
internal fun batchSpeechSegments(audio: Flow<PcmAudio>, format: AudioFormat, maxSeconds: Int): Flow<PcmAudio> = flow {
    val frameBytes = format.sampleRate / 50 * format.channels * 2
    val maximum = minOf(15, maxSeconds) * 50
    val frame = ByteArray(frameBytes)
    val energy = DoubleArray(format.channels)
    val speech = ByteArrayOutputStream()
    val preRoll = ArrayDeque<ByteArray>()
    var filled = 0
    var voiced = 0
    var onset = 0
    var silence = 0
    var frames = 0
    var floor = 80.0
    suspend fun finish() {
        if (voiced >= 6) {
            val bytes = speech.toByteArray()
            val trim = (silence - 7).coerceAtLeast(0) * frameBytes
            emit(PcmAudio(if (trim == 0) bytes else bytes.copyOf(bytes.size - trim), format.sampleRate, format.channels))
        }
        speech.reset(); preRoll.clear(); voiced = 0; silence = 0; frames = 0; onset = 0
    }
    suspend fun accept(size: Int) {
        energy.fill(0.0)
        var p = 0
        while (p < size) for (channel in energy.indices) {
            val sample = ((frame[p].toInt() and 255) or (frame[p + 1].toInt() shl 8)).toDouble()
            energy[channel] += sample * sample
            p += 2
        }
        val rms = sqrt(energy.max() / (size / (format.channels * 2)))
        val speaking = rms >= maxOf(if (frames == 0) 300.0 else 190.0, floor * if (frames == 0) 3.0 else 1.9)
        if (frames == 0) {
            if (!speaking) floor = (floor * .94 + rms * .06).coerceIn(35.0, 1800.0)
            preRoll.addLast(frame.copyOf(size))
            while (preRoll.size > 10) preRoll.removeFirst()
            onset = if (speaking) onset + 1 else 0
            if (onset < 3) return
            preRoll.forEach { speech.write(it) }
            frames = preRoll.size; voiced = onset; preRoll.clear()
        } else {
            speech.write(frame, 0, size)
            frames++
            if (speaking) { voiced++; silence = 0 } else if (size == frameBytes) silence++
        }
        if (silence >= 30 || frames >= maximum) finish()
    }
    audio.collect { chunk ->
        if (chunk.sampleRate != format.sampleRate || chunk.channels != format.channels)
            throw EngineException(EngineError.UNSUPPORTED_FORMAT, "录音格式与识别引擎不匹配")
        var offset = 0
        while (offset < chunk.bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(frameBytes - filled, chunk.bytes.size - offset)
            chunk.bytes.copyInto(frame, filled, offset, offset + count)
            offset += count; filled += count
            if (filled == frameBytes) { accept(filled); filled = 0 }
        }
    }
    if (filled > 0) accept(filled)
    finish()
}.buffer(4)
