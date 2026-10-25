# Zfold 打包 Scratch：工作流与交付

入口：项目 Agent `.omp/agents/zfold-packager.md`。本文件供新会话、打包子 Agent 和人工维护者共用，不含密钥。

## 调用

在本项目 OMP 会话中直接说：

> 让 zfold-packager 用当前源码打一个 compat28 APK，只打包和校验，不安装、不做真机测试。

父 Agent 使用 `task` 时选择 `tasks[].agent: "zfold-packager"`，不是在任务描述里只提名字。任务必须明确：源码修改已结束、目标渠道、是否已改版本、允许的写入范围。保持当前 cwd，不开启隔离 worktree。一个专门的打包任务应在代码整合后运行，不让多个实现子任务各打一次包。

示例任务参数（不是要求现在执行）：

```json
{
  "context": "cwd=/workspace/workspace/pi-android。源码已收敛，无其他构建在写产物。保留用户修改，仅打包，不安装、不测试。",
  "tasks": [{
    "agent": "zfold-packager",
    "name": "ZfoldPackage",
    "task": "读取 Agent 的 Scratch 与 SIGNING.md，使用当前源码版本生成 arm64 compat28 APK，完成产物校验，返回路径、摘要、签名和日志。不要修改业务源码或版本号。",
    "solutionSpace": "构建目标和原签名固定；依据本机可用且匹配的缓存选择构建路径。"
  }]
}
```

Agent 不固定模型，沿用调用会话的模型选择；不为本角色修改全局模型、凭据或 OMP 配置。`.omp/agents/` 是 OMP 项目级发现目录；这不是 APK 内旧 Pi 只读 subagent 的角色配置。新会话可从 `/agents` 查看，已有会话的 task 执行会重新发现定义。

## 先核对

1. 读取 `SIGNING.md`，原 key 默认 `.build/debug.keystore`。公开证书 SHA-256 必须为：
   `192ee603469e9f3a289c7cea7d223228565f51faf38dd82f27b421a477a08530`。
   使用 keytool 导出公开证书计算 SHA-256，禁止把 keystore 原始字节读入聊天。别名与口令约定只从签名文档查，不在日志打印密钥。
2. `package.json` 与 lockfile 根版本一致。versionCode 沿用 `scripts/build.py` 的 `major*10000 + minor*100 + patch`；不要硬编码最近一次版本。
3. 明确渠道。默认 compat28，Manifest targetSdk 与 `runtime/runtime-channel.json` 均为 28/compat28；modern 则均为 35/modern。包名保持 `dev.zfold.assistant`，不是独立验收包。
4. 根据 [本机环境](zfold-build-environment.md) 检查工具、依赖、磁盘空间和缓存。不要先全盘搜索或下载依赖。已有路径失效时再做有界查找。
5. 记录实际构建输入和缓存来源，确认其他任务已停止修改相关文件。不能将“文件存在”当成“它与当前源码匹配”。

## 选择构建路径

### 完整构建环境

规范流程在 `scripts/build.py`，JS 打包在 `scripts/bundle.mjs`。只有主机架构、JAVA_HOME、SDK、NDK host 工具及依赖确实可用时才运行完整构建。

```sh
ZFOLD_TARGET_SDK=28 python3 scripts/build.py
```

这是完整构建命令，不保证适用于本机 ARM64 Ubuntu：当前脚本的 Linux NDK host 路径固定为 linux-x86_64。不要为了让命令运行而跳过 native 步骤、伪造输出或替换原签名。

### 当前 ARM64 手机的缓存构建路径

这里记录已采用的实际工序，不是另一个会自动执行的构建脚本。使用现有工具完成每一步，按需用 eval 组织文件列表、子进程和 ZIP；每步检查退出码并保留日志。

1. 选择匹配源码的 native 缓存；native 源码、X11 overrides、构建参数或固定依赖变化时，不复用旧 `.so`。对比缓存对应的源代码和已有构建记录，不能只比较 mtime 或库数量。无法证实时报告缺少 provenance。
2. JS/runtime、Markdown 资源或依赖变化时，使用已有 Node 和 `ZFOLD_NODE_MODULES` 运行 `scripts/bundle.mjs`。只有能证明输入未变时才复用生成资产；不要把旧 assets 当当前实现。
3. 从当前 `android/AndroidManifest.xml` 生成目标渠道的临时 Manifest，不从旧 APK 或旧验收 Manifest 复制；为本次打包生成匹配的 runtime-channel.json。
4. 用 AAPT2 编译当前 `android/res`，再 link 当前资源和 assets，生成 resources.apk 与 R.java。只有资源输入可证明不变时才允许复用 resources.zip；不得沿用上个版本的最终 resources.apk。
5. javac 编译当前 `android/src`、`third_party/termux-terminal/src` 和新生成的 R.java。参数沿用 source/target 11、UTF-8；classpath 为 android.jar、sherpa-classes.jar、kotlin.jar。使用新的 classes 输出，防止已删除类残留。
6. D8 使用 android.jar、min-api 30，将所有本次 `.class` 以及 sherpa/kotlin 依赖转为 dex。参数用实际 argv 列表；不要把每个路径包裹引号再写进 D8 参数文件。
7. 以本次 resources.apk 为基础，加入本次 dex 与核验过的 `lib/arm64-v8a/*.so`。只写入构建要求的资源，不打包 `.build`、Scratch、keystore 或日志。
8. 签名前执行 `zipalign -P 16 -f 4`；用 `java -jar <apksigner.jar> sign` 和原 key 签名到新的最终 APK，禁用不需要的 v4 idsig。不得直接覆盖正在交付的旧 APK。
9. 按下方完成校验；失败只修正相应构建步骤，禁止将失败后的旧文件当作新产物。

## 交付所需校验（不是功能验收）

- ZIP 完整性检查：`ZipFile.testzip()` 返回 None。
- `apksigner verify --verbose --print-certs` 成功，公开证书指纹与 `SIGNING.md` 一致。
- 签名后的 APK 执行 `zipalign -c -P 16 4` 成功。
- `aapt2 dump badging` 确认主包名、当前版本、versionCode、targetSdk、启动器；APK 中 native ABI 为 arm64-v8a。
- 核对 APK 内 runtime-channel.json 与 Manifest 渠道一致，且包含本次 dex/运行时所需资产。
- 计算最终签名 APK 的 SHA-256 与字节数。检查后不再修改 APK。
- 保存 `.build/packager/` 下的命令日志与非敏感产物元数据。不要将完整环境变量、模型凭据或私钥写入日志。

默认没有单元测试、真机 UI、测试包、ADB install、phonecontrol 或上传。编译与签名通过不代表 UI／业务功能通过。用户明确追加安装时，先指出会终止主应用和 Ubuntu 会话，再在授权范围内交接或执行。

## 清理与失败

只删本次自建中间 APK 和临时脚本。保留最终 APK、日志、`.build/debug.keystore`、旧 APK、外部缓存及用户文件。不得用 `rm -rf .build` 做“清理”。

报错交付包含失败命令、退出状态、关键报错、具体缺失路径及日志。需要业务代码修改时交还实现方；未得到用户同意，不扩大为依赖升级、构建系统重写或全量设备测试。

权威引用：[签名](../../SIGNING.md)、[构建脚本](../../scripts/build.py)、[JS 打包](../../scripts/bundle.mjs)、[验证记录](../../VERIFICATION.md)。
