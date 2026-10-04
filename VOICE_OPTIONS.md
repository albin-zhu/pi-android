# Fold7 本机语音路线 · 2026-09-23

## 当前状态

- Zfold 的 Pi/Node 在 APK 内运行；现有 ASR 仍调用系统默认 `SpeechRecognizer`，可能联网。
- 0.2.4 开始，TTS 优先选择 Android 报告已安装、无需网络的大陆中文声线；找不到才回退到系统默认中文声线。ASR 在检测到已安装中文包后使用 `createOnDeviceSpeechRecognizer`，否则明确标记默认识别可能联网。
- Fold7 初次 API 探测：`isOnDeviceRecognitionAvailable=true`，本机 ASR 已安装语言列表为空，`cmn-Hans-CN` 可下载。经用户选择系统本机路线后，通过应用入口下载，复查显示 `asrInstalled=[cmn-Hans-CN]`，Zfold 检测结果 `zfoldWillUseLocalAsr=true`。
- Fold7 的 TTS 引擎报告有已安装的中文本地声线；调用 `setVoice` 选择 `cmn-cn-x-ccc-local` 成功。尚未做断网合成试听、实际麦克风识别和长时间稳定性测试。

## 两层实现

| 路线 | 实现 | 对当前手机的结论 |
| --- | --- | --- |
| 系统本机语音 | 用户触发下载简体中文 ASR 语言包；Zfold 用 `createOnDeviceSpeechRecognizer` 和 `checkRecognitionSupport` 确认可用，TTS 固定选已安装的离线中文声线 | 最快实现；识别与播报发生在手机上，但语言包由系统服务管理，不随 APK 打包 |
| 应用内独立语音 | sherpa-onnx Android AAR + 中文流式 ASR + VAD + 中文 TTS，模型放在应用私有目录，由用户按需下载 | 可控、可离线；需评测中文误识、首字延迟、内存、电量和折叠/后台行为 |

Android 的 `EXTRA_PREFER_OFFLINE` 只是偏好，识别服务可忽略；要承诺本机识别，必须用 `createOnDeviceSpeechRecognizer` 并检查中文已安装。TTS 的 `Voice.isNetworkConnectionRequired()` 和 `notInstalled` 特征可筛选本地已安装声线。

sherpa-onnx 官方提供 Android AAR、实时中文 ASR、中文 TTS。0.2.5 已引入其 AAR **只用于 Silero VAD**，没有下载或打包独立 ASR/TTS 模型。其 14M 中文 Zipformer 的 int8 编码器约 21 MB、解码器约 7.2 MB、joiner 约 1.7 MB；1.13.8 AAR 约 47.8 MB。中文 VITS 模型约 115–163 MB。模型大小和手机实际延迟需要单独验收，不能用桌面/GPU 速度推断 Fold7 表现。

0.2.19 新增离线唤醒「小派」：sherpa-onnx KWS Zipformer WenetSpeech int8，int8 encoder/decoder/joiner 合计约 4.8 MiB。关键词为 `x iǎo p ài @小派` 和 `x iǎo p i @xiaoPi`，分别覆盖中文品牌读法和英文 `xiao pi` 读法；keywords 文件不能使用带空格的英文 alias，否则模型会把空格后的词当额外 token 并拒绝加载。唤醒是前台麦克风服务，命中后停止 KWS 并懒加载 Pi/ASR/TTS。

## Laya 的位置

Laya 是开源的文本决策模型，接收文字和候选判断，输出分类/评分；它不是 ASR、TTS 或可自由对话的 LLM。中文需用多语言检查点，权重约 644 MB，当前公开安装路径依赖 Python、PyTorch 和 Transformers，未提供现成 Android 集成。它可在 ASR **之后**尝试识别“问答 / 操作手机 / 停止”等意图，但当前 Pi 已能处理这些请求；先实测语音链路，再决定是否值得加入额外模型。

## 下一步验收

1. 用真机麦克风测已安装本机识别包的中文口语、英文应用名、噪声和停止/重试；同时断网试听离线 TTS。
2. 测「小派」的安静/噪声/外放/蓝牙唤醒率、误唤醒率、响应时间、锁屏可用性、电量与发热；语音会话中唤醒监听应暂停，结束后恢复。
3. 若系统服务效果或可控性不足，再将 sherpa-onnx 独立模型做成可选语音包，测首字延迟、常驻内存与电量。

资料：[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[Android RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport)、[Android Voice](https://developer.android.com/reference/android/speech/tts/Voice)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[sherpa 小模型](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/small-online-models.html)、[sherpa 中文 TTS](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)、[Laya](https://github.com/NandhaKishorM/laya)。
