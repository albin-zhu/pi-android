package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import java.util.*;

/** Explicit, per-turn screen context. There is never a cached image to send on failure. */
final class ScreenContext {
  interface Capture {void done(String image,String label,String error);}
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Set<Runnable> listeners=new LinkedHashSet<>();
  private static volatile boolean enabled,busy;private static int generation;
  private static long lastCapture;private static String target="";
  static boolean enabled(){return enabled;}
  static boolean busy(){return busy;}
  static void cancelCapture(){if(busy)generation++;}
  static String label(){return enabled?(target.isEmpty()?"看屏幕 · 每次发送刷新":"看屏幕 · "+target):"看屏幕";}
  static void listen(Runnable listener){listeners.add(listener);}
  static void unlisten(Runnable listener){listeners.remove(listener);}
  private static void changed(){for(Runnable listener:new ArrayList<>(listeners))listener.run();}
  static void setEnabled(boolean value){if(value)CameraCoordinator.disableVideo();if(enabled==value)return;enabled=value;generation++;if(!value)target="";changed();}
  static void capture(Context context,Capture callback){
    if(!enabled){callback.done(null,null,"看屏幕已关闭");return;}
    if(busy){callback.done(null,null,"正在读取屏幕，请稍候");return;}
    PhoneAccessibility service=PhoneAccessibility.instance;
    if(service==null){setEnabled(false);callback.done(null,null,"需要先开启读屏与操作服务");return;}
    if(context instanceof android.app.Activity&&((android.app.Activity)context).isInMultiWindowMode()&&Build.VERSION.SDK_INT<34){callback.done(null,null,"当前 Android 版本不支持单独读取旁边的应用窗口");return;}
    busy=true;int request=generation;long started=SystemClock.uptimeMillis();
    ArrayList<View> hidden=new ArrayList<>();
    for(View view:new View[]{AssistantOverlay.currentPanel(),AssistantSessionService.currentPanel(),CircleOverlay.captureView(),UnifiedOverlay.captureView()}){
      if(view!=null&&view.getVisibility()==View.VISIBLE){view.setVisibility(View.INVISIBLE);hidden.add(view);}
    }
    final boolean[] finished={false};final Runnable[] timeout={null};
    Capture complete=(image,label,error)->{
      if(finished[0])return;finished[0]=true;main.removeCallbacks(timeout[0]);
      for(View view:hidden)if(view.isAttachedToWindow())view.setVisibility(View.VISIBLE);
      busy=false;if(request!=generation||!enabled){callback.done(null,null,"本次屏幕读取已取消");return;}
      if(error==null){target=label;changed();}callback.done(image,label,error);
    };
    timeout[0]=()->complete.done(null,null,"屏幕读取超时，请重试");main.postDelayed(timeout[0],7000);
    // Let the compositor remove all assistant surfaces, and let the keyboard finish dismissing.
    Choreographer.getInstance().postFrameCallback(first->Choreographer.getInstance().postFrameCallback(second->{
      long delay=Math.max(250-(SystemClock.uptimeMillis()-started),400-(SystemClock.uptimeMillis()-lastCapture));
      main.postDelayed(()->{
        if(finished[0])return;
        if(request!=generation||!enabled){complete.done(null,null,"本次屏幕读取已取消");return;}
        String app=service.screenApplicationPackage();
        if(app.isEmpty()||app.equals(context.getPackageName())){complete.done(null,null,"请在小窗旁边打开要查看的应用，再发送");return;}
        lastCapture=SystemClock.uptimeMillis();
        try{service.captureApplication(app,(bitmap,error)->main.post(()->{
          if(finished[0]){if(bitmap!=null)bitmap.recycle();return;}
          if(error!=null){complete.done(null,null,error);return;}
          if(!app.equals(service.screenApplicationPackage())){bitmap.recycle();complete.done(null,null,"应用刚刚切换，请重新发送以读取新画面");return;}
          String label=app;try{label=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(app,0)).toString();}catch(Exception ignored){}
          complete.done(PhoneAccessibility.encode(bitmap),label,null);
        }));}catch(RuntimeException failure){complete.done(null,null,"读屏服务已断开，请重新开启后重试");}
      },Math.max(0,delay));
    }));
  }
}
