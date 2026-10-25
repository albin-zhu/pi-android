# Zfold 打包 Scratch：当前手机环境

配套：[工作流](zfold-packaging.md)。这些路径是当前 Ubuntu 的缓存候选，启动任务时重新确认；仓库文件和实时输出优先，不据此声称任何未来构建已通过。

## 固定边界

- 主工作目录：`/workspace/workspace/pi-android`。不要切换到旧仓库编译业务源码。
- 平台：Android 上的 Linux ARM64 Ubuntu。空间有限，不复制 rootfs、node_modules、工作树或多套 SDK。
- 主应用 `dev.zfold.assistant` 承载 Ubuntu。安装主 APK 会结束相关进程，默认只交付 APK。
- OMP 项目 Agent：`.omp/agents/zfold-packager.md`。Scratch 用仓库文件路径引用，不假设有 `scratch://` 协议。

## 原签名

| 用途 | 路径或规则 |
| --- | --- |
| 当前构建默认 key | `.build/debug.keystore` |
| 保留的已有备份 | `/workspace/debug.keystore` |
| 权威说明 | `SIGNING.md` |
| Git 排除 | `.gitignore` 的 `.build/`、`*.keystore`、`*.jks` |

缺 key 不生成新 key，不再默认要求用户 ADB 配对或重新导出。恢复既有备份前后核对公开证书指纹；不搬走原文件。仓库在共享存储，chmod 600 的实际结果曾为 660，不能把忽略规则或文件权限请求当安全隔离。

## 已确认存在的工具链候选

| 用途 | 当前位置 |
| --- | --- |
| Android SDK | `/tmp/android/sdk` |
| android.jar | `/tmp/android/sdk/platforms/android-35/android.jar` |
| AAPT2 | `/tmp/android/sdk/build-tools/35.0.0/aapt2` |
| zipalign | `/tmp/android/sdk/build-tools/35.0.0/zipalign` |
| D8 | `/tmp/android/sdk/build-tools/35.0.0/lib/d8.jar` |
| apksigner | `/tmp/android/sdk/build-tools/35.0.0/lib/apksigner.jar` |
| Sherpa Java 类 | `/tmp/mainbuild/deps/sherpa-classes.jar` |
| Kotlin 依赖 | `/tmp/mainbuild/deps/kotlin.jar` |
| Node 24 ARM64 | `/root/pi-android-omp-termux/.build/toolchains/node-v24.22.0-linux-arm64/bin/node` |
| 已安装 JS 依赖 | `/root/pi-android-omp-termux/node_modules` |
| native 缓存候选 | `/root/pi-android-main/.build/rootless/native` |
| X11 资产候选 | `/root/pi-android-main/.build/assets`（目前包含 x11/xkb.zip 与许可） |

`/tmp` 路径可能在重启或清理后失效。AAPT2 / zipalign 可能是链接，确认目标可执行，不根据 stat 显示的链接大小判断损坏。Java/javac/keytool 使用当前实际工具链；完整构建脚本需要正确的 JAVA_HOME，未设置时它默认指向 macOS Android Studio。

JS 打包示例，在当前项目 cwd 执行：

```sh
ZFOLD_NODE_MODULES=/root/pi-android-omp-termux/node_modules \
/root/pi-android-omp-termux/.build/toolchains/node-v24.22.0-linux-arm64/bin/node scripts/bundle.mjs
```

`scripts/bundle.mjs` 会核对 Pi 依赖版本。不要为“尽快成功”跳过核对，也不要让 npm install 顺手更新锁文件。

## native 缓存与失效规则

当前候选目录实际列出 12 个库：libXlorie、libzfold、libsherpa-onnx-jni、libonnxruntime、libc++_shared、libsherpa-onnx-cxx-api、libnode、libzfold_lzma、libsherpa-onnx-c-api、libzfold_pkg、libzfold_x11_guard、libzfold_xz（均为 `.so`）。这只是目录清单，不是永远固定的打包数量断言。

权威构建输入包括 `native/`、`scripts/build.py`、`scripts/x11_assets.py`、`scripts/x11_native.py` 及其引用的第三方版本／overrides。旧仓库可用于比较缓存的对应输入，但不能替代当前源码；版本字符串相同也不能证明二进制匹配。历史 native 核验范围见 `VERIFICATION.md` 的 0.2.88 与 X11 章节；本次仍须确认后续相关输入未改变。

Java、Manifest、res、runtime 变动分别使对应的 classes/dex、资源链接、资源编译、JS bundle 失效。尤其不要照搬 `.build/popup/ProbeManifest.xml` 或任何 `*-probe-*` 产物来生成主包。

## 最近一次产物只是参考

- 记录：`.build/text-preview/apk.json`，可能被用户清理。
- 产物：`artifacts/Zfold-0.2.93-compat28-text-preview.apk`。
- 当时大小：58,342,784 bytes。
- 当时 SHA-256：`d62c45ff61b2489dc7b111f8336024d08ba3b2cf54310ebc5a3edb00bab9b1b0`。
- 当时状态：编译和原签名等产物校验通过；用户要求停止真机验收，不能声称 TXT／JSON 实机渲染已通过。

新任务读取当前 package.json，不复用上面的版本号、文件大小或摘要作为本次结果。优先查明确的缓存目录，不遍历整台手机，也不为找 key 再翻全部聊天历史。
