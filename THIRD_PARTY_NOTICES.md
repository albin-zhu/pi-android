# Third-party components

- Pi coding agent / pi-ai **1.0.4**, MIT: https://github.com/earendil-works/pi
- Node.js Mobile **24.21.0-0**, Node.js and mobile modifications under MIT and bundled third-party licenses: https://github.com/fogtape/nodejs-mobile/tree/v24.21.0-0
- Android NDK libc++ runtime **28.0.13004108**, Apache 2.0 with LLVM exceptions: https://github.com/llvm/llvm-project
- Phosphor Icons Regular, MIT: https://github.com/phosphor-icons/core — SVG source is retained in `third_party/phosphor/` and converted to native VectorDrawable resources.
- sherpa-onnx Android AAR **1.13.8**, Apache 2.0: https://github.com/k2-fsa/sherpa-onnx — pinned to SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`; only arm64 native libraries are packaged.
- Silero VAD int8 ONNX model, MIT: https://github.com/snakers4/silero-vad — pinned to SHA-256 `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20`.
- sherpa-onnx Chinese keyword spotting Zipformer int8 model, Apache 2.0: https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01 — release archive pinned to SHA-256 `b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f`; only int8 encoder/decoder/joiner and tokens are packaged.
- Kotlin standard library **1.9.25**, Apache 2.0: https://github.com/JetBrains/kotlin — pinned to SHA-256 `f9cdcdbff1f5de85380ae526977e683726c2aa42db1ed6e6e50ae89e496e95fd` for sherpa-onnx's Android classes.
- Marked **18.0.11**, MIT: https://github.com/markedjs/marked — Markdown preview parsing; the locked dependency license is bundled in APK assets.
- npm dependency versions and integrity hashes are pinned in `package-lock.json`.

The Android runtime is an embedded shared library. This application does not require Termux, Office, a desktop agent, root, or a local control server. Model inference may use the endpoint configured by the user.

The build places dependency license texts in APK assets under `licenses/`. The development runtime archive is downloaded from the release above and its SHA-256 is checked before packaging:

`e3cd29a1be03405f11dd5c857af8cd3ad13f84f1409ea648f5328f0bada5bd76`
# Embedded terminal

Terminal emulator and view components from Termux v0.118.3 (commit
5b657c6adf4304e5198951ce815fe0205dcac29c), under the upstream Apache 2.0 exception.
See `third_party/termux-terminal/README.md`, `UPSTREAM-LICENSE.md` and
`third_party/Termux-Terminal-LICENSE.txt` for provenance and local changes.

## Embedded X11 and optional Windows runtime

- [Termux:X11](https://github.com/termux/termux-x11/tree/fa3a8b430e2896a19f44c99a9cb056254615ae06), commit `fa3a8b430e2896a19f44c99a9cb056254615ae06`, GPLv3. `libXlorie.so` is built from pinned source and submodules with local overrides in `third_party/termux-x11/overrides/`: per-window Composite backing storage, multi-Surface rendering, window lifecycle and input routing. `scripts/x11_native.py` verifies the inputs and records the modified library hash, toolchain and overrides; there is no unmodified prebuilt engine fallback. The Java frontend and Unix-only network guard are local integration code. Exact source revisions, build inputs and source-conveyance requirements are recorded in `third_party/termux-x11/`. Notices are packaged in `assets/x11/licenses/termux-x11/`.
- xkeyboard-config **2.29**, Ubuntu `xkb-data 2.29-2`, multiple permissive licenses retained in `third_party/xkeyboard-config/` and `assets/x11/licenses/xkeyboard-config/`. The complete installed keyboard data tree is packaged, not just a single layout.
- Optional [Wine 10.0 WoW64 build](https://github.com/Kron4ek/Wine-Builds/releases/tag/10.0), Wine under LGPL-2.1-or-later; [Box64](https://github.com/ptitSeb/box64), MIT, Android package revision `b99d443` from [box64-debs](https://github.com/ryanfortner/box64-debs). These binaries are downloaded only after installation confirmation, not bundled into the APK. `runtime/windows.py` pins URLs and SHA-256 digests; upstream archives and package copyright files are retained in the private installation/cache. Dependencies have their own licenses.

The combined application is licensed under GNU GPL version 3; see the root `LICENSE` (also packaged as `assets/licenses/Project-GPL-3.0.txt`). Copyright (C) 2026 pi-android contributors. Integration modifications include the Android frontend, independent display service, Unix-only network guard, Ubuntu mounts, optional Wine launcher, modified native per-window rendering and one Android document task per X11 primary window. Third-party components retain their respective copyright and license notices.

The application's corresponding source is available at https://github.com/albin-zhu/pi-android; use the commit identified by the APK's CI run or release. The X11 engine is GPLv3: a notice or upstream link alone is insufficient for binary redistribution. Provide equivalent access to the complete corresponding source of the combined application, including pinned native submodules, local modifications and build/install scripts, and comply with applicable license/installation-information requirements. `third_party/termux-x11/SOURCE.txt` describes the source tree and retrieval procedure. This integration does not include Warcraft III or other proprietary application files.
