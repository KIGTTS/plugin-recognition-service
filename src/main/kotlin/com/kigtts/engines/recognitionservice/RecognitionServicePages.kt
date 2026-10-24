package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*

internal fun recognitionServicePage(id: String, settings: RecognitionServiceSettings, broker: RecognitionBroker,
    context: PluginContext, serviceId: String): PluginPage? {
    val all = settings.ordered(broker.providers()); val selected = settings.current(all)
    if (id == "manager") return PluginPage(id, "语音识别服务", buildList {
        if (context.recognitionServiceId != serviceId) add(PageBlock.Actions(listOf(PageAction("activate", "用于语音识别", PluginIcon.MIC))))
        add(PageBlock.Group("识别", listOf(PageBlock.Input("识别引擎", selected?.name ?: "选择识别引擎", "engines", icon = PluginIcon.MIC))))
        add(PageBlock.Group("聆听字幕", listOf(PageBlock.Choice("显示位置", listOf(
            PageAction("output/PANEL", "主界面", PluginIcon.TEXT), PageAction("output/FLOATING", "浮窗", PluginIcon.TEXT),
            PageAction("output/BOTH", "两处", PluginIcon.TEXT)), "output/${settings.captionOutput.name}", PluginIcon.HEARING))))
        add(PageBlock.Group("语音输入", listOf(
            PageBlock.Toggle("快捷语音输入", settings.voiceInput, "voice-input", "长按快捷文本开始或停止", PluginIcon.MIC),
            PageBlock.Toggle("连续输入", settings.continuousInput, "continuous-input", "逐句发送，再次长按停止", PluginIcon.HEARING))))
        add(PageBlock.Group("加载方式", listOf(
            PageBlock.Toggle("保持加载", settings.resident, "resident", "减少下次识别等待"),
            PageBlock.Slider(PluginControl.Slider("保留时间", "idle-seconds", settings.idleSeconds.toFloat(), 5f, 120f, steps = 22, suffix = "秒")))))
        val entries = all.filter { it.enabled }.flatMap { provider -> provider.settingsPages.map { page ->
            PageAction("settings/${provider.pluginId}/${page.id}", if (provider.settingsPages.size == 1) provider.name else "${provider.name} · ${page.title}", page.icon)
        } }
        if (entries.isNotEmpty()) add(PageBlock.Group("引擎设置", listOf(PageBlock.Actions(entries))))
    })
    if (id == "engines") return PluginPage(id, "选择识别引擎", emptyList(),
        ManagementList("recognition-engines", all.map { provider ->
            ManagementItem(provider.pluginId, provider.name,
                if (!provider.enabled) "已停用" else if (provider.info.availability == EngineAvailability.READY) recognitionKind(provider.info.streaming) else "待配置",
                PluginIcon.MIC, selected = provider.pluginId == selected?.pluginId,
                primaryAction = "select/${provider.pluginId}", selection = ManagementSelection("select/${provider.pluginId}"))
        }, emptyText = "添加识别引擎后即可选择"))
    return null
}
internal fun recognitionKind(kind: StreamingKind) = when (kind) {
    StreamingKind.INCREMENTAL -> "边说边识别"
    StreamingKind.SEGMENTED -> "分段识别"
    StreamingKind.NONE -> "录音识别"
}
