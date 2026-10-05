package dev.zfold.assistant;
import android.app.Activity;
import android.os.Bundle;
/** Fallback ACTION_ASSIST handler: resume the system-owned chat window. */
public final class OverlayEntryActivity extends Activity {
  @Override public void onCreate(Bundle state){super.onCreate(state);SystemWindowNavigation.showChat(this,false);finish();overridePendingTransition(0,0);}
}
