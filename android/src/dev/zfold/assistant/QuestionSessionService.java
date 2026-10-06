package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.IBinder;

/** Visible lifetime for a task waiting for a user answer, through the resumed turn. */
public final class QuestionSessionService extends Service {
  private static String turn;private static QuestionSessionService active;
  static void begin(Context context,String id){turn=id;try{context.startForegroundService(new Intent(context,QuestionSessionService.class));}catch(RuntimeException error){android.util.Log.w("ZfoldQuestion","Question lifetime unavailable");}}
  static void refresh(Context context){if(active!=null)active.update();}
  static void end(Context context,String id){if(turn!=null&&(id==null||id.isEmpty()||turn.equals(id))){turn=null;context.stopService(new Intent(context,QuestionSessionService.class));}}
  @Override public void onCreate(){super.onCreate();active=this;update();}
  private void update(){
    getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("user_questions","需要回答的任务",NotificationManager.IMPORTANCE_DEFAULT));
    UserQuestions.Request question=UserQuestions.current();Intent intent=question==null?new Intent(this,MainActivity.class):new Intent(this,UserQuestionActivity.class).putExtra("question_request_id",question.id);
    PendingIntent open=PendingIntent.getActivity(this,81,intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Intent cancel=new Intent(this,QuestionSessionService.class).setAction("stop").setData(Uri.parse("chengcheng-question://stop/"+turn)).putExtra("turn",turn);
    PendingIntent stop=PendingIntent.getService(this,82,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    startForeground(44,new Notification.Builder(this,"user_questions").setSmallIcon(R.drawable.ic_chat_circle_text).setContentTitle(question==null?"助手正在继续任务":"助手有问题需要你回答")
      .setContentText(question==null?"点此查看任务进度":"点此填写回答，任务正在等待").setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止任务",stop).build()).build());
    if(turn==null)stopSelf();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&"stop".equals(intent.getAction())){PiRuntime runtime=PiRuntime.get(this);if(runtime.busy&&runtime.activeTurnId().equals(intent.getStringExtra("turn")))runtime.send(PiRuntime.json("type","stop"));}update();return START_NOT_STICKY;}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onDestroy(){active=null;super.onDestroy();}
}
