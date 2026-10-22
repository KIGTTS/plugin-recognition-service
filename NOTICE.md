# 语音识别服务插件

新增服务源码 MIT。服务只包含引擎管理、当前引擎选择与规范识别路由，不链接 sherpa、ONNX Runtime、SenseVoice 或其它引擎实现，不包含模型。

字幕与语音输入依赖启用的识别服务；服务通过宿主授予的 RecognitionBroker 跨进程调用独立的 PROVIDER 引擎。音频与 Started/Partial/Final 按原引擎传递，取消逐级释放；不将离线分段结果伪装成增量流式，也不在失败时更换语言/引擎。

服务配置与排序在 `appdata/Kigtts/plugin/plugindata/service.speech-recognition/config/`。各引擎模型、下载与其它资源仍在对应引擎自己的 Appdata，服务仅打开其声明的设置/识别包管理页面。MIT 完整许可见 LICENSE。
