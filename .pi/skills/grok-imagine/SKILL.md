---
name: grok-imagine
description: 通过独立脚本复用橙橙Pi现有xAI OAuth探测Grok图片服务，不安装官方CLI、不使用API key。用户提到Grok生图、Imagine、Grok视频或订阅额度时使用。已实测图片与视频提交均返回额度/订阅门禁403；通用生成、视频完成/下载和具体计费尚待验证。
---

# Grok Imagine · Pi OAuth直接调用方案

## 用户指定的路径

不要安装官方Grok CLI。复用本机Pi已有xAI OAuth，由独立Node脚本调用媒体服务，做法与Codex生图Skill的宿主模式类似。目前已有最小OAuth图片探测脚本 `grok-oauth-probe.mjs`，复用官方Pi认证库，不安装CLI。它支持status及一次固定最小图片或视频提交请求；不是通用图片/视频执行器。

## 2026-10-09真实探测

用户明确授权测一次错误。使用现有xai OAuth调用官方images/generations一次，未重试，返回HTTP403：

- code：personal-team-blocked:spending-limit
- message：You have run out of credits or need a Grok subscription. Add credits at https://grok.com/?_s=usage or upgrade at https://grok.com/supergrok.

证明请求经过OAuth到达服务并触发账户额度/订阅门禁。不能区分额度耗尽还是缺少有效套餐，不能推算剩余额度或证明所有媒体计费规则。未生成图片。随后经用户明确授权测试视频：POST /v1/videos/generations，模型grok-imagine-video-1.5，6秒480p，单次提交也返回完全相同的HTTP403/code/message。未受理任务、未生成视频、没有轮询或重试。拒绝请求证明OAuth媒体提交的额度门禁可达，不代表成功生成/扣费/下载已验证。

脚本执行入口（经用户授权才使用probe）：
`runtime_exec` argv为 `["node", "/storage/emulated/0/pi/workspace/.pi/skills/grok-imagine/grok-oauth-probe.mjs", "--status"]` 或末参数 `--probe-image` / `--probe-video`。视频若意外返回request_id，仅报告submitted并保留ID，不报告completed；不得自动重提交。
脚本暂依赖本机私有dev-ci-local内SDK1.0.4；未打成独立bundle，私有构建依赖移除会失效。模型不得读取认证文件；脚本使用SDK listCredentials/getAuth读取刷新，禁止输出认证材料。403错误解析及令牌/邮箱脱敏用本地fixture检查通过。

## 已确认

- 本机Pi SDK1.0.4的xaiOAuth使用client_id b1a00492-073a-47ea-816f-4c329264a828，与官方Grok Build安装脚本公开OIDC scope中的client ID一致。
- Pi申请scope：openid profile email offline_access grok-cli:access api:access；xaiOAuth标明isSubscription=true，toAuth返回access作为授权材料。
- 官方图片API文档：POST https://api.x.ai/v1/images/generations，示例模型grok-imagine-image-2.0；可要求response_format=b64_json，避免额外下载远程URL。
- 官方视频协议见下列参考文档，异步生成必须区分提交、轮询完成、真实文件落盘。
- OAuth client/scope兼容证据不等于媒体端点权限和订阅扣费已验证。通用API文档说API计费，不能仅因为使用OAuth就断言免费或扣订阅。

## 实现与验证顺序

1. 独立脚本使用Pi官方认证库管理私有凭据读取/刷新；模型不得直接读取auth.json或接触令牌。不要复制认证到共享目录/命令行/日志，不更改Pi当前模型。
2. status仅输出oauth_ready/未登录/失效等非敏感状态，必须明确不是媒体权限或额度证明。
3. 严格只允许xai OAuth：不回退XAI_API_KEY、部署key、自定义中继或其他提供方。认证失败走橙橙供应商登录界面，不重新造登录协议。
4. 对图片/视频适配器先做本地HTTP夹具和参数/回执测试，再经用户明确授权做最小真实生成验证；不能拿用户令牌试探社区内部端点。
5. 核对服务对OAuth媒体请求的收费说明/回执。若仅能确认通用API付费或收费归属未知，必须先说明，不自动当订阅生成执行。
6. 请求独立operationId。超时/取消可能已被服务处理，保留request_id做只读恢复，禁止自动重提交。
7. 服务完成且文件存在、格式/大小校验通过才返回completed。产物保存当前工作区generated_images/generated_videos；图片消息用Markdown预览，文字文件链接用通用open，视频保留真实MP4。
8. 401/403/429分别报告登录、权限、限额；不换服务绕过。视频ZDR不允许擅自关闭隐私或创建收费存储。

## 资料

调研报告：../../../research/grok-cli-media/REPORT.md。
官方图片文档快照：../../../research/grok-cli-media/image-api.md。
官方视频文档快照：../../../research/grok-cli-media/video-api.md。
公开源码认证：Pi SDK1.0.4 pi-ai/dist/auth/oauth/xai.js、providers/xai.js；本轮没有读取用户认证文件。
官方CLI/社区源码仅作协议来源研究，不作为执行依赖。未经验证不能把社区内部账单RPC当稳定公开API。
