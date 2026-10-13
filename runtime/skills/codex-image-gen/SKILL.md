---
name: codex-image-gen
description: 在 Zfold 手机上通过独立 Node 脚本使用 Codex 官方 ImageGen（gpt-image-2）生成或编辑图片，复用手机 OpenAI Codex OAuth。用户提到 Codex 生图、二次元图片、文生图或参考图编辑时使用。无需 Pi 扩展。
---

# Codex Image Gen · Zfold 脚本版

这是独立脚本方案，不是扩展。不要查找 `codex_imagegen` 工具、扩展目录或 MCP 生图服务，也不要要求 /reload、重启或新建会话来加载扩展。

## 执行入口

使用现有 `runtime_exec` 工具调用 Node，脚本路径固定为：

`/storage/emulated/0/pi/.pi/skills/codex-image-gen/codex-image-gen.cjs`

脚本是数据文件，由 Node 解释执行；不要 chmod 或直接执行共享存储中的文件。
Node 已安装时直接调用。若确实提示未安装，通过 `pkg` 查找 nodejs 并说明用途、征得用户同意后安装。

## 认证检查

```json
{"argv":["node","/storage/emulated/0/pi/.pi/skills/codex-image-gen/codex-image-gen.cjs","--status"],"timeoutMs":60000}
```

`oauth_ready` 只表示 OAuth 可用，不能证明生图权限或额度。脚本通过认证库自动读取、刷新手机应用私有凭据。模型不得读取 auth.json，不得索取、输出、复制令牌或把令牌放到命令行和共享存储。未登录时引导用户在 Zfold 的供应商设置中登录 OpenAI Codex，不使用桌面的 /login 指令。无需 API key 或 Codex CLI。

## 生成图片

1. 将需求编译为完整视觉提示词：主体、动作、环境、构图、光线和颜色。用户已明确要求生成时不要再次确认。
2. 使用 `write` 将提示词写入**当前工作区绝对路径**的普通 UTF-8 文件，例如 `/storage/emulated/0/pi/workspace/image-prompt.txt`。有参考图时先用 `read` 检查图片。
3. 调用如下（工作区路径有变化时使用当前实际路径）：

```json
{"argv":["node","/storage/emulated/0/pi/.pi/skills/codex-image-gen/codex-image-gen.cjs","--prompt-file","/storage/emulated/0/pi/workspace/image-prompt.txt","--output-dir","/storage/emulated/0/pi/workspace/generated_images"],"timeoutMs":360000}
```

生成可能需要数分钟。等待调用结束，不把正常延迟当成失败。

## 编辑图片

在上述 argv 中追加 `--reference` 和参考图绝对路径，可重复至多五次。支持 PNG、JPEG、WebP，每张不超过 32 MB。提示词明确要保留和修改的内容。

脚本不直接访问当前会话图片：优先使用附件对应的本地文件路径；拿不到路径时请用户提供文件或重新附图，不编造参考图，不传 `num_last_images_to_include`。

## 结果与失败

- 成功返回 JSON：`status: completed`、`operation`、`savedPath`。只有返回成功且文件存在，才能声称生成成功。
- 用 `read` 查看 savedPath；通过现有 `open` 工具打开实际 savedPath 向用户展示（遵守该工具参数定义）。图片不是脚本自动内联展示的，不要声称已显示。
- 简短报告生成或编辑完成及保存位置，保留原始 PNG。
- 401：在 Zfold 重新登录 OpenAI Codex；403：账号无生图权限；429：额度限制。不要自动切换服务。
- 网络超时可能已经被服务器处理，不自动重复提交，以免重复消耗额度。
- 安全拒绝如实说明，不绕过提供方限制。遵守适用内容政策；人物年龄或内容不明确时避免未成年人性化。

## 维护

此 Skill 及脚本一起安装；只复制 SKILL.md 不完整。更新后调用 `reload_skills` 重新读取说明即可，无需安装扩展或更新 APK。若脚本文件缺失应明确报告安装不完整。
