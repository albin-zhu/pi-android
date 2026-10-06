package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.util.*;
import org.json.JSONObject;

/** Keeps the local OAuth listener alive while the user authorizes in another app. */
public final class AuthSessionService extends Service {
  private static final Set<String> requests=new HashSet<>();private static AuthSessionService active;
  static boolean running(){return active!=null;}
  static void update(Context context,JSONObject event){
    if(!event.has("active"))return;String id=event.optString("requestId");if(id.isEmpty())return;
    boolean keep=event.optBoolean("active")&&!event.optString("state").equals("cancelling");
    boolean changed=keep?requests.add(id):requests.remove(id);if(!changed&&(active!=null||!keep))return;
    if(requests.isEmpty()){context.stopService(new Intent(context,AuthSessionService.class));return;}
    if(active!=null){active.refresh();return;}
    try{context.startForegroundService(new Intent(context,AuthSessionService.class));}
    catch(RuntimeException error){android.util.Log.w("ZfoldAuth","Login background lifetime unavailable");}
  }
  @Override public void onCreate(){super.onCreate();active=this;refresh();}
  private void refresh(){
    NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("oauth_sessions","账号授权",NotificationManager.IMPORTANCE_LOW));
    PendingIntent open=PendingIntent.getActivity(this,71,new Intent(this,MainActivity.class).putExtra("open_provider_auth",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent cancel=PendingIntent.getService(this,72,new Intent(this,AuthSessionService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    startForeground(43,new Notification.Builder(this,"oauth_sessions").setSmallIcon(R.drawable.ic_gear_six).setContentTitle("正在等待账号授权")
      .setContentText("返回应用继续或取消登录").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"取消登录",cancel).build()).build());
    if(requests.isEmpty())stopSelf();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&"cancel".equals(intent.getAction())){cancelPending();stopSelf();}else refresh();return START_NOT_STICKY;}
  private void cancelPending(){if(requests.isEmpty())return;PiRuntime runtime=PiRuntime.get(this);for(String id:new ArrayList<>(requests))if(runtime.ready)runtime.send(PiRuntime.json("type","auth_manage","action","cancel","requestId",id));requests.clear();}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onDestroy(){active=null;super.onDestroy();}
}
