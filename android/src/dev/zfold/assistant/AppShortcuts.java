package dev.zfold.assistant;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import java.util.Collections;

final class AppShortcuts {
  static void publish(Context context){
    ShortcutManager manager=context.getSystemService(ShortcutManager.class);
    if(manager==null)return;
    ShortcutInfo circle=new ShortcutInfo.Builder(context,"circle_ask")
      .setShortLabel(context.getString(R.string.circle_ask_short))
      .setLongLabel(context.getString(R.string.circle_ask_long))
      .setIcon(Icon.createWithResource(context,R.drawable.ic_corners_out))
      .setIntent(CircleEntryActivity.intent(context).setAction("dev.zfold.assistant.CIRCLE_ASK"))
      .build();
    try{manager.setDynamicShortcuts(Collections.singletonList(circle));}catch(RuntimeException ignored){}
  }
}
