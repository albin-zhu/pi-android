# Chat workbench UI iteration

- Idle draft: send. Busy draft: enqueue directly. Busy without draft: stop directly. Busy draft also exposes separate steering and stop actions.
- Pending prompts are visible above the composer in a capped scroll area. Edit/cancel uses stable request IDs; attachment content is preserved. Dispatcher observers refresh the list on enqueue, edit, cancel, dequeue, stop and session change, and are unregistered on activity destruction.
- Tool groups use compact light surfaces and file-oriented headings. File counts describe attempted changes, not verified success.
- Expanded edit records show original/replacement snippets with relative snippet line numbers, not absolute source lines or a computed unified diff. Write and bash have separate previews. Large previews are bounded; original parameters/results remain selectable under a separate disclosure. Existing status labels are retained; returned is not silently relabeled verified.
- File paths open through the scoped artifact provider. Relative paths currently resolve against the active workspace at click time.
- 160–180 ms alpha/translation and arrow rotation respect ValueAnimator.areAnimatorsEnabled(). No per-token animation or continuous pulse. No height/reorder animation yet: protecting scroll position takes priority.
- Normal turn completion hides stale busy status. Queue persistence and steering recall are not added in this iteration.

Validation: full on-device build (bundle, javac, D8, native compilation, packaging, signature verification). Interactive tests still pending installation: outer/inner screen and keyboard layout; enqueue/edit/cancel/dequeue while visible; attachments; stop clears queue; steering confirmation; long text diff; failed edits remain errors; raw logs copy; link opening; animation scale 0; no reader jump when disclosing tools.

Deferred: true unified diffs/absolute line numbers, thumbnail/size artifact cards, exit-code structured bash badge, queue reorder animation, persistent pending queue. This is a first integrated UI pass, not completion of every proposed visual detail.
