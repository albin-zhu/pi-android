# 当前 App 承载 Ubuntu / omp 的架构评估（feat/omp-termux-app）

性质：探索分支已实现手机工具桥与 Ubuntu 实验安装器，在隔离模拟器及 Zfold SM-F9660 真机安装运行；不是 GUI 产品切换。用户接受重新登录，本轮不共享凭据或会话，不依赖独立的 `com.termux` App。

## 结论

1. **Ubuntu 是 Linux 用户空间，不是独立虚拟机**：PRoot 翻译系统调用和文件路径，仍使用 Android 内核；guest 的“root”不授予真实 root 权限，也不绕过 Android UID、SELinux 或应用权限。已在模拟器运行 Ubuntu 24.04.5、Linux ARM64 Bun 1.4.3 和 omp 18.10.0；这不保证 Zfold 内核和后台限制也通过。
2. **同 App 启动的 Ubuntu 可以通过 IPC 请求手机操作**：Android 侧负责相机、无障碍、联系人等 API 和授权；Ubuntu 侧运行 omp，使用工具扩展连接 App 的原生能力桥。无需让 Ubuntu 直接访问 Android Framework，也无需独立 Termux App。
3. **手机工具桥已接通独立 Ubuntu 进程**：`PhoneToolServer` 校验真实同 UID，`PhoneToolDispatcher` 复用原生执行、取消、HUD 和用户接管；omp 扩展注册与内嵌 Pi 共享的 18 个手机工具定义。Ubuntu 客户端已读到真实 Android 型号和权限状态。先前“手机能力不可达”的判断只适用于未桥接的独立 App，不能套用于当前目标。

## 与既有自托管方案对比

| 维度 | 当前 App 内 Ubuntu + omp（实验分支） | 既有自托管 `files/runtime/omp` |
| --- | --- | --- |
| Linux ABI | Ubuntu / Bun / omp 已在 Android ARM64 模拟器运行；真机待验收 | 已用 glibc Bun 启动并进行部分调用，不代表全部功能验收 |
| 安装与更新 | Ubuntu Base / Bun 下载固定 SHA256，omp 固定 18.10.0；独立安装、不打进 APK、不复制主机 node_modules | 已有安装树；依赖裁剪与更新完整性仍需验证 |
| DNS/网络 | 验证 guest DNS、证书、代理和目标 provider，不假定开箱即用 | 现有 CONNECT 代理路径 |
| TUI | 仍由 App 的终端承担输入与渲染，Ubuntu 不会自动改善终端能力 | 当前 App 终端 |
| 手机能力 | 同 UID abstract Unix socket 已接通真实 Android 工具；默认逐次确认，可在手机能力中显式开启“始终允许” | 内嵌 Pi 已有工具桥；独立 omp 尚不能因此自动调用手机工具 |
| 凭据/会话 | 用户接受重新登录，由 Ubuntu 内 omp 管理；本轮不设计跨引擎同步 | omp 与内嵌 Pi 的存储并不天然合一 |
| 后台存活 | Android 服务管理运行进程与工具端；PRoot 不消除 Android / 三星后台限制 | 既有守护与独立 omp 的生命周期整合仍需验证 |

## 原生能力桥方案

### 所有权与通信

- Android App 拥有权限、无障碍服务、相机生命周期、用户确认和原生工具执行。
- Ubuntu 内 omp 拥有 agent 推理与自身会话；通过 `pi.registerTool(...)` 的工具扩展发起请求，不修改 omp 的 Linux native ABI 来调用 Android API。
- `PhoneToolServer` 使用 `LocalServerSocket`（abstract Unix socket），在 PRoot 外校验 `getPeerCredentials().getUid()` 等于 App UID。PRoot 不创建独立网络命名空间；已从真实 Ubuntu / Bun 子进程完成握手与手机状态调用。
- 服务端在 PRoot 外检查真实 UID，不能以 guest 的 `id` 输出作为授权依据。客户端也不能把 guest 假 root 的 `getuid()==0` 当成服务端身份判断依据。
- `ZFOLD_PHONE_SOCKET` 经受控启动环境传入；4 字节大端长度 + UTF-8 JSON，单帧上限 4 MiB，参数上限 64 KiB；限制连接、消息读写期限与并发，只开放既有 18 个手机工具，不开放 runtime_exec/pkg/pty 或任意 Java 调用。
- 同 UID 校验阻止其他 App 接入，但不隔离同 App 内运行的任意脚本。手机能力授权和敏感操作确认必须在 Android 端执行；PRoot 不是不可信代码的安全沙箱。

### 复用边界

- `runtime/native-tools.mjs` 已采用传输无关的 `send` / `receive` 接口：复用 `tool_request`、`tool_result`、`tool_heartbeat`、`tool_cancel` 和停止未确认状态。
- `native/bridge.cpp` 目前是内嵌 Node 使用的匿名管道，且设置了 `FD_CLOEXEC`；新启动的 Ubuntu 进程不会自动获得现有桥。新增独立 socket 传输，不把它直接接到整个 Pi 命令通道。
- `PiRuntime.dispatch` 已改为共用 `PhoneToolDispatcher`，复用停止、HUD 和 UI 让位；独立请求与内嵌手机任务互斥，busy 直接返回，不排队重放。默认 device_info、phone_capabilities、app_list 不弹窗，其他读取/变更逐次确认；“手机能力 → 独立客户端手机工具”可选择“始终允许”，经风险提示后持久保存。它允许同 UID 的 Ubuntu/omp/其他脚本，不授予缺失的 Android 系统权限。
- 改回“每次确认”对下一次请求立即生效，包括已有连接；不撤回已经接受的在途调用。工具白名单、同 UID 校验、忙碌互斥、取消和用户接管始终有效。
- 授权设置验收：模拟器真实 SMS 状态查询免确认、已有连接撤销后恢复确认、白名单不被绕过均通过；真机已通过设置界面开启并验证重启后保留，guest 的 SMS 状态查询和 screen_windows 无逐次确认即可返回。未拨号或发送短信，CALL_PHONE 系统权限仍为未授予。重启验证暂停的既有无障碍服务配置已恢复，读屏调用已重新成功。
- `NativeToolExecution` 的取消与心跳继续复用；服务端为连接和请求生成独立 ID，返回客户端原始 ID。断开后取消在途调用，客户端没有终态回执时返回执行不确定，而非成功或自动重试。
- `PhoneActions` 已有权限请求和相机 Activity。拍照可能切换到 Android 界面；无障碍读屏/点击仍需要用户启用服务。Ubuntu 不免除这些条件。
- `ubuntu.sh` bind 工作区数据根到 `/workspace`、无密钥的 `runtime/phone-tools` 到 `/opt/pi-phone`，以及专用 `runtime/x11` 到 `/run/zfold-x11`、其 socket 目录到 `/tmp/.X11-unix`。X11 的 cookie 只在 App 私有目录和该 guest 挂载内共享，不经公共存储传递。`ZFOLD_PHONE_PATH_MAP` 显式转换工作区路径，图片只进入工具的 image content，不塞入文本或 details。没有映射的路径明确标记为 Android 主机路径。不要绑定整个 App 私有目录或通过公共存储传凭据。
- 若后续需要定位等现有工具未覆盖的能力，仍需在 Android 侧实现和授权；socket 本身不会生成新能力。

### 实施与验收顺序

1. 在当前 App 内启动选定 Ubuntu 用户空间，验证 omp 的 native 加载、实际模型请求与 TUI；这与能力桥是两个独立验收点。
2. 独立进程接入能力桥，读取真实 `device_info` / `phone_capabilities`，验证同 UID 放行与跨 UID 拒绝。
3. 通过 omp 工具扩展实际完成读屏、一次用户批准的操作及拍照，验证图片和路径在 guest 中可消费。
4. 验证取消、用户接管、App/guest 退出与重连：保留真实回执和不确定状态，不自动重放点击、电话或短信。

## 实验使用

以下设备和 DNS 地址只适用于本次模拟器；真机必须指定已连接的 ADB 设备及其可用 DNS。

```sh
env ZFOLD_TARGET_SDK=28 python3 scripts/build.py
adb -s emulator-5580 install -r artifacts/Zfold-0.2.87-compat28-debug.apk
python3 scripts/setup-ubuntu.py --serial emulator-5580 --dns 10.0.2.3
```

打开 App 的新终端，在任意当前目录执行：

```sh
ubuntu
phone call device_info '{}'
omp
```

- `ubuntu` 是 APK 复制的启动脚本与 PATH 内符号链接；设置执行权限，不依赖 ADB 临时导出的 PATH。
- 安装器通过私有目录 stdin 传输，既有 rootfs 不覆盖；全新 tar 解压将硬链接实体化，避开 SELinux 硬链接拒绝。未完成的安装目录不会被悄悄删除。
- PRoot 的 `PROOT_TMP_DIR`、`PROOT_LOADER`、`PROOT_LOADER_32` 指向当前 App，不借用硬编码 `com.termux` 路径。guest 环境不继承宿主的 Android 动态库搜索路径。
- Bun 安装使用 `--backend=copyfile --exact`，不删依赖来缩小体积；默认硬链接后端在本次 PRoot 环境出现 ENAMETOOLONG，不能据其“installed”日志宣称完整安装。
- 安装器等待当前 App PID 对应的运行环境就绪，避免读到上一进程留下的 socket 配置。
- `omp` guest 入口自动加载 `/opt/pi-phone/omp-phone-extension.mjs`。初次安装不迁移授权；后续按用户明确要求，已通过官方 auth export/import 合并本机 openai-codex OAuth、xai/Grok OAuth、litellm API key，并合并 LiteLLM provider 配置。没有覆盖 agent.db、迁移旧会话或修改内嵌 Pi 凭据。

### Ubuntu 链接交给 Android 打开

安装器在 guest 的 `/usr/local/bin/xdg-open` 安装原生打开入口，调用 `/opt/pi-phone/phone-open.mjs`，复用同 UID 手机工具桥的 `open`。无需 Linux 桌面、浏览器或新增 HTTP 服务。调用 `xdg-open` 的终端链接立即走 Android 系统 handler；不改终端鼠标/链接识别逻辑。

```sh
xdg-open 'https://example.com/'
xdg-open '/workspace/workspace/artifacts/report.pdf'
xdg-open './包含 空格的图片.png'
```

- URL 交给 Android；相对文件路径按调用进程 cwd 解析，`file://` URI 先解码，再复用 `ZFOLD_PHONE_PATH_MAP`。只有实际绑定的工作区可打开；`/root`、`/tmp` 等未映射路径明确失败，不复制到公共目录，也不上传文件。
- 保留手机工具确认与系统权限。APK 仍需 Android 确认安装，不把“已弹确认框”当作“已安装”。无文件 handler 而回退到目录时返回非零，避免错误报告文件已打开。
- 退出码：0 为 Android 接收打开操作或弹出 APK 确认；1 为参数/路径/连接/拒绝等失败；2 为执行结果不确定；130 为取消。连接中断不会重放操作。
- 已热部署到当前 Ubuntu：新增 opener 模块及 guest 命令，未重装 APK、重启 App 或终端。opener 是独立新增文件，旧 APK 启动时复制既有资源不会覆盖它；源码 bundle 与安装器也已纳入后续构建。
- 真机通过实际 `xdg-open` 命令验证 Chrome 打开带 query/fragment 的 HTTPS 地址，工作区 PNG 由三星相册显示。截图保存在工作区 `artifacts/android-xdg-open/`；不将命令回执单独视为画面成功。Bun 执行 opener 与 socket 回归共 8 项通过；尚未逐个验证所有 Android URI scheme 或第三方应用。


## 终端字形与内联图片

Ubuntu 不改变 Android 终端的绘制能力。本轮直接修复 App 的终端实现，不迁移 GUI，不更改 Pi / Node / omp 版本或供应商配置。

- **字形比例**：旧 `TerminalRenderer` 对宽度不匹配的 fallback 字形只缩放 X，导致圆形变窄。现在只对该字形等比缩小、居中，保留字格布局和原始纵横比；宽图标被限定为一格时会更小。背景及 block / underline / bar 光标不参与缩放，测量 Paint 不继承上一段粗体/斜体绘制状态。
- **图片协议**：实现 iTerm2 `OSC 1337;File=inline=1;...:BASE64` 的 PNG/JPEG，支持 BEL / ST 结束和分段 PTY 输入。支持正整数列/行、`Npx`、百分比、`auto` 尺寸与 `preserveAspectRatio`；名称仅为元数据，不读取本地路径、URL 或触发下载。不支持 Kitty、Sixel、GIF 动画和 iTerm2 分块文件传输。
- **布局与生命周期**：图片所属字符格随普通历史、行复制和重排移动；字符覆盖、清行、清历史、reset 释放对应图片引用，主/备用屏幕分离。图片绘制遵循手动历史视口；超过内存/数量预算会淘汰旧图，不能承诺无限图片历史。改变字格像素尺寸时清除旧图片，等待应用重新绘制。
- **资源预算**：单个压缩图片最多 8 MiB、头部 1024 字符；解码前限制为 4,194,304 像素、任一边不超过 8192 像素。主/备用缓冲区合计保留最多 32 MiB bitmap、64 张图；单次放置不超过 65,536 字格。相同内容和尺寸的存活图片复用解码结果，普通文字行跳过图片逐格扫描。
- **能力声明**：仅交互 `TerminalSessions` 默认导出 `PI_FORCE_IMAGE_PROTOCOL=iterm2`，`ubuntu.sh` 透传；`TERM` 仍是 `xterm-256color`。没有给嵌入 agent 和一次性执行环境强制开启图片。新环境只对新终端及其新启动的 omp 生效；可在该 shell 中用 `export PI_FORCE_IMAGE_PROTOCOL=off` 后重启 omp 禁用。

验收使用最终 compat28 APK 和 ARM64 模拟器 `emulator-5580`：

```sh
adb -s emulator-5580 shell am instrument -w dev.zfold.assistant/.TerminalGlyphInstrumentation
adb -s emulator-5580 shell am instrument -w dev.zfold.assistant/.TerminalImageInstrumentation
adb -s emulator-5580 shell am instrument -w dev.zfold.assistant/.TerminalScrollInstrumentation
```

三项 instrumentation 均返回 `INSTRUMENTATION_CODE: -1`。字形检查覆盖 12/18/24/32/48px 的四种圆形、实际像素比例、完整背景/光标及粗斜体 ASCII/CJK/组合字符；旧 X-only 绘制与新绘制对比图为 `artifacts/terminal-render/terminal-glyphs-24.png`。图片检查经真实 shell PTY 传输超过普通 OSC 长度的 PNG，覆盖分段输入、ST、JPEG 精确像素尺寸、历史视口、擦除、主/备用分离、reset 和畸形/超限输入；截图为 `artifacts/terminal-render/terminal-image-checker.png`。既有历史重绘、追加输出锚点、恢复底部跟随检查仍通过。

另以本机已安装的 **omp 18.10.0 `Image.render()`** 生成相机 JPEG 的实际输出序列，通过真实 Android PTY 回放，并从 App 正常导航进入终端执行 `cat` 显示该序列；照片、图标及中英文在实际屏幕绘制，完整截图为 `artifacts/terminal-render/terminal-image-screen.png`。这证明 omp 图片组件与终端协议兼容，不是一次真实 provider 推理回合；未调用付费模型或读取/迁移凭据。

正常交互终端执行 `printenv PI_FORCE_IMAGE_PROTOCOL` 实际输出 `iterm2`，截图为 `artifacts/terminal-render/terminal-capability.png`。本次加载的模拟器快照缺少 PRoot，因此未在这个快照中重跑 Ubuntu 内完整 omp 会话；不能把上述图片回放宣称为 guest 会话验收，也不表示真机已有 Ubuntu 缺失或被改动。

本轮真机未连接：`adb devices -l` 仅列出模拟器，`adb mdns services` 无发现。APK 已生成 `artifacts/Zfold-0.2.87-compat28-debug.apk`，但这些渲染修复尚未覆盖安装至 Zfold，三星实际字形与图片显示仍需真机验收。

## 已有证据与未验证项

- 实现入口：`PhoneToolServer.java`、`PhoneToolDispatcher.java`、`PhoneToolApproval.java`、`PhoneToolApprovalActivity.java`、`runtime/phone-socket-client.mjs`、`runtime/omp-phone-extension.mjs`、`runtime/ubuntu.sh`、`scripts/setup-ubuntu.py`。
- 扩展使用实际 omp 18.10 的 `api.typebox.Type`、五参数 execute 和生命周期事件。原版 Ubuntu omp 通过本地确定性模型协议夹具完成真实 device_info → screen_read → camera_capture 回合，输出 `PHONE_BRIDGE_VERIFIED`；型号、Settings 界面节点和相机结果来自 Android，不是夹具伪造。没有调用付费 provider。
- 已验证：APK 编译签名、Ubuntu Base 官方 SHA256、Bun 官方 SHA256、全新 rootfs 解压后的 Perl/压缩程序运行、Ubuntu 内 omp --version、独立客户端实际型号/权限状态。
- 手机工具 instrumentation 通过：真实设备状态、工具白名单、拒绝敏感读取、取消/断开未批准操作、外部/内嵌互斥、零/超大/半帧拒绝。通过跨 UID ADB 转发尝试，非 App peer 未收到协议握手。
- 验收发现并修复两项行为问题：LocalSocket 先 shutdown 再 close，确保部分帧期限确实解除阻塞；授权界面使用独立临时任务，结束后返回原目标 App，避免批准后读不到 Settings。拒绝后的前台保持已加入永久回归。
- 独立观察器使用 UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，点击真实 Android 授权按钮。默认 uiautomator dump 会抑制其他无障碍服务，不可一边 dump 一边宣称读屏能力失效。
- 实际相机返回 768×1024 JPEG；guest 从映射路径读到完整 JPEG 首尾标记。产物：`artifacts/ubuntu-omp/phone-camera.jpg`。
- 在真实 App 终端中从宿主 `/` 执行 `ubuntu`，再从 guest `/tmp` 启动 `omp`，登录向导正常渲染；截图：`artifacts/ubuntu-omp/app-terminal-tui.png`。未使用真实 OAuth 登录。
- 既有 RuntimeLifecycleInstrumentation 通过：实际内嵌 Pi 的 35 秒工具调用、PTY、执行环境、取消/超时对子进程的清理，以及有界输出和 stdin EOF。不是仅靠编译推断原 GUI 路由未退化。
- JavaScript 回归 175 项中 172 通过；3 个失败在未改动的 session-catalog/session-workspace/subagent-files 用例，本轮不修改这些模块。不能宣称全套回归通过。
- Zfold 真机已通过新地址 10.30.208.21:42761 覆盖安装实验 APK并安装 Ubuntu 24.04.5 / Bun 1.4.3 / omp 18.10.0。guest 的真实 device_info 返回 samsung SM-F9660、Android 16、无障碍和浮窗权限均已开启。保留应用数据，不迁移 OAuth；真机读屏/拍照交互、真实 provider 登录/请求和三星长时后台仍未验收。使用当前 WiFi DNS 10.20.105.10，而不是模拟器 DNS。
- 授权同步核验：目标导出的凭据与源记录一致，models.yml 权限 600。Codex access 未过期，Grok access 已过期但保留 refresh token，刷新可用性未验证。本机没有独立 openai API key 记录，未虚构该授权。真机访问 LiteLLM /models 返回 HTTP 200，发现 107 个模型；未发送付费推理请求。秘密只经内存和私有 stdin 传输，不写公共存储。OAuth 两端并行刷新可能使旧令牌失效。
- PRoot 官方说明：<https://proot-me.github.io/>，说明其用户空间路径/系统调用翻译、假 root 与共享宿主内核行为。

## 决策建议

采用“当前 App 的 Android 能力服务 + App 内 Ubuntu / omp + 同 UID 工具桥”。本分支已按用户要求安装至真机供实验，不是 GUI/TUI 产品切换；内嵌 Pi 1.0.4 / Node 24.21.0 的依赖和构建通道保持不变。真机交互及长时稳定性验收仍需分别完成。
