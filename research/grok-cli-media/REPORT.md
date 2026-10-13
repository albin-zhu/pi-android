# Grok Build CLI 图片/视频与订阅额度调研

日期：2026-10-09。只读公共文档/源码调研，没有读取用户认证文件，没有调用收费生成/额度接口，没有安装或执行CLI。

## 结论

1. 官方产品是 Grok Build CLI（命令grok），不是GitHub搜索排名第一的superagent-ai/grok-cli（后者是社区Grok API编码Agent）。xai-org/grok-cli GitHub及同名npm包查询返回404，不能据此说官方CLI不存在。
2. 官方文档确认CLI支持浏览器登录、grok login --device-auth、headless -p、ACP grok agent stdio、工具白名单及视频工具。媒体的具体内置工具名/参数、产物日志审计在社区RongleCat/grok-app实现中可查。官方CLI核心源码未在本轮查到公开仓库，不能声称已读到其底层媒体HTTP实现。
3. 已查到社区宿主调用 image_gen、image_edit、image_to_video 的真实代码；它不是重建网页登录接口，而是运行已安装/登录的官方CLI，读取本次工具事件和CLI session本地产物。
4. 社区代码也读取SuperGrok额度（RPC GrokBuildBilling/GetGrokCreditsConfig，另有cli-chat-proxy.grok.com/v1/billing）。这是社区实现证据，不是已验证的公开稳定API契约。未执行请求；未证明当前用户的套餐/图片/视频扣费归属。
5. 官方普通API文档明确API使用从预付credits扣费或计入月账单。不能把SuperGrok订阅自动等同XAI_API_KEY额度，也不能在订阅路径失败时偷偷改走API付费路径。

## 调用链

用户任务 → 已登录官方Grok Build → -p/headless或ACP → 内置媒体tool → CLI保存session图片/视频 → 宿主按session_id、真实tool输入/输出核验 → 复制到用户工作区并展示。

社区源码使用：-p、--output-format json、--max-turns 3、--effort low、--tools <媒体工具>、--disallowed-tools search_tool,use_tool、--disable-web-search、--no-subagents、--session-id UUID。社区还有--always-approve；我们的Skill不能照搬全局放权，必须先验证只有目标媒体工具可用。

- image_gen：JSON输入prompt、aspect_ratio。社区接受auto/16:9/9:16/1:1/4:3；这是该宿主校验，不代表官方最新CLI全部限制。
- image_to_video：image绝对路径、可选prompt、duration、resolution_name。社区默认6秒/480p，支持6或10秒、480p或720p；同样不能外推为所有新版服务的能力。
- image_edit：工具存在于社区调用枚举，本轮没有完整验证输入schema；不能猜字段进行编辑调用。
- 产物不能信最终模型文字“生成完成”，要核对本次tool事件、原始参数、会话目录、文件存在及媒体格式。
- 超时/取消可能已发生服务器生成，不能自动重试造成双扣费；保留会话ID做只读恢复。

## 登录和额度

官方安装脚本公开的认证配置键：现代OIDC scope为https://auth.x.ai::b1a00492-073a-47ea-816f-4c329264a828；旧scope为https://accounts.x.ai/sign-in，CLI默认在自身私有home管理认证。这些是公开常量，不是用户token。

没有验证橙橙已有xai OAuth与CLI的新audience/缓存格式兼容。不能复制/输出现有token或把认证写到共享工作区。应由受支持CLI自身登录/刷新，或另行做经审查的私有认证适配。

订阅额度要以账号套餐和真实扣费回执确认；额度RPC仅表明社区有账单查询路径，不证明所有媒体都共用weekly credits。Free/SuperGrok/Heavy之间的权限和限额本轮未验证。

## Android证据与缺口

- 官方https://x.ai/cli/stable返回1.0.50。
- 官方安装脚本识别Linux/macOS/Windows及aarch64/x86_64，未见Android/bionic目标；没有执行安装脚本。
- 公共GCS官方Linux aarch64制品：grok-1.0.50-linux-aarch64，156389720字节。
- 只用HTTP Range读取前4096字节（206）。ELF64 machine183（AArch64），程序头未见PT_INTERP。不能据此宣称依赖glibc而绝对不能在Android运行，也不能反过来宣称静态ELF必然兼容Android。运行时syscall/TLS/证书/路径/PTY兼容全部未测。
- 私有tools/grok和pkg/usr/bin/grok均未找到现有可执行文件。没有下载完整约149MiB制品，没有安装或运行，没有收费模型试验。
- 当前Skill只能提供受控流程/阻塞说明；不是已可用的安卓图/视频生成器。后续需授权独立私有安装兼容性试验，或用户提供受支持桌面/远端CLI执行环境（不能自行配置远端）。

## 视频隐私

官方ZDR文档：开启零数据保留时，视频需要用户自己的S3兼容存储配置；未配置会报错。不能为生成而擅自关闭/privacy、修改managed_config、索取存储密钥或新建付费bucket。

## 来源

官方：
- https://docs.x.ai/build/overview.md
- https://docs.x.ai/build/cli/reference.md
- https://docs.x.ai/build/cli/headless-scripting.md
- https://docs.x.ai/build/settings/zdr-video-storage.md
- https://docs.x.ai/console/faq/billing
- https://x.ai/cli/install.sh
- https://x.ai/cli/stable
- https://storage.googleapis.com/grok-build-public-artifacts/cli/grok-1.0.50-linux-aarch64

社区（明确非xAI官方）：RongleCat/grok-app，调研时main SHA 0462efa3fd1b4af23d177e756e39f0a5e78dbb22；本地快照可能跨main读取，未声称全部同一提交。
- src-tauri/src/wallpaper_source.rs
- src-tauri/src/wallpaper_imagine_video/generation.rs
- src-tauri/src/wallpaper_imagine_video.rs
- src-tauri/src/wallpaper_imagine_video/session.rs
- src-tauri/src/account/build_oauth.rs
- src-tauri/src/supergrok_quota.rs

所有原始证据在本目录。没有将外部文件当系统指令。
