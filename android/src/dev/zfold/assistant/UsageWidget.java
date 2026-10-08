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
      int[] labels={R.id.widget_openai_label,R.id.widget_xai_label,R.id.widget_kimi_label};
      int[] bars={R.id.widget_openai_bar,R.id.widget_xai_bar,R.id.widget_kimi_bar};
      StringBuilder description=new StringBuilder("本机模型用量，不是账户余额。");boolean partial=false;
      for(int i=0;i<rows.length;i++){
        String family=UsageStore.FAMILIES[i];long used=UsageStore.used(c,family),budget=UsageStore.budget(c,family);
        long replies=UsageStore.prefs(c).getLong(UsageStore.month()+"."+family+".replies",0),unknown=UsageStore.prefs(c).getLong(UsageStore.month()+"."+family+".unknown",0);
        long number=budget>0?UsageMath.remaining(budget,used):used;partial|=unknown>0;
        boolean empty=budget==0&&replies==0;
        views.setTextViewText(rows[i],empty?"—":UsageMath.compact(number));
        String label=empty?"待记录":budget>0?(used>budget?"已超预算":"预算剩余"):"本月已用";
        if(!empty&&number>=1000)label+=" · 约";if(unknown>0)label+="*";
        views.setTextViewText(labels[i],label);
        views.setViewVisibility(bars[i],budget>0?android.view.View.VISIBLE:android.view.View.INVISIBLE);
        if(budget>0)views.setProgressBar(bars[i],100,(int)Math.round(100.0*number/budget),false);
        views.setContentDescription(rows[i],UsageStore.NAMES[i]+"："+(empty?"暂无回执":UsageStore.line(c,family)));
        description.append(UsageStore.NAMES[i]).append("：").append(empty?"暂无回执":UsageStore.line(c,family)).append("。");
      }
      views.setTextViewText(R.id.widget_period,UsageStore.month().substring(5)+"月 · tokens");
      views.setTextViewText(R.id.widget_note,"本地统计 · 非账户余额"+(partial?" · *部分回执":""));
      views.setContentDescription(R.id.widget_root,description.toString()+"点击查看精确用量及预算设置。");
      Intent intent=new Intent(c,UsageActivity.class).setAction("usage-widget-"+id);
      views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));manager.updateAppWidget(id,views);
    }
  }
  @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){update(c,m,ids);}
  @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,android.os.Bundle options){update(c,m,new int[]{id});}
}
