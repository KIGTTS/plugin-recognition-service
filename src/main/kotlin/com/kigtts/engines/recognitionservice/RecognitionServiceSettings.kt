package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*
import org.json.JSONObject
import org.json.JSONArray

internal class RecognitionServiceSettings(storage: PluginStorage) {
    private val file = storage.file("config/recognition-service.json").also { storage.configuration }
    private val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    var provider: String? = json.optString("provider").takeIf { it.isNotBlank() }
    var voiceInput = json.optBoolean("voiceInput", true)
    var continuousInput = json.optBoolean("continuousInput", false)
    var captionOutput = runCatching { CaptionOutput.valueOf(json.optString("captionOutput", "PANEL")) }.getOrDefault(CaptionOutput.PANEL)
    private var order = json.optJSONArray("order")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
    fun ordered(providers: List<AsrProvider>): List<AsrProvider> {
        val data = providers.associateBy { it.pluginId }
        return ItemOrder.reconcile(order, providers.sortedBy { it.pluginId }.map { it.pluginId }).mapNotNull(data::get)
    }
    fun current(providers: List<AsrProvider>): AsrProvider? {
        if (provider != null) return providers.firstOrNull { it.pluginId == provider && it.enabled }
        return providers.filter { it.enabled && it.info.availability == EngineAvailability.READY }
            .sortedWith(compareBy<AsrProvider> { when (it.info.streaming) { StreamingKind.INCREMENTAL -> 0; StreamingKind.SEGMENTED -> 1; StreamingKind.NONE -> 2 } }
                .thenBy { it.pluginId }).firstOrNull()
    }
    fun reorder(proposed: List<String>, providers: List<AsrProvider>) { ItemOrder.validate(proposed, providers.map { it.pluginId }); order = proposed; save() }
    fun save() { file.writeText(JSONObject().put("provider", provider).put("order", JSONArray(order))
        .put("voiceInput", voiceInput).put("continuousInput", continuousInput)
        .put("captionOutput", captionOutput.name).toString(), Charsets.UTF_8) }
}
