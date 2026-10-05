# Zfold 现状核对与整改清单

日期：2026-10-05

代码基线：d9cf8a8 / 0.2.72-compat28

对应目标：[PRODUCT_PLAN.md](PRODUCT_PLAN.md)

Java 文件均位于 android/src/dev/zfold/assistant/。行号指上述基线版本；以后代码移动时以函数名和基线提交核对。

本次是产品与代码核对，不是一次新的功能发布。以下“确认”指有源码/现有记录支撑；“风险”不表示已在真机复现。真机 11:15 左右的截图明确显示旧 AssistantPanel 覆盖在终端上，是用户本次反馈的实际问题。

## 一、核对结论

当前基础能力值得保留，主要问题是**产品约束没有覆盖全部入口和状态归属**：

- 两套聊天实现同时在线；从不同入口进入会得到不同的布局、功能和恢复方式。
- 共用 PiRuntime 不等于共用 UI 状态；共用 VoiceTurnController 类也不等于只有一个控制器实例。
- 系统小窗的布局已有验证，但“应用内直接切换”“所有入口都进入同一小窗”尚未完成。
- 多份文档把目标、旧实现、局部验收混在一起；一些测试继续验证旧面板，所以“测试通过”无法证明旧路径已退出产品。
- 新增需求“橙橙唤醒 → 实时语音页 → 接通音”目前也未实现；显示名已经改变，唤醒链路仍硬编码小派。

## 二、已有基础：保留与边界

| 能力 | 可确认的基础 | 边界 |
| --- | --- | --- |
| 本机 Agent | Pi SDK、嵌入式 Node、JNI 命令/事件桥接 | 仍与 UI 同 app 进程；不是全天候独立守护 |
| 执行与取消 | tool ID、turn ID、心跳、受管理执行与取消 | 不等于所有外部副作用都能回滚 |
| 终端 | 真正 VT/PTY、字节流、后台会话与前台服务 | 进程内 Job 状态不等于进程死亡后恢复 |
| 环境与凭据 | RuntimeEnvironment、CredentialStore、私有认证 | 同 app UID 的用户代码不等于完整沙箱 |
| MCP | 客户端连接、stdio/网络、认证、工具发现与调用 | 并非已提供完整的手机 MCP server |
| 手机操作 | 语义快照、截图 ID、新鲜度/回读校验、可替换选择器 | 常用第三方 App 的整任务完成不能仅由工具回执判断 |
| 主聊天交互 | 系统小窗尺寸、大字体、键盘、左右滑、返回专项 | 这些证据不覆盖旧面板与所有入口 |
| 语音 | KWS、ASR/TTS、VAD、插话及点击头像控制基础 | 实例唯一性、设备路由、后台语音和新唤醒词仍需整改 |

上述基础的历史验证见 VERIFICATION.md；本表不扩大原测试覆盖范围。

## 三、逐项源码核对

### A01 · P0 · 两套聊天实现仍可生产触达【确认】

证据：

- [MainActivity.java](android/src/dev/zfold/assistant/MainActivity.java)：buildUI、showHistory、sendText。
- [AssistantPanel.java](android/src/dev/zfold/assistant/AssistantPanel.java)：构造函数与 buildHeader/buildComposer/buildAnswer；有自己的输入、回复、附件与工具列表。
- [AssistantOverlay.java:33](android/src/dev/zfold/assistant/AssistantOverlay.java#L33) 与 [AssistantSessionService.java:44](android/src/dev/zfold/assistant/AssistantSessionService.java#L44) 都直接构造 AssistantPanel。

影响：同样是“找助手”，用户获得不同界面。新版侧滑、布局与语义只改 Main，旧入口继续保留旧行为。

处理：一个 ChatSurface/聊天宿主；旧渲染器退出生产路径。别名入口只允许路由，不保留第二套聊天兜底。

### A02 · P0 · 小麦克风、任务 HUD、唤醒仍路由到旧面板【确认】

证据：

- [CircleOverlay.java:36](android/src/dev/zfold/assistant/CircleOverlay.java#L36)、46–48：点击、展开、看屏幕、圈选回调调用 AssistantOverlay。
- [UnifiedOverlay.java:110](android/src/dev/zfold/assistant/UnifiedOverlay.java#L110)、113、127：任务麦克风/主体仍调用旧 Overlay。
- [VoiceWakeService.java:139](android/src/dev/zfold/assistant/VoiceWakeService.java#L139)–141：系统助手、已有系统小窗和其他情况分别走不同路径。
- [FloatingNavigation.java](android/src/dev/zfold/assistant/FloatingNavigation.java)：返回工作页后还可恢复旧面板。

处理：所有动作表达为 EntryRequest，进入同一 EntryRouter。清理工具调用后自动召回旧聊天 UI 的路径，不能只修一个按钮。

### A03 · P0 · 系统助手与回退助手行为不一致【确认】

证据：

- [OverlayEntryActivity.java](android/src/dev/zfold/assistant/OverlayEntryActivity.java) 已调用 SystemWindowNavigation.showChat。
- [AssistantSessionService.java](android/src/dev/zfold/assistant/AssistantSessionService.java) 仍创建完整旧聊天面板，并有自己的展开、收起、圈选与关闭逻辑。

处理：系统助手 Session 只负责唤起和交付系统上下文，展示交给统一聊天。角色入口、普通 ASSIST 回退必须分别验收。

### A04 · P0 · 语音控制器按界面创建，跨入口寻址不一致【确认实例结构；并发录音未据此判定】

证据：

- [MainActivity.java:47](android/src/dev/zfold/assistant/MainActivity.java#L47) 与 [AssistantPanel.java:40](android/src/dev/zfold/assistant/AssistantPanel.java#L40) 分别 new VoiceTurnController。
- [UnifiedOverlay.java:90](android/src/dev/zfold/assistant/UnifiedOverlay.java#L90) 的头像操作寻找 MainActivity.activeVoice。
- VoiceCallActivity 与后台状态从 Main/静态字段寻找控制器。
- AudioModeOwner 管的是 AudioManager 场景，不是唯一 ASR/TTS 控制器实例注册表。

影响：类复用了，但状态所有者仍分散。从旧面板开始的语音，不应假设能由 Main 的控制器正确打断。

处理：一个 VoiceCoordinator/VoiceSession ID，所有头像、唤醒、结束、状态条都向它发命令。

### A05 · P0 · 应用内“系统小窗”是说明，不是切换操作【确认】

证据：

- [WorkbenchActivity.java:58](android/src/dev/zfold/assistant/WorkbenchActivity.java#L58) 调用 SystemWindowNavigation.explain。
- MainActivity 的手机能力入口及非多窗口看屏幕路径也调用 explain。
- [SystemWindowNavigation.java:53](android/src/dev/zfold/assistant/SystemWindowNavigation.java#L53) 仅构建说明对话框。
- 真机声明 android.software.freeform_window_management，系统版本 API 36；不能以缺 feature 为由跳过目标机验证。

处理：先验证直接窗口切换的有效适配路径，再接实际按钮。验收观察真实模式与边界，启动 Intent 发出不算成功。

### A06 · P0 · “恢复最后页面”与“打开对话”混在一个方法里【确认】

证据：SystemWindowNavigation.showChat 在已有 task 分支只 moveToFront；任务顶部可能是工作台或其他页面。voice 请求在 MainActivity.current 为空时没有传给后续实例，方法仍返回。

补充：ConnectorActivity.returnFromOAuth 使用 NEW_TASK/CLEAR_TOP，回调是否返回原小窗与原连接页面也需要入口矩阵验证。

处理：RESTORE_APP、OPEN_CHAT、OPEN_VOICE、OPEN_JOB 与原请求返回分开；冷启动请求要排队且只消费一次。一般应用恢复保留最后页面，助手入口必须到目标会话。

### A07 · P0 · 会话、草稿与展示快照分散【确认；截图不同不等于两个底层 Agent】

证据：

- Main 的 input/pendingImage/localFiles 在 Activity 字段中。
- AssistantOverlay 保存另一份静态 AssistantPanel.Draft。
- ScreenContext 的 enabled 是进程全局状态，没有会话绑定。
- AssistantPanel 使用 runtime.lastPrompt/lastResponse 恢复局部展示；Main 使用完整 history。
- [PiRuntime.java:17](android/src/dev/zfold/assistant/PiRuntime.java#L17) 是进程单例；runtime/agent.mjs:122 是全局单 session/busy。

处理：Session ID 下的统一草稿与上下文存储；视图订阅同一事件。不要把问题误判成必须重写两套 Agent 引擎。

### A08 · P0 · 界面销毁与语音生命周期耦合【确认】

证据：[MainActivity.java:623](android/src/dev/zfold/assistant/MainActivity.java#L623) 在销毁时按 VoiceCallActivity 是否活跃决定 voice.destroy；AssistantPanel.dispose 也负责自身控制器释放。

处理：页面卸载只注销订阅。系统关闭最后对话窗口是否结束语音由明确产品命令判断；旋转、重建和切页不得当作“结束语音”。

### A09 · P1 · 查询、补充、改目标和停止尚未分离【确认】

证据：

- MainActivity.sendText 在 busy 时，将非停止文本设为 pendingTaskPrompt 后发送 stop。
- [MainActivity.java:564](android/src/dev/zfold/assistant/MainActivity.java#L564) 的 stop 同时清理本地文件附件并调用 voice.stopSession(true)。
- runtime/agent.mjs:798–823 在 busy 时禁止切换运行会话/新 prompt。
- 当前 Pi SDK 的 agent-session.d.ts 已有 steer 和 followUp，具备复用基础。

影响：“做到哪了”这样的查询可能中止旧目标；停止任务还会结束语音并清理部分草稿。查看其他会话与切换 runner 也未分开。

处理：独立控制命令、查看状态与排队语义。安全边界上的补充要求可评估 SDK steering；不要简单删除 busy 检查冒充并发。

### A10 · P1 · 语音启动强制路由和抬音量【确认代码；未扩大声学结论】

证据：AudioModeOwner.enterSession 选择内置扬声器、setSpeakerphoneOn(true)，当前通话音量低于一半时设为最大值。

处理：尊重用户设备和音量，用音频焦点/租约管理场景；耳机、外放、来电单独验证。接通提示音不得沿用强制最大音量的方式。

### A11 · P1 · 看屏幕与执行工具的目标选择不一致【确认实现差异】

证据：

- ScreenContext 调用 screenApplicationPackage，过滤自身并捕获外部窗口。
- PhoneAccessibility.applicationRoot 根据当前焦点选根；screen_read、screen_capture 等使用该路径。

风险：系统小窗获焦后，读图与后续工具可能针对不同窗口。不是已证明每次都会误点。

处理：统一显式 ScreenTarget 和观测 ID；UI 焦点不自动更换任务目标，原有快照/坐标失效校验继续保留。

### A12 · P1 · 用户接管判定过宽【确认】

证据：PhoneAccessibility.onAccessibilityEvent 对 touch-interaction-start 检查 phoneBusy；PiRuntime.takeoverPhone 设置 stopped 并发送整轮 stop。phoneActive 在一组手机工具触发后保持到回合结束/中止。

影响：需要区分助手自身导航、用户操作目标 App、后续已转为后台计算。不能让任意触碰统一取消所有工作。

处理：先停止屏幕派发，按资源和事件来源分类；来源不明时保留执行事实，不能继续盲点。

### A13 · P1 · 运行时与恢复目标尚未完全落地【确认】

证据：PiRuntime.startNode 在 app 内线程运行；运行状态多为内存字段。runtime/agent.mjs 有活动会话文件，但这不等于持久 Run/Job 恢复协议。

处理：分阶段移出 UI 所有权、记录 Run/Job 与中断，再推进独立 supervisor。应用进程被杀后不得继续显示“正在运行”或盲重放外部提交。

### A14 · P1/P2 · 终端与安装已有分离基础，资源契约仍需补齐【确认基础；冲突是待验证风险】

证据：

- TerminalSessions 的 Job 与 Activity 已分离，并有前台服务。
- jobs/selected 仍在进程内，监听器是单 WeakReference。
- PiPackageManager 自身有 synchronized 安装入口与 installed.json；这不证明所有终端包工具、Runtime Packs 共用事务与清单。

处理：保留现有终端；明确手动 Job 与 Agent 派生 Job 的关联。后续多终端窗口、多安装后端再统一观察者和资源仲裁。

### A15 · P1 · 文档存在相互矛盾的“当前状态”【确认】

证据：

- RUNTIME_DESIGN 的 0.2.65 快照仍写 MCP 待接入、文本终端边界。
- README 旧“当前边界”仍写仅 OpenAI 兼容配置、不能接 MCP，和 ConnectorActivity/版本记录不符。
- PRODUCT_INTERACTION 允许多种浮标/面板，同时声称共享状态机；授权描述还有一律提交前确认的旧假设。

处理：PRODUCT_PLAN 作为唯一产品基线；历史文档标注用途；事实、目标与验证分离。已有明确授权沿用，不制造重复确认。

### A16 · P1 · 局部测试不能证明入口统一【确认】

证据：0.2.72 的 16 项主聊天手势测试通过，但生产入口仍可构造 AssistantPanel。ScreenShareInstrumentation 还专门覆盖“兼容旧浮层”；它通过不代表旧 UI 已退出。

处理：先写静态禁用旧渲染器/唯一所有者约束，再验每个入口的 session/run/voice/window 归属，最后走真机完整链路。

### A17 · P1 · 测试/更新边界需要统一【确认源码风险；未在真机执行该失败路径】

证据：

- 测试 instrumentation 随调试 APK 打包。
- SwipeNavigationInstrumentation 在 try 内检查 emulator，却在 finally 无条件恢复 scale/freeform；若在设置值尚未保存前拒绝运行，默认字段可能用于清理。
- 应用更新会重启进程，当前 PTY/语音/执行状态不能据现有设计承诺跨更新存活。

处理：统一的设备拒绝检查早于所有修改和清理，只恢复已经捕获的状态；拆分测试包。更新前检查活跃工作，默认等待安全点，已有明确中断授权则沿用。

### A18 · P2 · 一部分“直接管理操作”仍转给模型【确认差异】

证据：MainActivity.runSlashCommand 的 pkg/runtime 等分支调用 sendText/提示词生成；工作台软件包入口则有确定性 API。环境/安装状态也分布在包管理与 Runtime Packs。

处理：明确“直接执行管理命令”与“请 Agent 处理目标”。同一动作共用后端与能力状态，不增加一套同名含义。

### A19 · P0 · “橙橙”唤醒、直接语音与接通音尚未形成链路【确认】

新增用户要求：由“橙橙”唤醒后，直接进入实时语音界面，并播放接通提示音。

证据：

- [VoiceWakeService.java:33](android/src/dev/zfold/assistant/VoiceWakeService.java#L33) 的 WAKE_WORD 仍为“小派”。
- android/assets/models/kws/keywords.txt 仍是小派相关词条。
- [VoiceTurnController.java:304](android/src/dev/zfold/assistant/VoiceTurnController.java#L304)–309 的前缀剥离仍是小派/小排/xiao pi/xiao pai。
- MainActivity 的唤醒开关文案仍写小派；改 Agent 显示名不会改上述配置。
- VoiceWakeService 在检测后还等待尾音/静音段（约 1.5 秒起、最长 5 秒）完成才发起界面唤起。
- 路由仍可到 AssistantPanel。未发现应用层明确的 voice_ready → 接通提示音实现；系统识别器可能自行发声，不等于统一产品提示音。
- 当前 KWS token 表有 ch 与 éng，是配置可行性线索，不能当“橙橙”识别效果已通过。

处理：一个 WakeConfig 驱动词条、前缀和文案；唤醒事件立即交 EntryRouter 的 OPEN_VOICE；语音协调器在实际可收听后发一次 ready 事件与接通音。保留唤醒后接的命令，排除自身提示音/播报造成的触发，真实口述验收。

## 四、第一轮整改交付包

1. 确认 Fold7 直接系统小窗的有效路径及失败行为。
2. 统一 EntryRequest、Session/Draft、VoiceCoordinator 和 WakeConfig。
3. 主聊天/实时语音接入同一状态，完成“橙橙 → 语音界面 → 接通音”。
4. 小入口、HUD、系统助手、唤醒、圈选全部改路由；删除旧聊天生产路径。
5. 走完整入口矩阵，记录身份与状态一致性，之后再发更新。

先解决这五项，不再单独为旧面板加新按钮、手势或另一套适配逻辑。

## 五、本次工作范围

本次只核对源码、已有测试记录和用户展示的真实界面，制定策划与迁移标准。没有修改应用代码、唤醒词文件、系统配置或发布新 APK，也没有续做截图中显示的用户聊天任务。

策划中的“橙橙唤醒”和接通音尚待实施；运行版本仍为 0.2.72。
