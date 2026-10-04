package dev.zfold.assistant;
import android.app.Activity;
import android.os.Bundle;
/** Fallback ACTION_ASSIST handler: summon a panel without navigating to the chat activity. */
public final class OverlayEntryActivity extends Activity {
  @Override public void onCreate(Bundle state){super.onCreate(state);AssistantOverlay.show(this);finish();overridePendingTransition(0,0);}
}
