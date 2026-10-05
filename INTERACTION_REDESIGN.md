# 手机工作台交互重做 · 0.2.70 历史审计

> 0.2.71 补充：真机系统弹出视图暴露了本次验收遗漏的 239 × 282 dp 窗口。下面的“通过”仅对应当时列出的自绘浮层和模拟器场景，不能推及 OEM 系统小窗。新版改为系统管理常驻窗口，并补充 `SystemWindowInstrumentation` 实际 freeform task 验证。

用户目标：让程序员能把手机当作本地 Agent 工作台；重做难用的 PTY、澄清 MCP 的添加与使用、在其他应用上通过浮窗看屏幕，并统一导航、浮窗和状态交互。用户拿走真机后，本轮所有设备操作均显式指定 Android 模拟器。

采用 apple-design 的即时反馈、可中断操作、空间一致性与清楚命名。本轮已完成逐项审计。版本 0.2.70，分支 `codex/runtime-foundation`。本文件按需求组织；历史试验、失败与修复过程保存在 `VERIFICATION.md`。

## 逐项证据

下列日志及已查看的截图位于 `artifacts/interaction-redesign/`，对应的测试实现位于 `android/src/dev/zfold/assistant/`、`tests/` 和 `scripts/`。没有把编译成功或模拟回调当作完整工作流成功。

| 原始需求 / 交互要求 | 当前实现 | 已检查的证据 | 审计结果 |
| --- | --- | --- | --- |
| PTY 可以直接输入、控制与编辑 | 真正的 Termux VT 解析/渲染、IME 输入、UTF-8、控制键、全屏应用；JNI PTY broker 管理进程 | `foreign-prefix-after.txt`：实际输入、分片中文、Ctrl+C、Vim 编辑与退出；`terminal-visible-cursor.txt`：复制、旋转、字体、会话管理 | 通过 |
| 输入 bash 不再因外来启动文件报权限错误 | 精确适配 Termux 全局启动文件路径，保留用户 HOME 启动配置；系统 Shell 可用，Bash 可从界面安装 | `foreign-prefix-before.txt` / `foreign-prefix-after.txt`：真实存在的外来应用私有目录下复现与修复；`terminal-install-and-background.txt` 中首装、裸 bash 与后台步骤通过，后续复制步骤的失败另行修复 | 通过；未把该部分日志整体当作全通过 |
| 离开终端不丢掉运行中的会话 | 会话与视图分离，前台服务维持用户启动的终端；显式结束仅终止所选会话 | `terminal-visible-cursor.txt`：HOME → 返回同一 PTY，命令完成；新建/切换/单独关闭 | 通过 |
| 横屏和大字时仍能使用终端 | 紧凑状态收起标题栏，管理动作移入按键栏；行数与实际可见区域一致 | `terminal-visible-cursor.txt` 断言光标在视图内；`terminal-landscape-visible-cursor.png` 已核对输出、提示符与光标 | 通过 |
| MCP 能从明确入口添加并使用 | 工作台 → 连接；远程 HTTP/SSE、本机 stdio、JSON 导入；私有认证、状态、错误恢复、工具目录与手动调用 | `connector-android.txt`：表单、认证失败修正、调用、导入、重连、删除、Android Node stdio、OAuth 回跳；`tests/connectors.test.mjs` 覆盖恢复、PKCE、SSE、超时与取消 | 通过 |
| Agent 真正能用连接的工具 | `connector_tools` 按需发现，`connector_call` 调用，不预塞全部服务 schema | `tests/connector-runtime.test.mjs`：真实 bundled Pi 的发现 → 调用 → 回答回合；认证 Token 未进入模型请求 | 通过 |
| 悬浮状态看其他应用 | 主界面带草稿进入浮窗；ScreenContext 统一显式按轮截图，隐藏助手窗口，失败不发送旧图 | `screen-regression.txt` 七项：独立应用红/蓝两种画面与真实 Pi 图片哈希、受保护窗口失败、关闭后无新图；`system-entry.txt` 验证系统助手捕获外部应用 | 通过 |
| 收起、关闭、停止各自含义清楚 | 收起保留入口与草稿，关闭移除入口和看屏幕模式，停止任务是独立动作 | `workbench-android.txt` / `workbench-large-font.txt`：界面往返、草稿保留、关闭后真实 Pi 回合完成 | 通过 |
| 浮球跟手、吸边可中断、尊重减少动画 | 抓取偏移不变，释放按当前速度吸边，再按下立即接管；关闭动画时直接停靠 | `workbench-android.txt` / `workbench-large-font.txt` 的真实输入事件；`bounds-after.txt` 六项边界与旋转检查 | 通过 |
| 不覆盖系统栏、旋转后仍可点击 | 全屏坐标系中显式限定状态栏、导航栏及切口安全区 | `bounds-before-detail.txt` 复现 y=0 越入状态栏；`bounds-after.txt` 横竖屏边缘、旋转后重新约束和重新点击通过；`floating-safe-area.png` 已查看 | 通过 |
| 键盘不遮挡输入与发送 | 按系统可见 IME 区域布置浮窗；空间有限时紧凑编辑；避免横屏输入法全屏接管 | `keyboard-{portrait,landscape}-{1.0,1.3}.txt`：实际 1080×2400 / 2400×1080 与几何检查，四张截图均核对 | 通过；测试关闭一次性的 Gboard 字体提示后检查正常布局 |
| 系统助手与普通浮窗不相互打架 | 单一可见入口、保留文本与图片草稿、工作台返回原入口；圈选利用已授权无障碍浮层 | `system-entry.txt` 七项、`circle-interaction.txt` 三项；后者无额外悬浮窗授权且实际触摸形成透明自由选区 | 通过 |
| 点击头像打断语音 | 完整语音页、浮窗与通话状态条都绑定打断控制器 | `voice-avatar-all.txt` 六项：可点击、进入监听、忙碌回合可插话、旧语音抑制 | 通过；合成忙碌回合，不声明麦克风声学效果 |
| 导航、前提与状态可理解 | 工作台按任务分组；启动时保留打开意图，离开则取消；权限缺失给系统设置入口；取消模型设置回原工作台；关键按钮至少 48dp | 普通 / 大字体工作台往返与截图；`final-navigation.txt` 九项通过，包含工具目录返回键关闭 | 通过 |

## 设计取舍与范围

- Connector 是服务连接，Skill 是操作说明，终端是持续 shell；它们在工作台中有各自明确的入口。
- 看屏幕是用户显式开启的逐轮截图，不是持续录屏。API 34+ 使用当前目标窗口；旧系统的整屏回退仍保留，但本轮不声明 API 30–33 的兼容性已全面验证。
- 本轮验证的是 Android 16 模拟器上的上述完整工作流。真实第三方账户策略、所有 OEM 差异、进程被杀后的任务恢复与长会话性能专项属于兼容性或后续运行时工作；没有把这些未来能力写成已经完成。
- 当前模拟器为 `Pi_Workbench_QA` / `emulator-5554`。原 `Medium_Phone_API_36.1` 已正常关闭。没有访问用户拿走的真机。

## 复核入口

- `npm test`：当前 57 项，最终结果 `acceptance-node-tests.txt`。
- `ZFOLD_TARGET_SDK=28 npm run build`：个人使用完整执行版本，最终构建记录 `final-build.log`。
- `FloatingBoundsInstrumentation`：边界回归；`InteractionInstrumentation`：拖动、导航与真实 Pi 生命周期。
- `scripts/emulator-keyboard-matrix.py`：真实横竖屏 × 字体矩阵。
- `scripts/screen-context-fixture.py` 与 `scripts/screen-context-model.mjs`：独立目标应用和受控模型端点；仅供验收，不是应用运行依赖。

## 最终产物

- APK：`artifacts/Zfold-0.2.70-compat28-debug.apk`，versionCode 270，targetSdk 28，arm64-v8a。
- SHA-256：`1da769b5b2192c3ad40632b69066e51b01c8a24579d7cb85944c3cb25ddfd022`。
- 最终 57 项 Node 测试、六项边界测试、九项导航/生命周期测试通过；之前各工作流的验收范围及截图已逐项复核。
- 本轮需求无剩余实施项；上文列出的跨版本、真实账号和进程恢复事项不作完成承诺。
