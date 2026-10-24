package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionResidencyTest {
    private class Broker : RecognitionBroker {
        val calls = mutableListOf<Pair<String, AsrControlOp>>()
        override fun providers() = emptyList<AsrProvider>()
        override suspend fun control(pluginId: String, command: AsrControl): Boolean {
            calls += pluginId to command.op; return true
        }
        override suspend fun recognize(pluginId: String, audio: PcmAudio, config: RecognitionConfig) = RecognitionResult("")
        override fun recognizeStream(pluginId: String, audio: Flow<PcmAudio>, config: RecognitionConfig) = emptyFlow<RecognitionEvent>()
    }
    @Test fun noReleaseDuringRecordingAndFiveSecondDeadlineRestartsOnActivity() = runTest {
        val broker = Broker(); val resources = RecognitionResidency(broker, { "a" }, { false }, { 5 }, backgroundScope)
        resources.begin("a"); advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf("a" to AsrControlOp.PREPARE), broker.calls)
        resources.end(); advanceTimeBy(4_999); runCurrent(); assertEquals(1, broker.calls.size)
        resources.begin("a"); resources.end(); advanceTimeBy(4_999); runCurrent(); assertEquals(1, broker.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals("a" to AsrControlOp.RELEASE, broker.calls.last())
        resources.close()
    }
    @Test fun twoMinuteLimitSwitchAndResidentReleaseAreDeterministic() = runTest {
        val broker = Broker(); var id = "a"; var keep = true; var seconds = 120
        val resources = RecognitionResidency(broker, { id }, { keep }, { seconds }, backgroundScope)
        resources.changed(); advanceTimeBy(240_000); runCurrent(); assertEquals(1, broker.calls.size)
        resources.begin("a"); id = "b"; resources.changed(); assertEquals(1, broker.calls.size)
        resources.end(); assertEquals("a" to AsrControlOp.RELEASE, broker.calls.last())
        resources.changed(); assertEquals("b" to AsrControlOp.PREPARE, broker.calls.last())
        keep = false; resources.changed(); advanceTimeBy(119_999); runCurrent(); assertEquals(3, broker.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals("b" to AsrControlOp.RELEASE, broker.calls.last())
        resources.close()
    }
    @Test fun cancellationFinalizerCanScheduleSleep() = runTest {
        val broker = Broker(); val resources = RecognitionResidency(broker, { "a" }, { false }, { 5 }, backgroundScope)
        val recording = launch { resources.begin("a"); try { awaitCancellation() } finally { resources.end() } }
        runCurrent(); recording.cancelAndJoin(); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("a" to AsrControlOp.PREPARE, "a" to AsrControlOp.RELEASE), broker.calls)
        resources.close()
    }
}
