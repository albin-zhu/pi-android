package dev.zfold.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Transparent shortcut/QS host: leave the current app visible, then overlay lasso. */
public final class CircleEntryActivity extends Activity {
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    boolean fromChat=MainActivity.current()!=null&&!MainActivity.current().isFinishing();
    if(fromChat)moveTaskToBack(true);
    finish();
    overridePendingTransition(0,0);
    long delay=fromChat?280:40;
    new Handler(Looper.getMainLooper()).postDelayed(()->CircleOverlay.beginOnCurrentScreen(getApplicationContext()),delay);
  }

  static Intent intent(android.content.Context context){
    return new Intent(context,CircleEntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION);
  }
}
