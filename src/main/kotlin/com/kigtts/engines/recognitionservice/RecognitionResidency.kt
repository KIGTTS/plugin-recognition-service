package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A recording pins its provider; the idle countdown starts only after its finalizer. */
internal class RecognitionResidency(
    private val broker: RecognitionBroker,
    private val selected: () -> String?,
    private val resident: () -> Boolean,
    private val idleSeconds: () -> Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var loaded: String? = null
    private var active = 0
    private var timer: Job? = null
    private var closed = false
    suspend fun begin(id: String) = mutex.withLock {
        check(!closed)
        check(active == 0 || loaded == id) { "请先停止当前识别" }
        timer?.cancel(); timer = null
        if (loaded != id) {
            release()
            broker.control(id, AsrControl(AsrControlOp.PREPARE))
            loaded = id
        }
        active++
    }
    suspend fun end() = withContext(NonCancellable) {
        mutex.withLock {
            check(active > 0); active--
            if (active == 0) {
                if (closed || loaded != selected()) release() else schedule()
            }
        }
    }
    suspend fun changed() = mutex.withLock {
        timer?.cancel(); timer = null
        if (active > 0 || closed) return@withLock
        val id = selected()
        if (loaded != id) release()
        if (resident() && id != null && loaded == null) {
            broker.control(id, AsrControl(AsrControlOp.PREPARE)); loaded = id
        }
        schedule()
    }
    private fun schedule() {
        timer?.cancel(); timer = null
        if (loaded == null || resident() || active > 0) return
        timer = scope.launch {
            delay(idleSeconds().coerceIn(5, 120) * 1000L)
            mutex.withLock { if (active == 0 && !resident()) { timer = null; release() } }
        }
    }
    private suspend fun release() {
        val id = loaded ?: return
        loaded = null
        try { broker.control(id, AsrControl(AsrControlOp.RELEASE)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A disabled or removed provider already released its resources. */ }
    }
    suspend fun close() {
        mutex.withLock {
            closed = true; timer?.cancel(); timer = null
            if (active == 0) release()
        }
        scope.cancel()
    }
}
