package dev.zfold.assistant;
import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import java.text.DateFormat;
import java.util.Date;
public final class UsageWidget extends AppWidgetProvider {
  static void updateAll(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);update(c,m,m.getAppWidgetIds(new ComponentName(c,UsageWidget.class)));}
  static void update(Context c,AppWidgetManager manager,int[] ids){
    for(int id:ids){RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.widget_usage);
      int[] rows={R.id.widget_openai,R.id.widget_xai,R.id.widget_kimi};
      for(int i=0;i<rows.length;i++)views.setTextViewText(rows[i],UsageStore.NAMES[i]+" · "+UsageStore.line(c,UsageStore.FAMILIES[i]));
      long updated=UsageStore.prefs(c).getLong("updated",0);
      String note="本机模型家族统计 · 非账户余额\n"+(updated==0?"安装后开始记录 · 点击设置预算":"最近记录 "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(updated))+" · 点击查看");
      views.setTextViewText(R.id.widget_note,note);
      Intent intent=new Intent(c,UsageActivity.class).setAction("usage-widget-"+id);
      views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));manager.updateAppWidget(id,views);
    }
  }
  @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){update(c,m,ids);}
  @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,android.os.Bundle options){update(c,m,new int[]{id});}
}
