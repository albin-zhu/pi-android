package dev.zfold.assistant;
import android.content.*;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
public final class AssistantOverlay {
  private static AssistantPanel panel;private static WindowManager manager;private static boolean accessibilityPanel;
  private static WindowManager.LayoutParams params;private static boolean expanded;
  private static AssistantPanel.Draft savedDraft;
  static boolean isShowing(){return panel!=null;}
  static AssistantPanel currentPanel(){return panel;}
  static boolean isAccessibilityPanel(){return panel!=null&&accessibilityPanel;}
  static void preserveDraft(AssistantPanel.Draft draft){savedDraft=draft;}
  static boolean minimizeForPhoneControl(Context context){
    if(panel==null)return false;
    savedDraft=panel.draft();hide();CircleOverlay.ensureBubble(context);return true;
  }
  static void show(Context context){show(context,null);}
  static void showForVoice(Context context){
    show(context);AssistantPanel panel=currentPanel();if(panel!=null)panel.wakeVoice();
  }
  static void show(Context context,String image){
    if(panel!=null){if(savedDraft!=null){panel.restore(savedDraft);savedDraft=null;}if(image!=null)panel.attachImage(image,"已附上圈选内容");return;}
    Context app=context.getApplicationContext();
    PhoneAccessibility service=PhoneAccessibility.instance;
    if(service==null&&!Settings.canDrawOverlays(app)){Toast.makeText(app,"请先允许 Zfold 显示悬浮窗",Toast.LENGTH_LONG).show();app.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+app.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
    Context windowContext=service!=null?service:app;
    CircleOverlay.hideBubble();manager=(WindowManager)windowContext.getSystemService(Context.WINDOW_SERVICE);
    panel=new AssistantPanel(new android.view.ContextThemeWrapper(windowContext,R.style.AppTheme),new AssistantPanel.Host(){
      public void close(){ScreenContext.setEnabled(false);savedDraft=panel.draft();hide();CircleOverlay.hideBubble();}
      public void minimize(){savedDraft=panel.draft();hide();CircleOverlay.ensureBubble(app);}
      public void select(){savedDraft=panel.draft();hide();CircleOverlay.begin(app,(data)->show(app,data));}
      public void toggleExpansion(){setExpanded(!expanded);}
    });
    int type=service!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    params=new WindowManager.LayoutParams(type,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
    params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;params.setFitInsetsTypes(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
    applySurfaceMode(app,false);
    panel.setOnApplyWindowInsetsListener((view,insets)->{int nav=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;int ime=insets.getInsets(WindowInsets.Type.ime()).bottom;((AssistantPanel)view).setBottomInset(ime>nav?0:nav);return insets;});
    try{manager.addView(panel,params);accessibilityPanel=service!=null;panel.restore(savedDraft);savedDraft=null;panel.setExpanded(false);expanded=false;if(image!=null)panel.attachImage(image,"已附上圈选内容");Ui.arrive(panel);}catch(RuntimeException error){panel.dispose();panel=null;accessibilityPanel=false;Toast.makeText(app,"悬浮窗未能显示，请检查权限",Toast.LENGTH_LONG).show();}
  }
  static void setExpanded(boolean value){
    if(panel==null||manager==null)return;
    expanded=value;applySurfaceMode(panel.getContext().getApplicationContext(),value);panel.setExpanded(value);
  }
  private static void applySurfaceMode(Context app,boolean full){
    if(params==null)return;
    if(panel==null||manager==null)return;
    android.graphics.Rect bounds=((WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
    params.gravity=full?Gravity.CENTER:Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
    params.width=full?WindowManager.LayoutParams.MATCH_PARENT:WindowManager.LayoutParams.MATCH_PARENT;
    params.height=full?WindowManager.LayoutParams.MATCH_PARENT:Math.round(bounds.height()*.58f);
    params.x=0;params.y=0;
    if(full)params.setFitInsetsTypes(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
    else params.setFitInsetsTypes(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
    if(panel.isAttachedToWindow())manager.updateViewLayout(panel,params);
  }
  static void hide(){if(panel==null)return;AssistantPanel old=panel;panel=null;old.dispose();try{manager.removeView(old);}catch(IllegalArgumentException ignored){}manager=null;accessibilityPanel=false;}
  static void accessibilityDisconnected(Context context){if(accessibilityPanel){savedDraft=panel.draft();hide();if(Settings.canDrawOverlays(context))CircleOverlay.ensureBubble(context);}}
}
