# Session library UI

An Apple-inspired native Android hierarchy: fixed search/new-chat controls, quieter backdrop, larger drawer, rounded selection surfaces, pinned sessions followed by Today/Yesterday/Earlier groups. Existing drawer animations respect system animator settings.

- Search is local and matches title plus first-user-message preview, not full transcript or latest message. No network search.
- Pin/unpin persists in the existing atomic catalog sidecar; Pi JSONL remains authoritative for conversations and names.
- Selection supports batch archive and soft delete after confirmation. Operations execute serially through the existing session_manage protocol, respecting busy/voice guards. Error, timeout or drawer dismissal stops unsent operations; already acknowledged operations are not rolled back. No automatic retry of unknown outcomes. Permanent deletion remains single-item only.
- Existing rename, archive, trash/restore, and permanent-delete controls remain available. Current conversation and active-task marker are visible.
- Searching clears hidden selection. Selecting a row preserves list scroll position. Search/filter still work when tasks are active; mutation/switching remains blocked.

Validation: 8 catalog/protocol/session-management tests pass on phone, including restart, current-session archive/delete, invalid/concurrent/busy requests, pin persistence. Found and fixed Android EAGAIN/partial-write protocol output during regression. Full local APK build/signature checked. UI, keyboard, multi-select dialogs and fold transitions still require installed-device validation.

Deferred: full-text matching/highlight, last-message previews, goal progress badges (goal SDK extension remains separate draft), batch restore, undo snackbar. Goal-panel draft is not included in this build.
