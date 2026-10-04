# Embedded terminal components

Source: https://github.com/termux/termux-app at v0.118.3,
commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.
Archive SHA-256: `a79a8ae388caab56d4d6df8f80aa213c21090e3a1f605c28d7f93fb466d6e7ca`.

Only terminal-emulator and terminal-view Java/resources are included. The upstream
license explicitly identifies these libraries as the Apache 2.0 exception; the
app and termux-shared modules are not included. The upstream license statement is
preserved here and Apache 2.0 is in ../Termux-Terminal-LICENSE.txt.

Local integration changes: resource imports use the app R class; compile-only
AndroidX Nullable/RequiresApi annotations are removed (minimum API is 30).
The upstream native TerminalSession/JNI are replaced by an app-side session adapter
under android/src/com/termux/terminal, so the existing app-owned PTY broker remains
the process owner. VT parsing, input, rendering, selection and Unicode handling
come from the upstream libraries.

The view minimum is two terminal rows (supported by TerminalEmulator), so a
landscape phone keyboard does not place the shell cursor below the visible view.
