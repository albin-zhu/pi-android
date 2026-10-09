# 官方 Subagent 示例的 Android 宿主适配（测试版）

## 来源与复用边界

上游：https://github.com/earendil-works/pi/tree/v1.1.0/packages/coding-agent/examples/extensions/subagent

`runtime/vendor/pi-subagent-v1.1.0/` 保留 v1.1.0 的 index.ts、agents.ts、README、四个角色 Markdown 及 MIT LICENSE 原文。APK 附带四个角色文件和 LICENSE。

本版复用官方角色正文及 single / parallel / chain + `{previous}` 工作流约定、限并发有序收集算法。**不是原封不动运行官方 CLI 扩展**：官方 spawn(pi) 和 TUI 在 Android 嵌入式 Node 上不可直接使用，`subagent.mjs` / `subagent-session.mjs` 是宿主适配代码，实际子 Agent 使用未修改的 `createAgentSession` SDK。

不接入实验性 durable 后端，不升级现有 SDK（仍为1.0.4），不迁移原有会话。先前 durable 56项 PoC 不是本功能的测试证据。

## 可以做什么

- 内置 scout、planner、reviewer、worker；每项独立历史和会话ID，只接收分配的任务文本，不复制主聊天。
- 单个、最多8项并行（全局最多4个子会话执行，等待队列最多16项）、最多8步链式。链式失败即停止后续步骤。
- 沿用当前模型、认证运行时与思考档位，忽略角色 frontmatter 中固定的 Claude 型号及 tools 设置。
- 首版四个角色均只读：read、ls、字面量 grep、简单通配符 find。worker **不是**官方示例里的全权限 worker。手机、短信、联系人、MCP、Shell、文件写入和递归 subagent 均未提供。
- 工作目录及读取路径限制在现有用户文件根内；搜索不经过 Shell，不遍历符号链接、.git、node_modules，限制条目/层级/读取字节/输出数量。
- 禁用子会话项目扩展、技能、上下文文件、缓存预热和自动重试，限制模型轮数和每次工具调用120秒总时限。
- 主工具卡显示各项进度及最终结果；父任务停止联动活动子会话和队列。最终返回不代表用户目标验收。
- 真实模型回执以独立ID计入现有本地用量记录。委托会增加模型请求/费用，并行不会减少总用量。

## 安全和当前限制

这是同一 Android App UID 内的独立 SDK 上下文，**不是 OS 沙箱或独立进程隔离**。安全边界来自不暴露危险工具、路径校验和关闭扩展；共享模型认证服务不向模型输出密钥。普通用户工作区文件仍是可读数据，不要把上下文分离当成文件数据隔离。

完整子会话记录保存在私有 runtime/subagent-sessions，不混入主会话库；父工具结果含 parentSessionId/sessionId 关联。当前没有独立的子会话浏览/删除入口和自动清理策略，没有进程重启续跑、消息级 Fork、单独停止某个子Agent或主会话后台并行。没有持久化请求去重或 exactly-once 保证。此次没有启用项目自定义 agent 配置，避免仓库角色静默扩大权限。

## 使用

安装测试包后可对橙橙说：

> 用两个只读子Agent并行检查这个项目：scout梳理结构，reviewer找风险，最后汇总。不要修改文件。

或：

> 先让scout查相关代码，再把结果交给planner给出实现计划。

展开“子Agent · 独立上下文”工具卡查看分配任务、进度及返回结果；发送区停止按钮停止整个当前任务，包括子任务。每个子Agent只知道明确传入的任务，需由主Agent提供必要背景。

## 本机验证（2026-10-09）

平台：Android/arm64 package Node26.4.0，私有独立构建目录；并非已安装 App 的 nodejs-mobile24.21.0。测试模型使用本地 HTTP/SSE fixture、测试凭据与临时目录；没有调用用户付费模型。

- `tests/subagent*.test.mjs` 14项通过：单个、4并发/顺序、链式替换/失败、输入/路径拒绝、提前取消/运行中取消、总超时、UTF8截断、只读搜索；真实SDK创建/持久化/并行隔离/流请求取消；打包主运行时端到端子工具白名单、结果/进度/用量、父停止后不再请求主模型。
- 连同 thinking-level 5项和 steer-attachments 5项，定向回归 **24/24通过**。日志：`../artifacts/subagent-targeted.log`。
- 完整测试日志：138项，134通过、1失败、3取消；外层runtime_exec还达到600秒超时，不能报告完整测试通过。日志：`../artifacts/subagent-regression.log`。
  - `session-catalog.test.mjs`：旧断言要求不列出另一个workspace会话，实际返回1；此测试和对应模块与main相同，本次未改动。
  - `sandbox-runtime`、`shell-runtime`：夹具20秒超时，shell测试文件另有pending promise取消；夹具不应答Android runtime_environment请求（已读源码），平台适配仍待补。没有为通过而删除断言。
- target28完整native/resources/java/d8打包签名成功，日志 `../artifacts/subagent-build.log`。
- APK：`../artifacts/Zfold-0.2.85-compat28-subagent.apk`。
- APK SHA256：`d30acf81de29341fd3440171cc831d8fbf92aeaa7fee1bc17918a9c3de8968b1`。
- Signer SHA256：`192ee603469e9f3a289c7cea7d223228565f51faf38dd82f27b421a477a08530`，与原应用一致。
- 已检查APK包含四个角色和MIT许可证，不包含子会话测试记录。

目标面板同步：本轮尝试把步骤5更新为verification，goal_plan返回“Extension runtime not initialized”，更新未成功；此文档作为本轮验证记录，不把面板状态视为已更新。装后自测日 goal_plan get 正常但 update 持续同一错误（3次跨2轮），面板仍为 revision 29 旧证据。

## 真机自测（2026-10-09，用户安装后）

用户安装 subagent 测试包并要求自测。当前会话引擎即新构建，subagent 工具可用。真实网关模型 glm-5.3-flashx：

- 单个：scout 读 SUBAGENT.md 前40行并总结，返回正确，状态 done。
- 并行：scout+reviewer 各读指定小节，结果按序返回、内容不串扰。
- 链式：scout 摘录→planner 用 {previous} 判断委托类型，替换正确。
- 磁盘：runtime/subagent-sessions/ 新增3个独立 UUID 目录，各自 JSONL，会话ID、cwd、模型正确，未混入主会话库。
- 界面：截图 screenshots/screen-5371820752912594362.jpg，工具卡“子Agent · 独立上下文”标题/已返回/模式行/分配任务/返回结果正常，深色主题可读。
- 用量：回执带 subagent: 前缀（桥接测试已证）；custom 网关不在 UsageMath.family 统计族（仅 openai/xai/kimi），小部件不统计，为既有行为非回归。
- 真机未测：子任务运行中按停止、轮数/120秒上限触发（仅单测/桥测覆盖）、无每个子任务单独停止入口；链式未测失败中断分支。
- 代码仍在 feat/official-subagent 未提交/合并/推送；功能可用待用户验收。

待用户安装后验证：嵌入式Node24、真实网关及OAuth、主会话切换中断、Fold深浅主题/窄屏/大字、任务卡进度可读性、停止及费用展示。当前代码在 `feat/official-subagent`，未提交/合并/推送，不自动安装，不代表产品验收。
