# Third-party components

- Pi coding agent / pi-ai / pi-agent-core **0.85.1**, MIT: https://github.com/earendil-works/pi
- Node.js Mobile **24.21.0-0**, Node.js and mobile modifications under MIT and bundled third-party licenses: https://github.com/fogtape/nodejs-mobile/tree/v24.21.0-0
- Android NDK libc++ runtime **28.0.13004108**, Apache 2.0 with LLVM exceptions: https://github.com/llvm/llvm-project
- Phosphor Icons Regular, MIT: https://github.com/phosphor-icons/core — SVG source is retained in `third_party/phosphor/` and converted to native VectorDrawable resources.
- sherpa-onnx Android AAR **1.13.8**, Apache 2.0: https://github.com/k2-fsa/sherpa-onnx — pinned to SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`; only arm64 native libraries are packaged.
- Silero VAD int8 ONNX model, MIT: https://github.com/snakers4/silero-vad — pinned to SHA-256 `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20`.
- sherpa-onnx Chinese keyword spotting Zipformer int8 model, Apache 2.0: https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01 — release archive pinned to SHA-256 `b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f`; only int8 encoder/decoder/joiner and tokens are packaged.
- Kotlin standard library **1.9.25**, Apache 2.0: https://github.com/JetBrains/kotlin — pinned to SHA-256 `f9cdcdbff1f5de85380ae526977e683726c2aa42db1ed6e6e50ae89e496e95fd` for sherpa-onnx's Android classes.
- npm dependency versions and integrity hashes are pinned in `package-lock.json`.

The Android runtime is an embedded shared library. This application does not require Termux, Office, a desktop agent, root, or a local control server. Model inference may use the endpoint configured by the user.

The build places dependency license texts in APK assets under `licenses/`. The development runtime archive is downloaded from the release above and its SHA-256 is checked before packaging:

`e3cd29a1be03405f11dd5c857af8cd3ad13f84f1409ea648f5328f0bada5bd76`
