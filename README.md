# 语音识别服务

KIGTTSNEXT 新版已将语音识别服务并入主程序。这个仓库保留旧版服务的源码和构建方法；新版应更新主程序，只需独立安装需要的识别引擎。见 [主程序语音识别说明](https://github.com/KIGTTS/KIGTTSNEXT/blob/main/docs/recognition-service.md)。

KIGTTS的语音识别服务独立仓库。本仓库采用 **MIT**，完整文本见[LICENSE](LICENSE)，来源与第三方许可见[NOTICE.md](NOTICE.md)。SDK的许可不会覆盖插件的许可。

## 接入识别引擎

- `AsrInfo.streaming` 未填写时，默认 `INCREMENTAL`，保留实时中间结果；引擎需声明相应的包能力和权限。
- 自行实现句尾切分、每句返回最终结果的引擎声明 `SEGMENTED`。
- 只接收整段音频的引擎声明 `NONE` 和 `batch = true`。语音识别服务在遇到停顿、达到时长上限或正常提交时切出一段，在录音继续时依序调用引擎的 `recognize`，只提交最终文字。无需在引擎里模拟 `recognizeStream`。

## 构建

需要JDK17、Android SDK36、PowerShell7。克隆时获取已固定版本的SDK及共享组件，不需要克隆主程序。

```powershell
git clone --recurse-submodules https://github.com/KIGTTS/plugin-recognition-service.git
cd plugin-recognition-service
./gradlew.bat testDebugUnitTest assembleDebug
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

Linux/macOS使用`./gradlew`。编译产物在`build/outputs/aar`，签名插件包在`build/plugin-packages`。发布私钥始终由维护者保管，不提交到本仓库；正式更新沿用既有发布者签名、插件ID与Appdata范围。已发布版本不可覆盖，发行新内容须增加版本号。

[SDK与接口](https://github.com/KIGTTS/plugin-sdk) · [官方源](https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Official-Plugins)
