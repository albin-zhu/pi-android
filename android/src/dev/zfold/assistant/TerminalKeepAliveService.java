package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Visible lifetime owner for user-created terminal sessions. */
public final class TerminalKeepAliveService extends Service {
  private static TerminalKeepAliveService active;
  static void refresh(Context context){if(active!=null)active.update();}
  @Override public void onCreate(){super.onCreate();active=this;update();}
  private void update(){
    int count=TerminalSessions.get(this).liveCount();
    NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    notifications.createNotificationChannel(new NotificationChannel("terminal_sessions","运行中的终端",NotificationManager.IMPORTANCE_LOW));
    PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    startForeground(42,new Notification.Builder(this,"terminal_sessions").setSmallIcon(R.drawable.ic_gear_six)
      .setContentTitle(count+" 个终端正在运行").setContentText("点此返回终端；离开页面会保留进程")
      .setContentIntent(open).setOngoing(true).build());
    if(count==0)stopSelf();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){update();return START_NOT_STICKY;}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onDestroy(){active=null;super.onDestroy();}
}
