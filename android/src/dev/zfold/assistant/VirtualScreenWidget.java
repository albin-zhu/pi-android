package dev.zfold.assistant;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

public final class VirtualScreenWidget extends AppWidgetProvider {
  @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    for (int id : ids) update(context, manager, id);
  }

  @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
      int id, Bundle options) {
    update(context, manager, id);
  }

  private static void update(Context context, AppWidgetManager manager, int id) {
    Intent intent = new Intent(context, LauncherActivity.class)
        .setAction(LauncherActivity.ACTION_VIRTUAL_SCREEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent open = PendingIntent.getActivity(context, id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_virtual_screen);
    views.setOnClickPendingIntent(R.id.widget_virtual_screen_open, open);
    manager.updateAppWidget(id, views);
  }
}
