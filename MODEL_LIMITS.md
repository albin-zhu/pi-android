# 自定义网关模型参数修复

用户授权核对并修复llm.moontontech.net七个模型。models?model_group=<id>&include=all公开接口返回保存在workspace artifacts/gateway-model-audit，未做付费生成测试。

## 修复范围

runtime/custom-model-limits.mjs保存本次核实快照，仅匹配https://llm.moontontech.net指定模型；不是自动网络同步器，网关元数据变更须重新核对。contextWindow保守使用max_input_tokens，不擅自把输入/输出相加当总窗口；maxTokens取min(max_tokens,max_output_tokens)，Qwen不一致取65536，其131072上限语义尚未进一步确认。

|模型|contextWindow|maxTokens|
|---|---:|---:|
|glm-5.3-flashx|1000000|128000|
|gpt-6-astra|922000|128000|
|qwen3.8-max|991000|65536|
|claude-fable-5-1|1000000|128000|
|claude-sonnet-5|1000000|128000|
|claude-opus-5-5|1000000|128000|
|deepseek-flash|1048576|393216|

persistProviderSelection保存时合并已有条目，不再丢失model.api/name/reasoning/input/compat/cost等字段。只对已核实网关七个模型更新容量；其他已有模型保留原参数，其他新模型暂仍使用旧默认值，此处尚未实现通用元数据发现。

本机私有models.json七个模型容量已按核实结果原子更新，未更改密钥、协议、选定模型或认证；容量旧值备份仅含provider/id/两个数字字段，保存在私有models.json.limits-before.json（未复制凭据到共享目录）。正在运行引擎可能缓存旧模型，需要安装新包并重新打开App；旧版本保存模型仍可能覆盖参数。

## 原始验证和交付记录

新容量/主机范围/已有字段保留3项测试，与思考5项组合8/8通过。compat28完整bundle/native/Java/dex/资源/原签名构建成功，保留768MiB内置Node堆上限和fish/subagent/图片功能。
APK workspace/artifacts/Zfold-0.2.85-compat28-model-limits.apk
SHA256 371219e80c37741c7a3c9244b87b69a3fbf6887aef90e8cf4262b14fc3cbfcd6
Signer SHA256 192ee603469e9f3a289c7cea7d223228565f51faf38dd82f27b421a477a08530，与原包一致。

初次交付时未自动安装、提交或 push；后续主分支合并验证见 `VERIFICATION.md`。没有验证完整1M输入或最大输出实际可用性，未验证长会话自动摘要恢复。本次解除4096本地上限后，SDK默认reserveTokens16384时摘要预算最多floor(0.8*16384)=13107，仍不是模型全部输出容量；不能保证所有摘要永不截断，也不证明解决JS堆OOM。
