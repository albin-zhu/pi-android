# Message queue, first UI iteration

Main chat, while busy: tap the task button to choose enqueue, steer, manage queue, or stop and clear. Long-press the send/task button opens queue management even when idle. This changes the former single-tap stop behavior to an explicit task menu.

The existing PromptDispatcher FIFO holds at most eight pending requests. Editing changes text only and preserves original attachments; cancellation targets stable request IDs. A stale editor cannot change a request already dispatched. Pending requests are memory-only, cleared by session change or stop, and are not restored after process death. Existing policy continues separately accepted queued requests after a failed turn.

Steering currently accepts text only and uses Pi AgentSession.steer, delivered after the current assistant tool calls and before the next model call. Acceptance is not proof of consumption. Submitted steering is not editable or individually retractable. Wrong-session/idle/stopping requests return steer_result rather than the generic error event that would incorrectly mark a running task idle.

Validated: full on-phone bundle, Java compilation, D8, APK generation and signature verification. Interactive acceptance tests still required: queue two messages and verify FIFO; edit/cancel the second; stale editor after dispatch; clear queue on stop/session switch; steer during a tool call; reject attachments in steering; reject steering after turn completion without clearing draft; long-press management after activity recreation. No claim of full Codex feature parity.
