package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import java.util.*;
import java.util.function.Consumer;

/** The OS owns task movement, sizing and minimization; the app stores only a restore hint. */
final class SystemWindowNavigation {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Map<Activity,ArrayList<Consumer<Boolean>>> entering=new IdentityHashMap<>();
  private static SharedPreferences preferences(Context context){return context.getSharedPreferences(PiRuntime.testMode?"test-system-window":"system-window",Context.MODE_PRIVATE);}
  static boolean active(){MainActivity main=MainActivity.current();return main!=null&&!main.isFinishing()&&main.isInMultiWindowMode();}
  static void remember(Activity activity){
    if(!activity.isInMultiWindowMode())return;
    android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
    if(insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime()))return;
    WindowManager windows=activity.getWindowManager();Rect bounds=windows.getCurrentWindowMetrics().getBounds(),screen=windows.getMaximumWindowMetrics().getBounds();
    // Split panes span one display dimension. Do not replace the user's floating geometry with a split or maximized task.
    if(bounds.width()>=screen.width()||bounds.height()>=screen.height()||bounds.isEmpty())return;
    float density=activity.getResources().getDisplayMetrics().density;
    preferences(activity).edit().putFloat("widthDp",bounds.width()/density).putFloat("heightDp",bounds.height()/density)
      .putFloat("x",Math.max(0,Math.min(1,(bounds.left-screen.left)/(float)(screen.width()-bounds.width()))))
      .putFloat("y",Math.max(0,Math.min(1,(bounds.top-screen.top)/(float)(screen.height()-bounds.height())))).apply();
  }
  static Rect restoredBounds(Context context){
    SharedPreferences saved=preferences(context);if(!saved.contains("widthDp"))return null;
    Rect screen=((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();float density=context.getResources().getDisplayMetrics().density;
    int width=Math.min(screen.width(),Math.max(1,Math.round(saved.getFloat("widthDp",280)*density)));
    int height=Math.min(screen.height(),Math.max(1,Math.round(saved.getFloat("heightDp",360)*density)));
    int left=screen.left+Math.round((screen.width()-width)*saved.getFloat("x",.5f)),top=screen.top+Math.round((screen.height()-height)*saved.getFloat("y",.5f));
    return new Rect(left,top,left+width,top+height);
  }
  static void showChat(Context context,boolean voice){
    Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(voice)intent.putExtra("open_voice",true);
    ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
    for(ActivityManager.AppTask task:manager.getAppTasks()){
      ActivityManager.RecentTaskInfo info=task.getTaskInfo();
      if(info.baseIntent.getComponent()!=null&&info.baseIntent.getComponent().getClassName().equals(MainActivity.class.getName())){
        try{task.moveToFront();MainActivity main=MainActivity.current();if(voice&&main==null)context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else if(voice)main.root.post(()->{if(!main.isFinishing()&&!main.isDestroyed())main.toggleVoice();});return;}catch(IllegalArgumentException ignored){} // Task may have just been removed.
      }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Rect bounds=restoredBounds(context);
    ActivityOptions options=ActivityOptions.makeBasic();if(bounds!=null)options.setLaunchBounds(bounds);
    context.startActivity(intent,options.toBundle());
  }
  static void showWake(Context context,long wakeId){
    if(!VoiceWakeService.pending(wakeId)||VoiceCoordinator.get(context).ownsWake(wakeId))return;
    Intent intent=new Intent(context,MainActivity.class).putExtra("wake_event_id",wakeId)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    context.startActivity(intent);
  }
  static void open(Context context,Class<?> destination,boolean overlay){
    Intent intent=new Intent(context,destination).putExtra("from_overlay",overlay).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(!(context instanceof Activity)||overlay)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ActivityOptions options=ActivityOptions.makeBasic();
    if(context instanceof Activity&&((Activity)context).isInMultiWindowMode())options.setLaunchBounds(((Activity)context).getWindowManager().getCurrentWindowMetrics().getBounds());
    context.startActivity(intent,options.toBundle());
  }
  static Rect smallWindowBounds(Context context){
    Rect saved=restoredBounds(context);if(saved!=null)return saved;
    Rect screen=((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
    float density=context.getResources().getDisplayMetrics().density;
    int width=Math.min(Math.round(280*density),Math.round(screen.width()*.85f));
    int height=Math.min(Math.round(360*density),Math.round(screen.height()*.75f));
    int left=screen.left+(screen.width()-width)/2,top=screen.top+Math.round((screen.height()-height)*.3f);
    return new Rect(left,top,left+width,top+height);
  }
  /** Request a system-owned window for this existing task, then observe the result. */
  static void enterSmallWindow(Activity activity,Consumer<Boolean> complete){
    if(activity.isFinishing()||activity.isDestroyed()){complete.accept(false);return;}
    ArrayList<Consumer<Boolean>> waiting=entering.get(activity);
    if(waiting!=null){waiting.add(complete);return;}
    if(activity.isInMultiWindowMode()){complete.accept(true);return;}
    waiting=new ArrayList<>();waiting.add(complete);entering.put(activity,waiting);
    ActivityOptions options=ActivityOptions.makeBasic().setLaunchBounds(smallWindowBounds(activity));
    Bundle bundle=options.toBundle();
    // AOSP ActivityOptions / Android 16: a bounds hint alone does not change a
    // fullscreen task's mode. This optional framework hint can be ignored by an
    // OEM; never equate successful Intent dispatch with a successful transition.
    bundle.putInt("android.activity.windowingMode",5);
    Intent intent=new Intent(activity,activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    try{
      ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
      // Launch from the foreground Activity without NEW_TASK. AppTask.startActivity
      // rejects a singleTask root; finishing or replacing that root would lose drafts.
      activity.startActivity(intent,bundle);
    }catch(RuntimeException error){android.util.Log.w("ZfoldWindow","Window request failed: "+error.getClass().getSimpleName());finishEntry(activity,false);return;}
    long deadline=SystemClock.uptimeMillis()+2500;
    main.post(new Runnable(){public void run(){
      if(activity.isFinishing()||activity.isDestroyed()){finishEntry(activity,false);return;}
      if(activity.isInMultiWindowMode()){
        remember(activity);finishEntry(activity,true);return;
      }
      if(SystemClock.uptimeMillis()>=deadline){finishEntry(activity,false);return;}
      main.postDelayed(this,80);
    }});
  }
  private static void finishEntry(Activity activity,boolean success){
    ArrayList<Consumer<Boolean>> waiting=entering.remove(activity);
    if(waiting!=null)for(Consumer<Boolean> complete:waiting)complete.accept(success);
  }
  static void openSmallWindow(Activity activity){
    enterSmallWindow(activity,success->{
      if(activity.isFinishing()||activity.isDestroyed())return;
      if(success)android.widget.Toast.makeText(activity,"已进入系统小窗",android.widget.Toast.LENGTH_SHORT).show();
      else explain(activity);
    });
  }
  static void explain(Activity activity){
    new AlertDialog.Builder(activity).setTitle("系统未允许切换小窗")
      .setMessage("当前聊天和草稿已保留。可以在系统最近任务中，点 Zfold 的应用图标，选择「在弹出视图中打开」或「分屏」，再开启看屏幕。")
      .setPositiveButton("知道了",null).show();
  }
}
