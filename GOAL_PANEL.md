# SDK goal panel — first usable iteration

## Architecture
`runtime/goal-extension.mjs` is an inline Pi SDK extension using registerTool, appendEntry and before_agent_start. No Pi package sources are patched. `goal-state.mjs` is a pure reducer. Android receives snapshots through a small goal_manage / goal_state / goal_error adapter. The extension tool is explicitly activated in createAgentSession's tool allowlist.

State lives in `zfold.goal.v1` custom entries in the Pi session branch. Latest entry on the current branch wins; switching sessions does not share goals. Revision checks reject stale mutations. The active goal is supplied as context each new agent run, explicitly not extra authorization or a continuation loop.

## UX
Chat header opens the goal panel. User can create a title, acceptance criteria and one-step-per-line plan (1–20 steps). Steps support pending, in_progress, blocked, verification and done, plus textual evidence. Model can create/update with goal_plan, but only the native user action may accept or cancel. All steps must be done, with evidence, before acceptance. Evidence is reported text, not automatically verified facts. UI edits are blocked during active runs; normal stop/queue controls still govern execution. Creating a goal does not start a task.

`done` means reported step completion, not user acceptance. The header shows pending acceptance until the user explicitly accepts. Cancellation changes the record, not files or past external actions. Closed goals allow creating a new one; old entries remain in history.

## Validation
Six state/real-SDK protocol tests passed on phone: evidence required, stale revisions, model cannot accept/cancel, branch reads, goal tool active, persistence across process restart, independent new session, wrong-session rejection, and existing session-management behavior. Full APK build and signature verification passed. Installed-device goal UI and real-model tool usage still require acceptance testing.

Not included: autonomous goal loop, budget enforcement, read-only plan permission mode, automatic task resumption, evidence-file viewer, goal badges in session sidebar, editing the plan structure after creation. No claim of complete harness parity.
