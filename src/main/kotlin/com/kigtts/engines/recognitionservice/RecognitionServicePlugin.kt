package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*

class RecognitionServicePlugin : KigPlugin {
    override val descriptor = PluginDescriptor("service.speech-recognition", "语音识别服务", "聆听字幕、语音输入与识别包", version = "0.8.2")
    private lateinit var settings: RecognitionServiceSettings
    private lateinit var broker: RecognitionBroker
    private lateinit var facade: ServiceRecognition
    override val asr: AsrEngine get() = facade
    override suspend fun attach(environment: PluginEnvironment) {
        settings = RecognitionServiceSettings(environment.storage)
        broker = requireNotNull(environment.recognitionBroker) { "宿主未授予语音识别服务能力" }
        facade = ServiceRecognition(settings, broker)
    }
    private val entry = SettingsEntry("manager", "语音识别", "manager", PluginIcon.MIC,
        cardId = "audio", cardTitle = "音频", summary = "聆听字幕与语音输入")
    override val contributions get() = contributions(PluginContext("", ""))
    override fun contributions(context: PluginContext): PluginContributions {
        val selected = context.recognitionServiceId == null || context.recognitionServiceId == descriptor.id
        facade.updateActive(selected)
        return PluginContributions(settingsEntries = listOf(entry), automationActions = listOf("listen", "close", "input", "continuous"), actions = if (selected) listOf(
            UiAction("listen", if (captionSession(context)) "关闭聆听" else "开始聆听", UiSlot.EDITOR_TOP_START,
                PluginIcon.HEARING, buttonStyle = UiButtonStyle.ICON_TEXT),
            UiAction("close", "关闭聆听", UiSlot.LISTENING_BOTTOM_END, PluginIcon.CLOSE),
            UiAction("continuous", if (context.continuousRecognition) "停止连续输入" else "连续语音输入",
                UiSlot.EDITOR_MENU, PluginIcon.MIC, UiPresentation.MENU_ITEM),
        ) else emptyList(), gestures = if (selected && settings.voiceInput)
            listOf(GestureAction("input", if (context.continuousRecognition) "停止连续输入" else "语音输入", PluginGesture.QUICK_TEXT_LONG_PRESS))
            else emptyList())
    }
    override fun page(pageId: String, context: PluginContext) = recognitionServicePage(pageId, settings, broker, context, descriptor.id)
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val parts = actionId.split('/'); val id = parts.getOrNull(1)
        fun provider() = broker.providers().first { it.pluginId == id }
        return when (parts[0]) {
            "listen" -> if (captionSession(context)) PluginOutcome.EndRecognition() else start(RecognitionPurpose.CAPTIONS)
            "close" -> PluginOutcome.EndRecognition()
            "input" -> if (!settings.voiceInput) PluginOutcome.RefreshUi
                else if (context.voiceInputActive || context.continuousRecognition) PluginOutcome.EndRecognition()
                else start(RecognitionPurpose.VOICE_INPUT, settings.continuousInput)
            "continuous" -> if (context.continuousRecognition) PluginOutcome.EndRecognition()
                else start(RecognitionPurpose.VOICE_INPUT, continuous = true)
            "voice-input" -> { settings.voiceInput = !settings.voiceInput; settings.save(); PluginOutcome.RefreshUi }
            "continuous-input" -> { settings.continuousInput = !settings.continuousInput; settings.save(); PluginOutcome.RefreshUi }
            "output" -> { settings.captionOutput = CaptionOutput.valueOf(requireNotNull(id)); settings.save(); PluginOutcome.RefreshUi }
            "activate" -> PluginOutcome.SelectRecognitionService(descriptor.id)
            "engines" -> PluginOutcome.OpenPage("engines")
            "resident" -> {
                settings.resident = context.inputs["checked"]?.toBooleanStrictOrNull() ?: !settings.resident
                settings.save(); facade.settingsChanged(); PluginOutcome.RefreshUi
            }
            "idle-seconds" -> {
                val value = context.inputs["value"]?.toFloatOrNull()
                require(value != null && value.isFinite() && value in 5f..120f)
                settings.idleSeconds = kotlin.math.round(value).toInt(); settings.save()
                facade.settingsChanged(); PluginOutcome.NoAction
            }
            "select" -> {
                val selected = provider(); settings.provider = selected.pluginId; settings.save(); facade.settingsChanged()
                if (!selected.enabled) PluginOutcome.SetPluginEnabled(selected.pluginId, true) else PluginOutcome.RecognitionRoutingChanged
            }
            "toggle" -> provider().let { PluginOutcome.SetPluginEnabled(it.pluginId, !it.enabled) }
            "settings" -> {
                val selected = provider(); check(selected.enabled) { "请先启用引擎" }
                val page = parts.drop(2).joinToString("/"); require(selected.settingsPages.any { it.id == page })
                PluginOutcome.OpenPluginPage(selected.pluginId, page)
            }
            "reorder" -> { settings.reorder(PluginJson.decodeFromString(requireNotNull(context.inputs["order"])), broker.providers()); PluginOutcome.RefreshUi }
            else -> PluginOutcome.Message("未知识别服务操作")
        }
    }
    override suspend fun close() { if (::facade.isInitialized) facade.close() }
    private fun captionSession(context: PluginContext) = context.listeningPanelVisible ||
        (context.listening && !context.voiceInputActive && !context.continuousRecognition)
    private fun start(purpose: RecognitionPurpose, continuous: Boolean = false): PluginOutcome {
        val info = facade.info
        if (info.availability != EngineAvailability.READY) return PluginOutcome.Message("请先选用并配置一个识别包")
        if ((continuous || purpose == RecognitionPurpose.CAPTIONS) && info.streaming == StreamingKind.NONE)
            return PluginOutcome.Message("当前识别包仅支持完整录音，请选用支持边说边识别的识别包")
        return PluginOutcome.StartRecognition(RecognitionSession(descriptor.id, purpose, continuous = continuous,
            output = if (purpose == RecognitionPurpose.CAPTIONS) settings.captionOutput else CaptionOutput.PANEL,
            disableAutomaticRead = continuous, readFinalized = continuous))
    }
}
