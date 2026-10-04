# Zfold 语音轮次与打断设计

0.2.4 是轮流模式：ASR 完整返回 → Pi 回复 → TTS 播完 → 重新启动 ASR。0.2.5 已增加手动“打断”和可选择开启的本机 VAD 自动插话试验。`UtteranceProgressListener.onDone` 只在自然播报结束时重启收听；`onStop` 不当作完成。

## 两个不同的判断

| 判断 | 依据 | 动作 |
| --- | --- | --- |
| 用户是否说完 | ASR 最终结果、声音活动和停顿长度，结合末尾“然后、但是”等未完成语气 | 暂停后等待短窗口；确认一句话结束才送 Pi。短停顿只保留在同一轮输入 |
| 助手播报时是否有人插话 | 麦克风 VAD、回声消除后残余语音、与正在播放的 TTS 内容对比，必要时用 ASR 确认 | 先降低播报音量；确认是真人语音后停止 TTS，进入收听。咳嗽或 TTS 回声不应截断 |

初始试验阈值：连续人声约 200–300 ms 才作为插话候选；句中静音 500–700 ms 不急于提交，疑似未说完时延长到约 1–1.5 s。它们只是待 Fold7 实测调整的起点，不是 Android 系统保证值。

## 状态转换

```text
待命 → 收听 → 判句尾 → Pi 思考 → 分句播报 → 收听
           ↑                    │
           └── 确认插话 / 点“打断” ┘
```

- **“停、别说了”**：立即停 TTS，结束这轮播报；不要自动继续旧答案。
- **“不是，我是说……”**：停 TTS，取消尚在进行的 Pi 回复，以新的完整语句开一轮。
- **“继续”**：从上一个完整播报句之后继续，不从一个字的中间恢复。
- **噪声或回声误触发**：保持当前轮次并恢复音量，避免反复停顿。
- **TTS 自然结束**：只在匹配当前播报任务的 `onDone` 后重启收听；收到 `onStop` 不当作自然结束。用轮次 ID 忽略旧任务的迟到回调。

Laya 只能在 ASR 得到文字后辅助判断“停止 / 继续 / 新指令”等意图；不能承担原始音频的 VAD、回声消除或端点检测。固定的“停”“继续”可先用确定性规则，复杂改口交给 Pi。

## 实施顺序

1. 已做**手动插话**：播报时麦克风按钮变为“打断”，一次点击停播并开始本机 ASR；停止整个语音会话仍有单独操作。
2. 将 TTS 回复按句切段，记录已播完句子和轮次 ID，使取消与“继续”有明确位置。
3. 已接入播报期间的单路 `AudioRecord` + Silero VAD + Android AEC，作为可切换的自动插话试验。Fold7 原生模型自检、1.2 秒录音检查和一次 TTS 回声测试通过：直接合成语音的 VAD 峰值约 0.9997，TTS 外放经 AEC 的麦克风输入没有误触发。仍需用户真人插话、噪声和蓝牙测试。Android 对并发麦克风录制有优先级与静音规则，不能假定独立 `AudioRecord` 和系统 `SpeechRecognizer` 总能同时拿到音频。
4. Android 的 `EXTRA_AUDIO_SOURCE` 可传外部 PCM 给识别器。Fold7 探测在输入离线 TTS 合成的 16 kHz PCM 后返回完整的部分识别文字，但最终文字为空；需要继续验证实时音频转接和端点处理。当前自动插话会先停播、释放 VAD 录音，再启动系统本机 ASR，可能漏掉开头很短的词。若转接不可用，再考虑用可接收应用 PCM 的独立 ASR，避免两个录音客户端争抢麦克风。
5. 验收安静/音乐/外放/耳机/蓝牙/折叠切屏下的误打断率、真正插话响应时间与电量。后台持续监听需另行设计前台服务与清晰的麦克风状态。
6. 0.2.18 已实现流式分句 TTS，以及播报期的 `VoiceBargeIn`：VAD 确认插话后才启动外部 PCM 识别，但先送入最近约 2 秒预缓冲，再持续写入实时 PCM；final 为空时使用 partial。这改变了第 4 点“必然漏首词”的旧转接方式，但仍需真人插话验收。普通非播报轮次仍使用系统本机 `SpeechRecognizer` 自身采集。
7. 0.2.22 修复连续对话关键竞态：`turn_end` 只表示 Pi 文本任务结束，不代表 TTS 结束。若 `ttsSpeaking=true`，不再重启 ASR，而是显示“正在播报 · 播完自动听”；最后一句 `onDone` 后延迟约 320ms 再进入识别。0.2.21 的 `VoiceBargeIn` 还增加 ASR/播报文本回声门：VAD 只产生候选，ASR 与当前播报文本相似则冷却并继续播报，不相似才停止 TTS。手动打断仍立即有效，但切换前等待约 360ms 释放音频路径。
8. 0.2.58 把 Silero VAD 引入普通收听期：`VoiceAsrSource` 的同一路 16 kHz PCM 继续交给 Android ASR，同时驱动本地 VAD。VAD 只产生“开始说话 / 一轮结束”的候选；有 ASR 文本且静音约 900 ms 时请求 final，无文本的短语音只触发识别器收尾并重听，不直接提交。VAD 不可用时保留旧的 RMS + partial 兜底。

依据：[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[Android UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)、[Android AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler)、[Android 音频输入共享](https://developer.android.com/media/platform/sharing-audio-input)、[sherpa-onnx Silero VAD](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html)。
