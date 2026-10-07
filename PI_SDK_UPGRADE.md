# Pi SDK 1.0.4 upgrade

Baseline: 5179a29 / @earendil-works Pi 0.85.1. Target: pi-coding-agent and pi-ai pinned to 1.0.4, including override and lockfile. Upstream packages unmodified.

## Host adaptations
- OAuth static bundle imports now resolve top-level pinned pi-ai instead of npm-shrinkwrap-era nested dependency paths.
- SettingsManager disables image auto-resize in this Android single-file bundle: 1.x prompt image normalization otherwise omitted images. Android screenshot/camera producers already resize; read previously used autoResizeImages:false. Large arbitrary image input bounds remain a separate concern.
- Camera request filtering also recognizes persisted host camera text suffix; SDK normalization drops private image metadata. Tests retain both original frames on disk and send only the latest live-call frame alongside ordinary attachments.
- Disable cache warming explicitly (off); no implicit extra paid requests.
- Runtime version diagnostics updated to 1.0.4. Built-in MCP/Codemode not enabled; trusted inline extensions and existing Connector maintained.

## Evidence
Private dependency tree isolated from original dev node_modules symlink before installation. npm install --ignore-scripts completed. esbuild bundle grew from ~13.3MB to ~15.2MB.

92 tests passed in the portable suite excluding connectors.test.mjs, sandbox-runtime.test.mjs and shell-runtime.test.mjs; an additional normalized-camera regression passed (camera unit file now 2 tests). Focused real SDK tests include model switching, images, camera history, native bridge, stop and session restart. MCP suite separately: 9 pass / 1 local stdio transport failure. Whole-suite attempts on Android time out: sandbox/shell desktop fixtures require a native runtime_environment responder on Android; no full-suite success claim. Test child processes must not inherit production ZFOLD_CREDENTIALS_DIR.

Full target28 and target35 APK compile/package/sign passed on device. Test runtime uses package Node26.4, not yet the APK embedded Node24.21. UI/embedded runtime and real provider auth remain installation validation, not established by these tests.

Workspace evidence: sdk-review/{install,bundle,focused,portable-tests,connectors,build28,build35}.log.
User install artifact: artifacts/Zfold-0.2.85-compat28-pi-1.0.4.apk. No automatic install, no uninstall/data migration against production history.

Skill saved to workspace/.pi/skills/pi-sdk-upgrade/SKILL.md and mirrored under docs/skills. reload_skills on currently running old app failed with stale extension context; needs a fresh session/reload after lifecycle issue is addressed. Do not call it loaded based only on file creation.

## Pending verification
- Linux CI complete suite and both APK targets.
- User installs signed APK; check embedded Node boot, existing session resume, provider login/refresh, screenshot/circle prompt and streaming reply, /goal and skill reload.
- Old-session format compatibility and rollback must be exercised using copies; do not assume 1.x-written history is backward compatible.
- Existing multi-session abort behavior and stale extension-context reload are known host concerns, not resolved by a dependency bump alone.
