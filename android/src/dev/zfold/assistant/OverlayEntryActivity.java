package dev.zfold.assistant;
import android.app.Activity;
import android.os.Bundle;
/** Fallback ACTION_ASSIST handler: resume the system-owned chat window. */
public final class OverlayEntryActivity extends Activity {
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    if(CircleAskPrefs.assistInvokesCircle(this))CircleOverlay.beginOnCurrentScreen(getApplicationContext());
    else TerminalActivity.open(this);
    finish();overridePendingTransition(0,0);
  }
}
