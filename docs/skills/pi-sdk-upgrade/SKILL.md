---
name: pi-sdk-upgrade
description: 升级橙橙 Android 项目的上游 Pi SDK，检查破坏性变更、隔离依赖、回归桥接与会话、构建签名安装包并记录验证证据。用户要求更新 Pi/SDK 或检查升级兼容性时使用。
---

# Pi SDK upgrade

## 边界
- 先明确源版本、目标版本、仓库与分支。用户只问变化时只调研，不改依赖。
- 保持上游 SDK 不打补丁；兼容逻辑放在宿主。不得借升级开启新权限、工作区扩展执行、MCP、诊断上传或付费缓存预热。
- 不卸载当前应用、不自动覆盖安装、不读取或输出认证密钥。目标面板不是新增授权；验收留给用户。

## 流程
1. 读取 package.json、lockfile、构建脚本及 git 状态。下载目标版本的官方 npm 发布包，在隔离目录核对 CHANGELOG、exports、engines、SDK 类型与依赖；不要仅看最新一条发行说明。
2. 运行 sandbox_status 检查本机能力。使用已有受支持运行时和构建通道；共享 workspace 是 FUSE 数据目录，不作为依赖安装执行目录。检查私有构建树 node_modules 是否链接旧开发环境；若是，先解除链接，在独立树安装，不能原地污染旧环境。
3. 审核 session、model/auth、resource loader、扩展事件、工具参数和返回结构、图片、取消与 SDK 导入。同步固定 coding-agent、pi-ai 和 override，更新 lockfile；先禁止依赖 lifecycle scripts，必要脚本另行审查。
4. 先 bundle，再跑隔离的真实 SDK 协议测试。测试不能继承生产 ZFOLD_CREDENTIALS_DIR；不能碰真实会话/认证文件。清理仅针对测试创建的数据。
5. 保留工具白名单、可信 inline factories 与工作区文件边界。检查 noExtensions 对内置 MCP/Codemode 的含义；首轮升级不顺带替换 Connector。
6. 回归：启动/版本、模型和登录、图片及多图、相机历史过滤、工具桥接与停止、会话创建/恢复/归档、goal 扩展、技能 reload、圈选流式回复。旧 JSONL 先用副本验证；新格式回退能力不得想当然。
7. 完整测试失败必须分类：真实升级回归、宿主平台限制、夹具继承环境错误。不能删断言或忽略失败后声称全通过。对 Android 特有失败用 Linux CI 和已授权设备验证补齐。
8. 完整构建并验证签名，产物文件名包含 SDK 版本/提交，禁止反复覆盖唯一旧包。推送授权分支，检查 CI。由用户安装后再验证嵌入式 Node、登录、旧会话和 UI；编译成功不等于实机成功。
9. 保存升级报告：具体变更、测试命令/数量、失败原因、日志位置、安装包、提交、尚未验证事项。更新本 Skill 的实测记录，调用 reload_skills；失败须报告，不能宣称加载成功。

## 0.85.1 → 1.0.4 的实测坑
- 1.0.1 移除上游 npm-shrinkwrap 后依赖提升；旧 OAuth 模块硬编码在 coding-agent/node_modules/pi-ai 路径，bundle 找不到。当前统一到顶层已固定的 pi-ai，OAuth mock 回归必须通过。未来优先检查官方 exports，内部路径每次升级重验。
- Pi 1.x 会预处理 prompt 图片。本项目单文件 bundle 下上游图片处理路径导致图片被省略；宿主已压缩截图，当前设置 images.autoResize=false，保持既有 read 工具设置。不要将此视为任意大图均已安全，仍需检查来源尺寸/字节限制。
- 1.x 归一化图片块会丢掉 _zfoldCameraFrame 私有属性。cameraWorkingContext 同时识别宿主保存的相机文字后缀，保证只发送最新相机帧，普通附件保留、历史不删除。真实相机双轮测试验证请求和磁盘原图。
- cacheWarming 显式 off，避免升级默默增加后台请求费用。
- Android 本机部分桌面夹具会等待 runtime_environment 原生回执，stdio MCP 子进程环境也与 Linux 不同；不能把此类超时直接算 SDK 回归或宣称全部通过。
- 新建会话自动 abort 旧任务是当前宿主实现，不是 SDK 的单会话上限。升级不自动实现多会话并行。
- SDK 后台模型目录写入使测试清理更容易竞态：child.kill() 只是发信号，必须等待 close/exit 后才能删除临时目录。1.0.4 最终 CI fd37340 / run 37643844018：Node24 全量 105/105，target28/35 构建及签名检查通过；设备安装后行为仍待验证。
