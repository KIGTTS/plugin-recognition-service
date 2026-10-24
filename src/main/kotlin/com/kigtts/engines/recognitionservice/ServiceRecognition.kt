package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Cold routing to one provider, pinned for the entire recording; never substitutes an offline result. */
internal class ServiceRecognition(private val settings: RecognitionServiceSettings, private val broker: RecognitionBroker) : AsrEngine {
    @Volatile private var activeService = true
    private var receivedContext = false
    private val changes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun updateActive(value: Boolean) {
        if (receivedContext && activeService == value) return
        receivedContext = true
        activeService = value
        changes.launch {
            try { residency.changed() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Report preparation failures when recognition is requested. */ }
        }
    }
    private val residency = RecognitionResidency(broker,
        { settings.current(broker.providers())?.takeIf { activeService }?.takeIf { it.info.availability == EngineAvailability.READY }?.pluginId },
        { settings.resident }, { settings.idleSeconds })
    suspend fun settingsChanged() = residency.changed()
    override suspend fun close() { changes.cancel(); residency.close() }
    override val info get() = settings.current(broker.providers())?.info?.copy(role = AsrRole.SERVICE)
        ?: AsrInfo(streaming = StreamingKind.NONE, availability = EngineAvailability.NEEDS_RESOURCES, role = AsrRole.SERVICE)
    private fun provider(streaming: Boolean): AsrProvider {
        val selected = settings.current(broker.providers())
            ?: throw EngineException(EngineError.NOT_CONFIGURED, "请在语音识别服务中选用并启用一个识别引擎")
        if (selected.info.availability != EngineAvailability.READY) throw EngineException(EngineError.NOT_CONFIGURED, "当前识别引擎需要先配置模型")
        if (streaming && selected.info.streaming == StreamingKind.NONE || !streaming && !selected.info.batch)
            throw EngineException(EngineError.UNSUPPORTED_FORMAT, "当前引擎不支持此识别方式")
        return selected
    }
    override suspend fun recognize(audio: PcmAudio, config: RecognitionConfig): RecognitionResult {
        val id = provider(false).pluginId
        residency.begin(id)
        return try { broker.recognize(id, audio, config) } finally { residency.end() }
    }
    override fun recognizeStream(audio: Flow<PcmAudio>, config: RecognitionConfig): Flow<RecognitionEvent> = flow {
        val selected = provider(true)
        residency.begin(selected.pluginId)
        try {
        val transcript = TranscriptAssembler(config.maxDisplayCharacters)
        broker.recognizeStream(selected.pluginId, audio, config).collect { event ->
            when (event) {
                RecognitionEvent.Started -> emit(event)
                is RecognitionEvent.Partial -> emit(transcript.partial(event.text))
                is RecognitionEvent.Final -> transcript.finalize(event.result.text)?.let { emit(it) }
                is RecognitionEvent.Transcript -> error("识别引擎不能代替服务拼接字幕")
            }
        }
        } finally { residency.end() }
    }
}
