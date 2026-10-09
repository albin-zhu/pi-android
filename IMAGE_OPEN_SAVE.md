# 图片查看、保存与通用外部打开 · 2026-10-09

本轮用户授权：文字文件链接应走通用open，点图片也能保存到本地。没有安装/提交/合并/推送授权。

## 实现

- `OpenTargets` 是消息文字链接、ArtifactProvider.open包装、AndroidTools.open的共同外部打开实现。不再针对图片文字链接拦截成内部Dialog。
- 本地文件使用App自有read-only ArtifactProvider content URI，ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION + ClipData，而不是试图给外部应用授予App不拥有的DocumentsProvider权限。
- 支持 /storage/pi、/sdcard/pi、/storage/emulated/0/pi 别名和file URI；canonical路径限制在用户数据根，文件必须存在，目录可打开。目录Documents URI包含真正的pi目录层级，不设置App无法提供的grant flag。
- APK保留确认入口；无文件处理应用时回退包含目录。opened只是调用startActivity成功，不代表外部应用已成功读取或用户已看到。
- 点图片预览仍在App内查看。大图页有关闭、保存到本地、本地文件的外部打开按钮；外部打开选择器也复用OpenTargets。
- 保存只在用户点击后触发，后台解码/复制，保存期间禁用按钮。MediaStore Images写入Pictures/橙橙，无新增存储权限，适用于App minSdk30。
- 原文件复制原字节；用户图片使用收到的base64原始字节，不从显示缩略图重压缩。PNG/JPEG等MIME由真实解码信息确定。64MB上限、独立名称、不覆盖原文件。
- IS_PENDING写入/完成，写入异常删除新建残片，失败明确提示，不把失败当保存成功。
- 没有远程图片下载能力；只保存当前本地图片/已有附件，不自动下载或访问网络图片URL。

## 验证

- 26/26定向Node检查通过（图片卡/历史投影、open/save源码契约、思考档位、附件插入），日志 artifacts/image-open-save-tests.log。新增open/save是源码契约检查，不是Android运行时MediaStore/权限操作验证。
- target28完整native/resources/javac/d8/签名成功，日志 artifacts/image-open-save-build.log。
- 签名证书SHA256 192ee603469e9f3a289c7cea7d223228565f51faf38dd82f27b421a477a08530，保持原应用签名。
- APK artifacts/Zfold-0.2.85-compat28-image-open-save.apk；SHA256 d9af1c5e635394cfea0e7a67b45b9cab780c032ec6f692ae0a8e3ed3a77b8185。
- 保留fish默认终端和subagent，以及上轮chat-images预览功能。不修改SDK/凭据/原会话。

## 待安装后真机验证

1. 点女战士缩略图进入大图；保存一次，在相册Pictures/橙橙确认并比对原PNG字节。
2. 点同一文件的文字链接/大图外部打开：外部图片应用能读取，无UID权限错误。
3. 用户附件图片保存、关闭/返回、窄屏/大字、重复点击、低存储失败。
4. 非图片/APK确认/目录回退的通用打开回归。以上尚未验证，不报验收。

## GitHub同步记录（2026-10-09）
用户授权sync后，本轮同步至feat/official-subagent，不合并main。提交前组合定向回归40/40通过（含源码契约及本地模拟服务），完整回归的既有失败记录保留；不代表图片保存/外部权限真机验收。Grok Skill与脱敏OAuth图片/视频403探测记录一并纳入仓库，不包含认证文件、生成媒体或APK。
