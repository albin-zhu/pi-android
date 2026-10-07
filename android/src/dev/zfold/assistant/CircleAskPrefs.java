package dev.zfold.assistant;

import android.content.Context;
import android.content.SharedPreferences;

final class CircleAskPrefs {
  private static SharedPreferences prefs(Context context){
    return context.getSharedPreferences(PiRuntime.testMode?"test-circle-ask":"circle-ask",Context.MODE_PRIVATE);
  }
  static boolean assistInvokesCircle(Context context){return prefs(context).getBoolean("assist_circle",true);}
  static void setAssistInvokesCircle(Context context,boolean value){prefs(context).edit().putBoolean("assist_circle",value).apply();}
  static boolean selectionHint(Context context){return prefs(context).getBoolean("selection_hint",true);}
  static void setSelectionHint(Context context,boolean value){prefs(context).edit().putBoolean("selection_hint",value).apply();}
}
