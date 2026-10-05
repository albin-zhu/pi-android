package dev.zfold.assistant;

import android.content.Context;

/** Backward-compatible facade over the single compact overlay slot. */
final class VoiceMiniOverlay {
  static boolean showing() {return UnifiedOverlay.showing(UnifiedOverlay.Mode.CALL);}
  static void showIfCallActive(Context context) {
    if(SystemWindowNavigation.active())return;
    MainActivity main = MainActivity.current();
    boolean active = main != null ? main.voice.sessionActive() : VoiceCallActivity.hasLiveController();
    if (active) UnifiedOverlay.call(context, "实时通话进行中");
  }
  static void status(String text) {UnifiedOverlay.status(text);}
  static void state(boolean session, boolean speaking) {
    if (!session) hide();
    else status(speaking ? "正在说话" : "正在听");
  }
  static void hide() {UnifiedOverlay.hide(UnifiedOverlay.Mode.CALL);}
}
