package dev.zfold.assistant;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** App-icon routing only; explicit chat, voice and settings entries bypass this activity. */
public final class LauncherActivity extends Activity {
  static final String ACTION_UBUNTU_TERMINAL="dev.zfold.assistant.OPEN_UBUNTU_TERMINAL";
  static final String ACTION_ANDROID_TERMINAL="dev.zfold.assistant.OPEN_ANDROID_TERMINAL";
  static final String ACTION_VIRTUAL_SCREEN="dev.zfold.assistant.OPEN_VIRTUAL_SCREEN";
  @Override public void onCreate(Bundle state){super.onCreate(state);dispatch();}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);dispatch();}
  private void dispatch(){
    try{
      String action=getIntent().getAction();
      if(ACTION_VIRTUAL_SCREEN.equals(action)){VirtualScreenActivity.open(this,null);return;}
      if(ACTION_UBUNTU_TERMINAL.equals(action)||ACTION_ANDROID_TERMINAL.equals(action)){
        // Build a fresh intent: exported entry extras are never trusted or forwarded.
        startActivity(new Intent(this,TerminalActivity.class).setAction(action)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        return;
      }
      Class<?> destination=StartupPreferences.destination(this);
      ComponentName component=new ComponentName(this,destination);
      ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
      for(ActivityManager.AppTask task:manager.getAppTasks()){
        try{
          ActivityManager.RecentTaskInfo info=task.getTaskInfo();
          if(info.taskId==getTaskId())continue;
          // Reuse an already visible destination without disturbing its UI or terminal view state.
          if(component.equals(info.topActivity)){
            task.moveToFront();return;
          }
        }catch(IllegalArgumentException ignored){} // A task may disappear while enumerating recents.
      }
      // The dispatcher has no affinity: the destination, never this entry, owns the retained task.
      startActivity(new Intent(this,destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }finally{finish();overridePendingTransition(0,0);}
  }
}
