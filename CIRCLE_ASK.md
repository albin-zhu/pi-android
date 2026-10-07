# Circle-to-ask, overlay first

Circle selection is a shortcut, not a chat-toolbar detour.

- Launcher long-press **圈选提问**, Quick Settings tile, or the floating bubble long-press item start a transparent `CircleEntryActivity`. It finishes immediately and overlays lasso on the current screen.
- After a valid lasso, a compact **问橙橙** card appears on the current app (accessibility overlay). Sending a prompt does not bring the full chat to the front.
- System **PROCESS_TEXT** (“问橙橙” in the text selection toolbar) and **ACTION_SEND** for images open the same tiny card with the selected text or picture.
- The in-app 圈选 button now uses the same shortcut host so the chat recedes instead of covering the capture.

Not claimed: replacing Samsung Circle to Search gesture, drawing on another app without accessibility, or a pixel-accurate chip glued to the selection handles (Android does not give those coordinates to PROCESS_TEXT).
