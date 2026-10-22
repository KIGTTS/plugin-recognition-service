package com.kigtts.engines.recognitionservice

import com.kigtts.plugin.api.*

internal fun recognitionServicePage(id: String, settings: RecognitionServiceSettings, broker: RecognitionBroker,
    context: PluginContext, serviceId: String): PluginPage? {
    val all = settings.ordered(broker.providers()); val selected = settings.current(all)
    if (id == "manager") return PluginPage(id, "语音识别服务", buildList {
        if (context.recognitionServiceId != serviceId) add(PageBlock.Actions(listOf(PageAction("activate", "用于语音识别", PluginIcon.MIC))))
        add(PageBlock.Group("聆听字幕", listOf(PageBlock.Choice("显示位置", listOf(
            PageAction("output/PANEL", "主界面", PluginIcon.TEXT), PageAction("output/FLOATING", "浮窗", PluginIcon.TEXT),
            PageAction("output/BOTH", "两处", PluginIcon.TEXT)), "output/${settings.captionOutput.name}", PluginIcon.HEARING))))
        add(PageBlock.Group("语音输入", listOf(
            PageBlock.Toggle("快捷语音输入", settings.voiceInput, "voice-input", "长按快捷文本开始或停止", PluginIcon.MIC),
            PageBlock.Toggle("连续输入", settings.continuousInput, "continuous-input", "逐句发送，再次长按停止", PluginIcon.HEARING),
        )))
    }, management = ManagementList("recognition-engines", all.map { provider ->
        ManagementItem(provider.pluginId, provider.name,
            (if (!provider.enabled) "已停用" else if (provider.info.availability == EngineAvailability.READY) recognitionKind(provider.info.streaming) else "请添加识别包"),
            PluginIcon.MIC, selected = provider.pluginId == selected?.pluginId, primaryAction = "engine/${provider.pluginId}",
            actions = listOf(PageAction("select/${provider.pluginId}", "选用", PluginIcon.MIC)),
            toggle = ManagementToggle(provider.enabled, "toggle/${provider.pluginId}"))
    }, "reorder", "添加识别插件后即可使用"))
    if (id.startsWith("engine/")) {
        val provider = all.firstOrNull { it.pluginId == id.substringAfter('/') } ?: return null
        val items = listOf(
            ManagementItem("enabled", "启用", icon = PluginIcon.MIC, group = "识别",
                toggle = ManagementToggle(provider.enabled, "toggle/${provider.pluginId}")),
            ManagementItem("select", "用于识别", recognitionKind(provider.info.streaming), PluginIcon.MIC, group = "识别",
                selected = provider.pluginId == selected?.pluginId, primaryAction = "select/${provider.pluginId}")) +
            if (provider.enabled) provider.settingsPages.map { page ->
                ManagementItem("settings/${page.id}", page.title, icon = page.icon, group = "设置",
                    primaryAction = "settings/${provider.pluginId}/${page.id}")
            } else emptyList()
        return PluginPage(id, provider.name, emptyList(), ManagementList("recognition-settings/${provider.pluginId}", items))
    }
    return null
}
internal fun recognitionKind(kind: StreamingKind) = when (kind) {
    StreamingKind.INCREMENTAL -> "边说边识别"
    StreamingKind.SEGMENTED -> "分段识别"
    StreamingKind.NONE -> "录音识别"
}
