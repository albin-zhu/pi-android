# Zfold Codex ImageGen 脚本 Skill

不注册 Pi 扩展或新工具，不修改 APK。现有 `runtime_exec` 运行已安装的 Node，解释执行共享存储中的 CJS 数据文件。

## 文件

- `runtime/codex-image-script.mjs`：请求、参考图、PNG 保存；固定官方 Codex ImageGen endpoint 和 gpt-image-2。
- `scripts/codex-image-gen-entry.mjs`：CLI 与 ModelRuntime OAuth 接入；凭据留在手机私有目录，不进入 argv/stdout/shared storage。
- `scripts/bundle-codex-image-gen.mjs`：将认证库与脚本打包为 `.build/codex-image-gen.cjs`，无需手机 npm 依赖；统一 pi-ai 实例，避免嵌套依赖的 OAuth loader 未注册。
- `runtime/skills/codex-image-gen/SKILL.md`：手机脚本版说明，不覆盖 Mac 上的扩展版 Skill。

## 部署

```sh
node scripts/bundle-codex-image-gen.mjs
adb -s DEVICE shell mkdir -p /storage/emulated/0/pi/.pi/skills/codex-image-gen
adb -s DEVICE push .build/codex-image-gen.cjs /storage/emulated/0/pi/.pi/skills/codex-image-gen/codex-image-gen.cjs
adb -s DEVICE push runtime/skills/codex-image-gen/SKILL.md /storage/emulated/0/pi/.pi/skills/codex-image-gen/SKILL.md
```

先备份既有 Skill；脚本必须与 Skill 一起发布。手机需 compat28 通道、已安装 Node 和 OpenAI Codex OAuth。Agent 使用 `runtime_exec` 调用 Node 时自动注入包管理器环境；不要直接执行共享存储文件。修改说明后调用 `reload_skills`，并让 Agent 用 `read` 重新读取 Skill，避免继续使用会话中旧的扩展版说明。

脚本默认认证位置是主应用 `/data/user/0/dev.zfold.assistant/no_backup/credentials`；其他应用/测试环境可在进程环境设置 `ZFOLD_CREDENTIALS_DIR`，不应传令牌。脚本通过 SDK 读取并按需刷新凭据，不打印原始认证异常或 HTTP 响应正文。

仅检查 OAuth：`node /storage/emulated/0/pi/.pi/skills/codex-image-gen/codex-image-gen.cjs --status`。这不调用生图接口，不验证账号权限/额度。

生成：`--prompt-file FILE [--reference FILE ...] [--output-dir DIR]`。成功输出 JSON 中的 `savedPath`，Agent 需自行 read/open 展示。没有扩展版的会话图片自动读取或内联图片输出。

## 2026-10-09 验证

- 新增专用单元测试 6/6：CLI 校验、无网络状态检查、生成与编辑请求、输出保存、错误脱敏、无效图片和符号链接目录拒绝。
- Fold7 SM_F9660：在应用 UID 下运行已安装 Node 与正式脚本，返回 `{"status":"oauth_ready","entitlementVerified":false}`，未打印凭据、未调用生图接口。
- 手机已同步脚本与新版 Skill，两文件 SHA-256 与本机一致；原始说明备份为 `SKILL.extension-version.md.bak`。
- 未覆盖安装 APK、重启应用或提交生图请求；Agent 的 runtime_exec→实际生图→read/open 全链路仍待实际生成验收。
- 全项目测试 129 项，123 通过、6 失败（bundled runtime、catalog、goal extension、session cwd 等既有区域）；不能据此宣称全项目通过。Mac `/var` 与 `/private/var` 路径差异出现在部分失败中。
