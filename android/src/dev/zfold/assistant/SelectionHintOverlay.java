package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Tiny chip beside a system text selection; PROCESS_TEXT is often hidden on One UI. */
final class SelectionHintOverlay {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static FrameLayout chip;
  private static WindowManager manager;
  private static String pendingText="";
  private static final Runnable hideLater=SelectionHintOverlay::hide;

  static void offer(Context base,String text,Rect screenBounds){
    if(!CircleAskPrefs.selectionHint(base)||text==null)return;
    String trimmed=text.trim();
    if(trimmed.length()<2){main.post(SelectionHintOverlay::hide);return;}
    if(trimmed.length()>4000)trimmed=trimmed.substring(0,4000);
    pendingText=trimmed;
    Rect bounds=screenBounds==null?new Rect():new Rect(screenBounds);
    main.post(()->show(base,bounds));
  }

  private static void show(Context base,Rect bounds){
    hideImmediate();
    PhoneAccessibility service=PhoneAccessibility.instance;
    Context app=base.getApplicationContext();
    if(service==null&&!Settings.canDrawOverlays(app))return;
    Context host=service!=null?service:app;
    WindowManager wm=(WindowManager)host.getSystemService(Context.WINDOW_SERVICE);
    FrameLayout body=new FrameLayout(host);
    TextView label=new TextView(host);
    label.setText("问");
    label.setTextColor(0xffedf4ff);
    label.setTextSize(13);
    label.setGravity(Gravity.CENTER);
    label.setBackground(Ui.shape(app,0xf0141820,16,Ui.dp(app,1),0xff3b82f6));
    int size=Ui.dp(app,32);
    body.addView(label,new FrameLayout.LayoutParams(size,size));
    body.setContentDescription("问橙橙");
    body.setOnClickListener(v->{String text=pendingText;hideImmediate();SelectionAskOverlay.showText(app,text);});
    android.graphics.Rect display=wm.getMaximumWindowMetrics().getBounds();
    int x=Math.min(display.right-size-Ui.dp(app,8),Math.max(display.left+Ui.dp(app,8),bounds.right+Ui.dp(app,6)));
    int y=Math.min(display.bottom-size-Ui.dp(app,48),Math.max(display.top+Ui.dp(app,48),bounds.top-Ui.dp(app,4)));
    int type=service!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    WindowManager.LayoutParams params=new WindowManager.LayoutParams(size,size,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    params.gravity=Gravity.TOP|Gravity.LEFT;params.x=x;params.y=y;
    try{wm.addView(body,params);manager=wm;chip=body;main.removeCallbacks(hideLater);main.postDelayed(hideLater,8000);}catch(RuntimeException ignored){}
  }

  static void hide(){main.post(SelectionHintOverlay::hideImmediate);}
  private static void hideImmediate(){
    main.removeCallbacks(hideLater);
    if(chip==null)return;
    try{manager.removeView(chip);}catch(Exception ignored){}
    chip=null;manager=null;
  }
}
