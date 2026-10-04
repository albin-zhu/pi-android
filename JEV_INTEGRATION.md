# Jev 手机操作接入

首次接入版本：0.2.13；当前版本：0.2.17，已安装 Fold7。使用 [TypeSafe 官方 HTTP API](https://docs.typesafe.ai/api)，固定模型 `jev-1.13.0`，接口 `https://api.typesafe.ai/v1/systemone`。请求包含 `model`、结构化 `state`、`questions.next_action`，以 Bearer API Key 鉴权。没有引入 Mobilerun 云手机、电脑执行器或 MCP 服务。

## 应用内路径

Pi 负责理解任务、规划子目标和准备准确文字，通过 `phone_task` 交给 APK 内的执行循环。执行器将当前实际支持的节点动作枚举成 Choice 候选；Jev 选出下一步，原生无障碍服务执行并重新读取屏幕。每步向聊天工具卡返回动作、模型、置信度、耗时和结果。

可用动作是打开允许范围内的已安装应用、原生节点点击、设置文本、节点前后滚动、返回，以及等待、完成、受阻。输入值由 Pi 显式提供，执行器不会自行生成；密码字段不进入候选。当前实现采用一个完整动作候选 Choice，后续若候选规模/延迟实测需要，再拆分 operation 与各类 target 的并行问题。

所有动作候选都来自本机观测。`ui_act` 检查快照代数、有效期、前台应用、窗口、目标唯一语义身份、控件状态、bounds 及声明的动作；节点动作不做坐标降级。0.2.17 用稳定控件身份替代整屏逐字段指纹：无关内容刷新不误杀，目标身份真变化仍拒绝。输入系统接受后立即回读，执行结果显式区分 dispatched / observed / verified。Fold7 已通过无关事件允许操作、无关页面变化继续操作、控件身份变化拒绝旧动作以及真实 Jev 开关/中文输入测试。频繁变化和结构不完整的第三方页面仍待验收。

## 密钥

- 手机入口：**手机能力 → Jev 手机操作**。聊天模型与 Jev 分别配置。
- 手机配置保存在应用私有 `no_backup/agent/jev.json`，文件模式 0600。与已有模型设置一样，目前没有额外 Keystore 加密；调试包可通过授权 ADB 的 `run-as` 读取，不能视为生产密钥保险箱。
- 保存界面不会回显旧密钥，留空表示保留；可停用并清除。密钥不作为 Pi 工具参数，不写进系统提示或会话记录，不打包进 APK。
- 已获用户授权后，可运行 `python3 scripts/provision-jev.py --serial DEVICE_SERIAL`，从当前环境或交互式 fish 的 `JEV_API_KEY` 读取，经 ADB stdin 写入该设备的应用私有目录。它不会修改 fish 配置，也不会输出密钥。应用重启后加载。
- 自动化验收必须添加 `--test`，只写 `agent-smoke/jev.json`。测试完移除测试密钥并关闭隔离模拟器。
- `JEV_API_KEY` 是本项目接收的环境变量名，HTTP 鉴权仍是官方 Bearer 协议，无须将 fish 中的变量改名为 `TYPESAFE_API_KEY`。

## 判定与取消

- 校验 Choice 类型、候选 ID、所有概率项、分布总和、选中项是否最大，以及置信度范围。
- 目前阈值是 `confidence >= 0.75` 且前两项概率差 `>= 0.2`。这是保守初值，尚未完成多应用校准；高置信度也不保证任务正确。
- 单任务最多 20 次决定，默认 10 次，并受时间预算限制；重复状态下相同动作会停止。连续快照失效最多重新观察三次。网络错误、超时、未知动作结果不自动重放。
- `expected` 可包含当前应用、唯一控件的 checked 状态或完整文本，按执行优先级排列。每次只把第一项尚未满足的条件作为 Jev 的当前判断目标，避免多个同样正确的下一步分散 Choice 概率。最终所有条件须能在同一页面验证；跨页面/跨应用的完成条件由 Pi 拆成多个工具调用。
- 全部可见条件满足时由代码结束，不必再请模型选 DONE。条件必须覆盖完整子目标；不证明后台保存、网络提交或其他不可见效果。已满足的开关不会再次进入点击候选；支持直接 SET_TEXT 的输入框不会同时提供冗余的聚焦点击候选。
- 只有上述条件实测满足才返回 `verified`。没有条件或条件未满足时，模型 DONE 返回 `unverified`；低置信度、缺失候选、卡住等返回 `blocked`。停止返回 `cancelled`，在模型请求和后续动作之间检查取消信号。已提交系统的动作可能已执行，不能靠取消撤销。
- Jev 仅接收文本/结构化界面。截图由 Pi 当前支持视觉的聊天模型处理。

## 测试入口

`npm test` 运行离线回归。`scripts/jev-smoke.mjs` 和 `scripts/jev-phone-smoke.mjs` 使用真实 API、只提交合成界面；它们记录所有检查结果，失败会返回非零状态，不用于证明通用中文准确率。

完整 Android 链路使用专用 `PhoneTaskFixtureActivity`：仅测试开启「演示开关」并将备注填写为「你好，Jev」，不会操作其他 App 或系统设置。

1. 启动 `node scripts/jev-device-fixture.mjs`。这个服务只模拟 Pi 的规划输出；APK 内 Jev 决策使用真实 TypeSafe API。
2. 安装 APK，并为隔离测试设备启用 Zfold 无障碍服务和悬浮窗权限。
3. 执行 `python3 scripts/provision-jev.py --serial TEST_SERIAL --test`。
4. 转发 `adb -s TEST_SERIAL reverse tcp:18873 tcp:18873`，运行 `adb -s TEST_SERIAL shell am instrument -w dev.zfold.assistant/.JevInstrumentation`。
5. instrumentation 重启进程会使同进程无障碍服务断开，需要在测试进程启动后重新绑定；共享/真实设备上应保留其他已启用服务。
6. 完成后移除 reverse 和 `no_backup/agent-smoke/jev.json`。最终返回值同时检查执行器的 verified 与原生控件实际状态。

所有实际结果和限制记录在 `VERIFICATION.md`。测试只验证当前模型版本和测试场景，不能外推为任意第三方应用可稳定完成任务。
