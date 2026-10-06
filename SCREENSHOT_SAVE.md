# Optional workspace screenshot export

`screen_capture({save:true})` saves the same resized JPEG returned to the model under the current workspace's `screenshots/` directory. The response includes `saved:true` and an absolute `path`. The default remains read-only and does not create files. This does not save to the system gallery.

Disk writes run off the accessibility thread; filenames are unique. If saving fails, the screenshot is still returned with `saved:false` and `saveError`; partial files are removed. Secure screens remain subject to Android screenshot restrictions. Export only when requested: screenshots may contain private information. Saved files remain until the user deletes them.

Validation performed on phone: changed Java class compiled against Android SDK and existing application classes; Node syntax check; git diff whitespace check. No installation or live tool execution verified.

Manual acceptance checklist after installing a build:
- Omitted save / save:false creates no file.
- save:true returns an existing readable JPEG identical to the returned image.
- Repeated exports use different paths; active workspace selection is respected.
- Failed writes return saved:false without claiming success or leaving partial files.
- Secure-screen capture failure produces no export.
- Existing screenshot coordinate/frame expiry semantics remain unchanged.

Not included: full-resolution export, gallery export, default split-screen, Jev/voice fallback, or earlier local Dev-variant experiments.
