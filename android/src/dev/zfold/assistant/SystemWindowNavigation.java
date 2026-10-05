package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.view.WindowManager;

/** The OS owns task movement, sizing and minimization; the app stores only a restore hint. */
final class SystemWindowNavigation {
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
  static void open(Context context,Class<?> destination,boolean overlay){
    Intent intent=new Intent(context,destination).putExtra("from_overlay",overlay).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(!(context instanceof Activity)||overlay)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ActivityOptions options=ActivityOptions.makeBasic();
    if(context instanceof Activity&&((Activity)context).isInMultiWindowMode())options.setLaunchBounds(((Activity)context).getWindowManager().getCurrentWindowMetrics().getBounds());
    context.startActivity(intent,options.toBundle());
  }
  static void explain(Activity activity){
    new AlertDialog.Builder(activity).setTitle("使用系统小窗")
      .setMessage(activity.isInMultiWindowMode()?"拖动系统顶部把手移动窗口，拖动边缘调整大小；最小化和全屏由系统菜单控制。下次唤起会回到原窗口，窗口重建时会向系统提供上次大小与位置，是否采用由系统决定。":"打开系统最近任务，点 Zfold 的应用图标，选择「在弹出视图中打开」或「分屏」。小窗的大小与位置会保留，具体窗口模式由系统决定。")
      .setPositiveButton("知道了",null).show();
  }
}
