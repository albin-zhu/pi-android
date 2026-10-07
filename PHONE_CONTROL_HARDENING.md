# Phone control hardening, iteration 1

## Diagnosed defects
- captureTool observed applicationRoot while visualGesture checked getRootInActiveWindow. Different selection policies could immediately reject a fresh screenshot on Samsung overlays. Unified to one active-window policy.
- Focused underlying app selection could override an active system menu. Active non-IME/non-accessibility-overlay windows now win. Explicit window discovery and scoped reads handle ambiguity.
- Every frame validation failure used the same “expired screenshot” message, obscuring foreground, window, geometry and consumed-frame mismatches. Distinct reasons retain existing 30s and single-action safeguards.
- ui_select readback previously set verified:true merely because a screen could be read. Now reports observationAvailable and leaves verified:false; business verification is still the caller's responsibility.
- Label matcher now rejects disabled/password candidates and cross-package ancestors before dispatch.
- split state previously inferred split from window centers alone; now requires substantial non-overlapping pane geometry and distinct non-SystemUI package pairs. This remains geometric evidence, not privileged access to Samsung stage state.

## New capabilities
- screen_windows: read window IDs, types, packages, bounds, active/focused state and layer.
- screen_read: optional packageName/windowId; ambiguous package windows fail instead of guessing.
- ui_system: enumerate supported global actions; request home/back/recents/notifications/quick_settings, then observe screenshot. No hidden shell permissions.
- ui_tap longPress: native advertised long-click or 650ms screenshot gesture. No drag or multi-touch claim.

## Validation
17 selector/task/guard tests and 6 runtime bridge tests passed on phone. Full APK compilation/packaging/signature verification passed. New native window selection, recents and long-press still require installing and testing this version. No measured end-to-end speed or success-rate claim yet.

Next acceptance matrix: Samsung sidebar/system menus, app picker, real left-right split, top-bottom split, floating-window conversion, WeChat search/input (no sending without authorization), keyboard visible/hidden, rotation, stop/takeover, stale frame, duplicate labels. Record observation-to-action latency and reason-coded failures. Do not “fix” stale frames by simply removing checks.

Deferred: long-press-then-drag, multi-pointer gestures, stable screen settle detection, second-app-specific split validation, Samsung app pair creation. No claim of perfect phone automation.
