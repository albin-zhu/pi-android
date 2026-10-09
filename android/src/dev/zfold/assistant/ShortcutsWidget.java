package dev.zfold.assistant;
import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
public final class ShortcutsWidget extends AppWidgetProvider {
  static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);new ShortcutsWidget().onUpdate(c,m,m.getAppWidgetIds(new ComponentName(c,ShortcutsWidget.class)));}
  @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){
    for(int id:ids){RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.widget_shortcuts);
      views.setTextViewText(R.id.widget_agent_name,AgentIdentity.name(c));
      int[] buttons={R.id.widget_chat,R.id.widget_circle,R.id.widget_voice,R.id.widget_sessions};
      for(int i=0;i<buttons.length;i++){
        Intent intent=i==1?CircleEntryActivity.intent(c):new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setAction("widget-entry-"+id+"-"+i);if(i==2)intent.putExtra("open_voice",true);if(i==3)intent.putExtra("open_sessions",true);
        views.setOnClickPendingIntent(buttons[i],PendingIntent.getActivity(c,id*10+i,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
      }
      manager.updateAppWidget(id,views);
    }
  }
  @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,android.os.Bundle options){onUpdate(c,m,new int[]{id});}
}
