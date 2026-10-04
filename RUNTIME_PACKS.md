# Pi Runtime Packs

目标不是把 Termux 搬进 App，而是让 Pi 拥有自己的 Android/bionic Agent runtime：`/storage/pi` 只放数据，可信 native 工具通过版本化 Runtime Pack 安装到 App 私有 exec cache。

## 通道

- `modern`：`targetSdk=35`。`/storage` 是 `noexec`，Android W^X 禁止执行运行时写入的 ELF。适合当前正式版。
- `compat28`：`targetSdk=28` 侧载实验通道。真实 App 进程进入 `untrusted_app_27`，已验证可执行私有 runtime cache 里的 Android/arm64 ELF。

构建：

```sh
python3 scripts/build.py
ZFOLD_TARGET_SDK=28 python3 scripts/build.py
```

compat28 产物是 `artifacts/Zfold-<version>-compat28-debug.apk`。

## Runtime Pack 格式

`scripts/runtime-pack.py` 从 Termux main 仓库解析依赖、下载并校验 SHA-256、抽取 `data/data/com.termux/files/usr`、重根为 pack 的 `usr/`，并把脚本 shebang 里的 Termux 前缀替换成 `@@PI_RUNTIME_USR@@`。产物包含 `manifest.json`，声明包版本、入口、`LD_LIBRARY_PATH`、`GIT_EXEC_PATH` 等环境。

已验证的 Git 包：

```sh
python3 scripts/runtime-pack.py --name git-base --output artifacts/runtime-packs/git-base.tar.gz git
```

当前产物：

| Pack | 内容 | 大小 | SHA-256 |
|---|---|---:|---|
| `git-base.tar.gz` | Git 2.56.0，14 包 | 20.3 MiB | `96db91b38590bc50bbf6cce4f21972cbfb95c8965ee8a49e2d862f5a6bbe4d75` |
| `gnu-userland.tar.gz` | GNU coreutils/grep/sed/gawk/tar/gzip/xz/findutils/diffutils/patch/less，27 包 | 11.9 MiB | `cb0b5ac3945792b899d341ba0f0b6126eed0bfb608643ede97f1bda6642ecfc2` |
| `build-toolchain.tar.gz` | Clang/Clang++ 21.1.8、LLD/LLVM、binutils、make、CMake、Ninja、NDK sysroot、libc++、Python 3.14、`gcc/g++/cc/c++` 兼容别名，45 包 | 175.4 MiB | `62b597a3cc976e6b13a0882279c73c04d97922c067555ab2f77239bb0b69ea34` |
| `go-toolchain.tar.gz` | Go 1.27.1 android/arm64，16 包 | 190.6 MiB | `6b91694d9afe31b9343a868ec509d6adad8ccdb0b8c1947c9c22f6ddc629bdae` |
| `gcc-gnu.tar.gz` | GNU GCC/G++ 14.2.1、GNU binutils 2.44、GNU Make、glibc runtime，39 包 | 188.3 MiB | `aec0b97e5ca4bdf606c15c5c016cf9cdfc9e400bce073378fe0c0bb50cfc93df` |

## 工具链路线

1. `git-base`：已构建并执行 `git version 2.56.0`。
2. `gnu-userland`：已构建并执行 GNU coreutils 9.11 `sort --version`。
3. `build-toolchain`：已构建并执行 Clang++ 21.1.8；已编译并运行 C++ 样例输出 `pi c++ ok`。包内提供 `gcc/g++/cc/c++` 兼容别名，映射到 Android/bionic Clang；当前 Termux main 没有 GNU GCC 包，不能宣称 GNU GCC 驱动已完成。
4. `go-toolchain`：已构建并执行 `go version go1.27.1 android/arm64`。

### GNU GCC 状态

Termux glibc 仓库提供真实 GNU GCC 14.2.1 和 GNU binutils 2.44。`scripts/runtime-pack-glibc.py` 可构建 `gcc-gnu` 实验包：它会 patch interpreter、替换 ld script 中的 Termux 路径、写入 scoped 环境，并用静态 bionic launcher 启动 GCC/G++ driver。

模拟器已验证：

- `gcc --version` 输出 `gcc (GCC) 14.2.1`。
- C 编译输出可执行文件；把输出 interpreter 迁移到 App 私有 loader 后执行 `gcc_glibc_ok`。
- C++ 编译输出可执行文件；迁移 interpreter 后执行 `gxx_glibc_ok`。
- 关键修复包括：`runtime_exec` 使用 manifest `environmentScope` 注入 glibc `LD_LIBRARY_PATH`；GNU GCC driver launcher 删除 bionic `LD_LIBRARY_PATH` 并把 `argv[0]` 改成真实 glibc GCC；安装器只对严格 UTF-8 文本替换 Termux prefix；`cc1/cc1plus` 只 patch interpreter，不使用 RPATH，避免大型 ELF 被改坏。

当前还不把 `gcc-gnu` 标记为默认发布路径：它依赖 compat28、glibc scoped 环境和静态 launcher。生产可用路径仍是 bionic Clang 兼容别名。

2026-10-04 复验：修复 `.deb` 相对 symlink 被错误折叠、ELF 被当 UTF-8 文本改写、大型 `cc1/cc1plus` RPATH patch 损坏等问题后，`gcc-gnu` 安装不再报 tar 错误。scoped 环境下 GNU GCC/G++ 可编译 C/C++；产物 interpreter 指向 App 私有 loader，设置 `LD_LIBRARY_PATH` 后分别输出 `gcc_glibc_ok` 和 `gxx_glibc_ok`。

安装入口在 App 的“手机能力 → Runtime Packs”。它会列出 `/storage/pi/runtime-packs/*.tar.gz`、大小和 SHA-256；用户确认后安装。Agent 不能静默安装 pack，只能通过 `sandbox_status` 查询已装包，并用 `runtime_exec` 执行。`runtime_exec` 支持裸命令名，会解析到 `usr/bin`；Runtime Pack 环境自动注入。

## Runtime 软件包管理

单个工具不再要求先做 Runtime Pack。App 内包管理器直接使用 Termux stable aarch64 官方仓库，安装根为应用私有 `files/runtime/pkg`；`/storage/pi` 仍只保存 workspace、Skills 和用户数据。流程保持用户确认、HTTPS 索引/包、SHA-256 校验、依赖解析、私有目录解压和 prefix 重写。删除时按安装清单处理共享文件，避免破坏仍被依赖的库。

Pi 可通过 `pkg` 搜索、列出、刷新、安装和删除；`runtime_exec` 会按 `usr/bin`、`libexec` 和安装清单解析裸命令。兼容通道 APK 内置 `xz/liblzma` bootstrap，只用于解开 Termux `.deb`，不放开 workspace 内任意 ELF 的执行。已验证 `vim -> vi`、`kubectl version --client=true`。Termux 仓库版本会持续变化，结果以当时索引和哈希为准。

## 当前验证

模拟器 Android 16 arm64：

```sh
adb shell am instrument -w dev.zfold.assistant/.RuntimeExecInstrumentation
adb shell am instrument -w dev.zfold.assistant/.SandboxInstrumentation
adb shell am instrument -w -e class dev.zfold.assistant.PackageManagerInstrumentation -e action search -e query kubectl dev.zfold.assistant/.PackageManagerInstrumentation
```

compat28 结果：cat 执行进入 `u:r:untrusted_app_27`；Git 输出 `git version 2.56.0`；GNU coreutils 输出 9.11；Clang++ 输出 21.1.8；Python 执行 `pi_python_ok`；Go 输出 `go version go1.27.1 android/arm64`。用户侧 Node/npm/npx 由 `pkg install nodejs` / `pkg install npm` 提供。

2026-10-04 复验真实 UI 安装路径：侧边栏 → 手机能力 → Runtime Packs 可扫描 `/storage/pi/runtime-packs`，展示名称、安装状态、短哈希和大小；确认页显示完整 SHA-256，确认后解压到私有 cache 并写入 `installed.json`。UI 安装的 `gnu-userland` 在同一 App 私有目录执行 GNU `sort` 成功。运行时 instrumentation 使用 `usr/bin` 约定；Go 的 `usr/bin/go -> ../lib/go/bin/go` 相对 symlink 会继续限制在 runtime cache 内解析。

最终 APK SHA-256：modern `6f10dcf2fe778040d26bf4deef6cba0015be7d4a69c13dd7bd142569246ff059`；compat28 `f8d8300878776a743d9f579db55ffbe61cf75e809caf83a77c610dd964fdd367`。
