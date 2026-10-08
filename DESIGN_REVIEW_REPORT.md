# Product design review delivery

Branch: feat/apple-ui-polish. User acceptance reserved for one consolidated device review, not per-install prompts.

## Implemented / preserved

| Area | Evidence and implementation |
|---|---|
| Architecture | PRODUCT_DESIGN.md: result/action/evidence layers, state matrix and flow boundaries |
| Theme | Ui.Theme neutral palette, semantic accent, native values/values-night styles aligned; Markdown no longer hardcodes black text |
| Reading | 640dp column, copy latest displayed raw Markdown rather than initial closure text; existing follow-bottom behavior retained |
| Composer | Session control visible at usable widths; screen-sharing advanced control goes into existing overflow at narrow widths; existing attachment/queue/steer/stop semantics retained |
| Session library | Current accent, unselected transparent rows, 48dp search; existing search/group/multiselect retained |
| Tool evidence | Compact completed group; failures/verification remain explicit, 12-line bounded code preview plus complete raw logs; accessible disclosure target |
| Circle ask | In-place stream, scoped turn IDs, listeners cleaned up; content-sized capped reply, screen-width cap, outer keyboard-inset-bounded scroll; native checked new-session control; send switches to stop and restores after terminal event |
| Settings | Native theme palette aligned, model rows >=48dp, agent settings form scrolls within current window and resizes for IME |
| Motion | Choreographer analytical critical spring with live-value/velocity retargeting; immediate press alpha; drawer tracks touch, release velocity projection and cancellation on detach; animator-off fallback |
| Accessibility | Semantic CheckBox, disclosure labels, touch target fixes; palette contrast checks across normal text, secondary text and action combinations |

## Automated evidence

On phone: 97 portable Node tests pass (desktop-only native-responder/stdio fixtures separately run in Linux CI). SpringMathCheck pure Java validates time-step consistency, no unintended critical overshoot, retarget value/velocity continuity and projection sign. design-contract tests verify both theme palettes >=4.5:1 for covered normal text pairs, themed Markdown and XML accent consistency.

Full target28 compile/package/sign pass; artifact artifacts/Zfold-0.2.85-compat28-design-review.apk. CI adds Java spring and slash checks alongside full Node suite and target28/35 APK builds.

## Device-review matrix (not yet passed)

- Light/dark: Markdown, code, table, selected row, secondary/disabled text and buttons.
- Fold outer/inner, narrow floating window, split pane, portrait/landscape.
- Large fonts + keyboard: input, attachments, model/identity forms and circle reply scrolling.
- Drawer quick reversal, mid-animation grab, velocity release; animation scale off.
- Short/long reply, new-session checkbox, stop, failure, closed overlay and late events.
- User scrolled away from live output: no involuntary jump; full raw tool log available after truncated preview.

## Known limits, not hidden

No backdrop blur or fake glass claimed. Reduced transparency effectively uses opaque readable surfaces. System-owned floating-window dragging remains Samsung behavior. There is no implementation or claim of true multi-session parallel work. New-session checkbox can interrupt the old turn; spoken state description explicitly says so. UI appearance in the updated code is not proven by old installed screenshots. Every contract/source/math test covers only its own assertions, not TalkBack usability or physical frame smoothness.

The full design task cannot be called accepted before the consolidated device matrix. Further prototype iterations may be needed based on that review. No automatic installation or main merge.
