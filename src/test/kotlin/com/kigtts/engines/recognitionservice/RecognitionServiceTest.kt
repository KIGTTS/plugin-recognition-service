package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognitionServiceTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("recognition-service-test").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private class Broker : RecognitionBroker {
        var catalog = listOf(AsrProvider("test.segmented", "离线分段", AsrInfo(streaming = StreamingKind.SEGMENTED)),
            AsrProvider("test.online", "在线", AsrInfo(), settingsPages = listOf(AsrSettingsPage("packs", "识别包"))))
        val calls = mutableListOf<String>(); var cancelled = false; var hold = false
        var events: List<RecognitionEvent>? = null
        override fun providers() = catalog
        override suspend fun recognize(pluginId: String, audio: PcmAudio, config: RecognitionConfig): RecognitionResult {
            calls += pluginId; return RecognitionResult("$pluginId:${config.language}:${audio.bytes.size}")
        }
        override fun recognizeStream(pluginId: String, audio: Flow<PcmAudio>, config: RecognitionConfig) = flow {
            calls += pluginId
            try {
                events?.let { scripted -> scripted.forEach { emit(it) }; return@flow }
                emit(RecognitionEvent.Started)
                audio.collect { emit(RecognitionEvent.Partial("$pluginId:${it.bytes.size}")) }
                if (hold) awaitCancellation()
                emit(RecognitionEvent.Final(RecognitionResult("$pluginId:final")))
            } finally { cancelled = true }
        }
    }
    private suspend fun plugin(broker: Broker) = RecognitionServicePlugin().also { plugin ->
        plugin.attach(object : PluginEnvironment {
            override val storage = PluginStorage(root); override val assets = File(root, "assets")
            override val recognitionBroker = broker
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("Service never downloads models")
        })
    }
    @Test fun settingsPageKeepsEnableSelectionAndProviderPagesWithoutDuplicatingEngineStatus() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker)
        val page = plugin.page("engine/test.online", PluginContext("", ""))!!
        assertTrue(page.blocks.isEmpty())
        val items = page.management!!.items
        assertEquals(ManagementToggle(true, "toggle/test.online"), items.first().toggle)
        assertTrue(items[1].selected)
        assertEquals("settings/test.online/packs", items.last().primaryAction)
        val manager = plugin.page("manager", PluginContext("", "", recognitionServiceId = plugin.descriptor.id))!!
        assertEquals(listOf("聆听字幕", "语音输入"), manager.blocks.filterIsInstance<PageBlock.Group>().map { it.title })
        assertFalse(manager.blocks.any { it is PageBlock.Actions })
        broker.catalog = broker.catalog.map { if (it.pluginId == "test.online") it.copy(enabled = false) else it }
        val disabled = plugin.page("engine/test.online", PluginContext("", ""))!!.management!!.items
        assertEquals(2, disabled.size); assertFalse(disabled.first().toggle!!.checked)
    }
    @Test fun serviceAccumulatesTranscriptAndRoutesBatchToSeparateProvider() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker)
        assertEquals(AsrRole.SERVICE, plugin.asr.info.role); assertEquals(StreamingKind.INCREMENTAL, plugin.asr.info.streaming)
        val events = plugin.asr.recognizeStream(flowOf(PcmAudio(byteArrayOf(1, 0)))).toList()
        assertEquals(listOf(RecognitionEvent.Started,
            RecognitionEvent.Transcript("", "test.online:2", 0),
            RecognitionEvent.Transcript("test.online:final", "", 1, "test.online:final")), events)
        assertEquals("test.online:en:2", plugin.asr.recognize(PcmAudio(byteArrayOf(1, 0)), RecognitionConfig("en")).text)
        assertEquals(listOf("test.online", "test.online"), broker.calls)
    }
    @Test fun explicitChoiceSurvivesRestartAndNeverFallsBackWhenDisabled() = runBlocking<Unit> {
        val broker = Broker(); val plugin = plugin(broker)
        assertEquals(PluginOutcome.RecognitionRoutingChanged, plugin.execute("select/test.segmented", PluginContext("", "")))
        assertEquals(StreamingKind.SEGMENTED, plugin.asr.info.streaming)
        val reopened = plugin(broker); assertEquals(StreamingKind.SEGMENTED, reopened.asr.info.streaming)
        broker.catalog = broker.catalog.map { if (it.pluginId == "test.segmented") it.copy(enabled = false) else it }
        assertEquals(EngineAvailability.NEEDS_RESOURCES, reopened.asr.info.availability)
        try { reopened.asr.recognize(PcmAudio(byteArrayOf(1, 0))); fail("Silently switched engine") }
        catch (expected: EngineException) { assertEquals(EngineError.NOT_CONFIGURED, expected.code) }
        assertTrue(broker.calls.isEmpty())
    }
    @Test fun routingIsColdPinnedForRecordingAndCancellationReachesProvider() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker); broker.hold = true
        val flow = plugin.asr.recognizeStream(flowOf(PcmAudio(byteArrayOf(1, 0))))
        plugin.execute("select/test.segmented", PluginContext("", ""))
        withTimeout(2_000) { flow.first { it is RecognitionEvent.Transcript && it.partial.isNotEmpty() } }
        assertEquals("test.segmented", broker.calls.single()); assertTrue(broker.cancelled)
        broker.hold = false; assertTrue(plugin.asr.recognize(PcmAudio(byteArrayOf(1, 0))).text.startsWith("test.segmented"))
    }
    @Test fun managerOrdersEnginesAndOpensTheProvidersOwnModelPage() = runBlocking<Unit> {
        val broker = Broker(); val plugin = plugin(broker); val page = plugin.page("manager", PluginContext("", ""))!!.management!!
        val ids = page.items.map { it.id }.reversed()
        plugin.execute("reorder", PluginContext("", "", inputs = mapOf("order" to PluginJson.encodeToString(ids))))
        assertEquals(ids, plugin(broker).page("manager", PluginContext("", ""))!!.management!!.items.map { it.id })
        assertEquals(PluginOutcome.OpenPluginPage("test.online", "packs"), plugin.execute("settings/test.online/packs", PluginContext("", "")))
        assertEquals(PluginOutcome.SetPluginEnabled("test.online", false), plugin.execute("toggle/test.online", PluginContext("", "")))
        assertEquals(PluginOutcome.SelectRecognitionService("service.speech-recognition"), plugin.execute("activate", PluginContext("", "")))
        assertTrue(File(root, "config/recognition-service.json").isFile); assertFalse(File(root, "models").exists())
    }
    @Test fun unavailableEnginesBlockInputAndStreamKindIsNeverUpgradedByFacade() = runBlocking<Unit> {
        val broker = Broker(); broker.catalog = listOf(AsrProvider("test.batch", "完整录音", AsrInfo(streaming = StreamingKind.NONE)))
        val plugin = plugin(broker); assertEquals(StreamingKind.NONE, plugin.asr.info.streaming)
        try { plugin.asr.recognizeStream(emptyFlow()).toList(); fail("Invented streaming capability") }
        catch (expected: EngineException) { assertEquals(EngineError.UNSUPPORTED_FORMAT, expected.code) }
        broker.catalog = emptyList(); assertEquals(EngineAvailability.NEEDS_RESOURCES, plugin.asr.info.availability)
        assertTrue(broker.calls.isEmpty())
    }
    @Test fun selectingDisabledProviderRequestsHostEnableWithoutInliningEngine() = runBlocking<Unit> {
        val broker = Broker(); broker.catalog = broker.catalog.map { it.copy(enabled = false) }; val plugin = plugin(broker)
        assertEquals(PluginOutcome.SetPluginEnabled("test.online", true), plugin.execute("select/test.online", PluginContext("", "")))
        assertEquals(EngineAvailability.NEEDS_RESOURCES, plugin.asr.info.availability)
        broker.catalog = broker.catalog.map { if (it.pluginId == "test.online") it.copy(enabled = true) else it }
        assertEquals(StreamingKind.INCREMENTAL, plugin.asr.info.streaming)
    }
    @Test fun serviceOwnsListeningAndVoiceGestureAndDoesNotDuplicateAnotherSelectedService() = runBlocking {
        val plugin = plugin(Broker()); val current = PluginContext("", "", recognitionServiceId = plugin.descriptor.id)
        assertEquals("开始聆听", plugin.contributions(current).actions.first().label)
        assertEquals("关闭聆听", plugin.contributions(current.copy(listeningPanelVisible = true)).actions.first().label)
        assertEquals("input", plugin.contributions(current).gestures.single().id)
        val other = plugin.contributions(current.copy(recognitionServiceId = "other.service"))
        assertTrue(other.actions.isEmpty()); assertTrue(other.gestures.isEmpty()); assertEquals(1, other.settingsEntries.size)
        val caption = (plugin.execute("listen", current) as PluginOutcome.StartRecognition).session
        assertEquals(RecognitionPurpose.CAPTIONS, caption.purpose); assertFalse(caption.readFinalized)
        assertEquals(PluginOutcome.EndRecognition(), plugin.execute("listen", current.copy(listeningPanelVisible = true)))
        assertEquals(PluginOutcome.EndRecognition(), plugin.execute("close", current))
        val floating = current.copy(listening = true, listeningPanelVisible = false)
        assertEquals("关闭聆听", plugin.contributions(floating).actions.first().label)
        assertEquals(PluginOutcome.EndRecognition(), plugin.execute("listen", floating))
    }
    @Test fun continuousInputDisablesAutomaticReadThenOnlyFinalizedTextCanBeReadAndLongPressStops() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker); val context = PluginContext("", "")
        val manual = (plugin.execute("input", context) as PluginOutcome.StartRecognition).session
        assertEquals(RecognitionPurpose.VOICE_INPUT, manual.purpose); assertFalse(manual.continuous)
        plugin.execute("continuous-input", context)
        val continuous = (plugin(broker).execute("input", context) as PluginOutcome.StartRecognition).session
        assertTrue(continuous.continuous); assertTrue(continuous.disableAutomaticRead); assertTrue(continuous.readFinalized)
        assertEquals(PluginOutcome.EndRecognition(), plugin.execute("input", context.copy(voiceInputActive = true, continuousRecognition = true)))
        assertEquals(PluginOutcome.EndRecognition(), plugin.execute("continuous", context.copy(continuousRecognition = true)))
        plugin.execute("voice-input", context)
        assertTrue(plugin(broker).contributions(context).gestures.isEmpty())
        assertEquals(PluginOutcome.RefreshUi, plugin.execute("input", context))
    }
    @Test fun outputChoicePersistsAndSessionRejectsUnavailableOrBatchOnlyContinuous() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker); val context = PluginContext("", "")
        plugin.execute("output/BOTH", context)
        assertEquals(CaptionOutput.BOTH, (plugin(broker).execute("listen", context) as PluginOutcome.StartRecognition).session.output)
        broker.catalog = listOf(AsrProvider("test.batch", "录音", AsrInfo(streaming = StreamingKind.NONE)))
        assertTrue(plugin.execute("listen", context) is PluginOutcome.Message)
        assertTrue(plugin.execute("continuous", context) is PluginOutcome.Message)
        assertTrue(plugin.execute("input", context) is PluginOutcome.StartRecognition)
        broker.catalog = emptyList()
        assertTrue(plugin.execute("input", context) is PluginOutcome.Message)
    }
    @Test fun longSessionPreservesRepeatedSentencesAndHypothesesNeverRewritePreviousFinals() = runBlocking {
        val broker = Broker(); val plugin = plugin(broker)
        broker.events = buildList {
            add(RecognitionEvent.Started)
            repeat(10_000) {
                add(RecognitionEvent.Partial("可能${it}")); add(RecognitionEvent.Partial("修改${it}"))
                add(RecognitionEvent.Final(RecognitionResult("同一句")))
            }
        }
        var finals = 0L; var previous = ""; var largest = 0
        plugin.asr.recognizeStream(emptyFlow(), RecognitionConfig(maxDisplayCharacters = 256)).collect { event ->
            if (event is RecognitionEvent.Transcript) {
                largest = maxOf(largest, event.committed.length + event.partial.length)
                if (event.finalized == null) assertTrue(previous.endsWith(event.committed))
                else { finals++; assertEquals("同一句", event.finalized); previous = event.committed }
                assertEquals(finals, event.sequence)
            }
        }
        assertEquals(10_000L, finals); assertTrue(largest <= 256)
        assertTrue(previous.endsWith("同一句\n同一句")); assertFalse(previous.contains("修改"))
    }
    @Test fun cancellationNeverTurnsUnconfirmedHypothesisIntoFinal() = runBlocking {
        val broker = Broker(); broker.hold = true; val plugin = plugin(broker)
        val seen = mutableListOf<RecognitionEvent>()
        plugin.asr.recognizeStream(flowOf(PcmAudio(byteArrayOf(1, 0))))
            .onEach { seen += it }.first { it is RecognitionEvent.Transcript }
        assertTrue(broker.cancelled)
        assertTrue(seen.filterIsInstance<RecognitionEvent.Transcript>().none { it.finalized != null })
    }
}
