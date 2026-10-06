# Clickable artifact links

Message body supports `[label](file:///storage/emulated/0/pi/workspace/path.jpg)` alongside HTTP(S) links. Spaces should be percent encoded. Clicking routes through a non-exported read-only content provider with a temporary per-file read grant, not a raw file URI or an external DocumentsProvider permission assumption.

Canonical paths must resolve to existing files beneath the application's external pi root. Private paths, traversal outside the root, missing files, nonlocal file authorities, query strings and fragments are rejected. No handler produces a visible error; no automatic installation is attempted. Links in table cells are not yet supported. A future dev applicationId variant must also rename the manifest provider authority.

Validated: Java compilation, full on-device APK build and signature verification. Manual checks still required after installation: click screenshot link and verify external viewer opens; HTTPS links still work; long-press selection remains usable; missing/private/traversal paths fail visibly; external apps cannot write or access ungranted files; APK link opens system confirmation only.
