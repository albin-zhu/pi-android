package dev.zfold.assistant;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
final class UsageStore {
  static final String[] FAMILIES={"openai","xai","kimi"};
  static final String[] NAMES={"OpenAI","xAI","Kimi"};
  static final java.util.concurrent.ExecutorService writer=Executors.newSingleThreadExecutor();
  static SharedPreferences prefs(Context c){return c.getSharedPreferences("widget_usage",Context.MODE_PRIVATE);}
  static String month(){return new SimpleDateFormat("yyyy-MM",Locale.ROOT).format(new Date());}
  static String day(){return new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());}
  static long used(Context c,String family){return prefs(c).getLong(month()+"."+family+".tokens",0);}
  static long budget(Context c,String family){return prefs(c).getLong("budget."+family,0);}
  static void record(Context c,JSONObject event){
    final Context app=c.getApplicationContext();
    final String family=UsageMath.family(event.optString("provider"),event.optString("model")),id=event.optString("id");
    JSONObject usage=event.optJSONObject("usage");if(family==null||id.isEmpty()||usage==null)return;
    final long tokens=usage.optLong("totalTokens",0);if(tokens<0||tokens>1000000000L)return;
    writer.execute(()->{SharedPreferences p=prefs(app);String m=month(),d=day(),seen=m+".seen."+id;if(p.contains(seen))return;
      String key=m+"."+family;SharedPreferences.Editor edit=p.edit().putBoolean(seen,true)
        .putLong(key+".tokens",p.getLong(key+".tokens",0)+tokens)
        .putLong(d+"."+family+".tokens",p.getLong(d+"."+family+".tokens",0)+tokens)
        .putLong(key+".replies",p.getLong(key+".replies",0)+1)
        .putLong(key+".unknown",p.getLong(key+".unknown",0)+(tokens==0?1:0)).putLong("updated",System.currentTimeMillis());
      if(edit.commit())UsageWidget.updateAll(app);
    });
  }
  static String line(Context c,String family){long used=used(c,family),budget=budget(c,family);
    String result=budget>0?"本地预算剩余 "+UsageMath.remaining(budget,used):"本月已用 "+used;
    return result+" tokens"+(budget>0&&used>budget?" · 已超预算":"")+(prefs(c).getLong(month()+"."+family+".unknown",0)>0?" · 部分记录":"");
  }
}
