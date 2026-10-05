package dev.zfold.assistant;
import android.content.*;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
public final class AssistantOverlay {
  private static AssistantPanel panel;private static WindowManager manager;private static boolean accessibilityPanel;
  private static WindowManager.LayoutParams params;private static boolean expanded;private static int keyboardInset;
  private static AssistantPanel.Draft savedDraft;
  static boolean isShowing(){return panel!=null;}
  static AssistantPanel currentPanel(){return panel;}
  static boolean isAccessibilityPanel(){return panel!=null&&accessibilityPanel;}
  static void preserveDraft(AssistantPanel.Draft draft){savedDraft=draft;}
  static boolean hasSavedDraft(){return savedDraft!=null;}
  static AssistantPanel.Draft takeDraft(){AssistantPanel.Draft result=savedDraft;savedDraft=null;return result;}
  static boolean minimizeForPhoneControl(Context context){
    if(panel==null)return false;
    savedDraft=panel.draft();hide();CircleOverlay.ensureBubble(context);return true;
  }
  static void show(Context context){show(context,null);}
  static void showForVoice(Context context){
    SystemWindowNavigation.showChat(context,true);
  }
  static void show(Context context,String image){
    AssistantSessionService.yieldToOverlay();UnifiedOverlay.forceHide();
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
    params=new WindowManager.LayoutParams(type,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;params.setFitInsetsTypes(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;keyboardInset=0;expanded=false;
    applySurfaceMode(app,false);
    panel.setOnApplyWindowInsetsListener((view,insets)->{
      if(view!=panel||manager==null||params==null)return insets;
      int nav=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
      WindowInsets global=manager.getMaximumWindowMetrics().getWindowInsets();
      if(!insets.isVisible(WindowInsets.Type.ime())&&!global.isVisible(WindowInsets.Type.ime()))keyboardInset=0;
      else{int measured=Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom,global.getInsets(WindowInsets.Type.ime()).bottom);if(measured>0)keyboardInset=measured;}
      ((AssistantPanel)view).setBottomInset(0);applySurfaceMode(app,expanded);return insets;
    });
    try{manager.addView(panel,params);accessibilityPanel=service!=null;panel.restore(savedDraft);savedDraft=null;panel.setExpanded(false);expanded=false;if(image!=null)panel.attachImage(image,"已附上圈选内容");Ui.arrive(panel);}catch(RuntimeException error){panel.dispose();panel=null;accessibilityPanel=false;Toast.makeText(app,"悬浮窗未能显示，请检查权限",Toast.LENGTH_LONG).show();}
  }
  static void setExpanded(boolean value){
    if(panel==null||manager==null)return;
    expanded=value;applySurfaceMode(panel.getContext().getApplicationContext(),value);panel.setExpanded(value);
  }
  private static void applySurfaceMode(Context app,boolean full){
    if(params==null)return;
    if(panel==null||manager==null)return;
    WindowMetrics metrics=manager.getMaximumWindowMetrics();android.graphics.Rect bounds=metrics.getBounds();
    android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
    int available=Math.max(1,bounds.height()-bars.top-Math.max(bars.bottom,keyboardInset));
    int height=full?available:Math.min(available,Math.round(bounds.height()*.58f));
    int offset=Math.max(bars.bottom,keyboardInset),width=Math.max(1,bounds.width()-bars.left-bars.right),xOffset=(bars.left-bars.right)/2;
    boolean changed=params.height!=height||params.y!=offset||params.width!=width||params.x!=xOffset||params.gravity!=(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
    params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;params.width=width;params.height=height;params.x=xOffset;params.y=offset;
    params.setFitInsetsTypes(0);params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    if(changed&&panel.isAttachedToWindow())manager.updateViewLayout(panel,params);
  }
  static void hide(){if(panel==null)return;AssistantPanel old=panel;panel=null;old.dispose();try{manager.removeView(old);}catch(IllegalArgumentException ignored){}manager=null;accessibilityPanel=false;}
  static void accessibilityDisconnected(Context context){if(accessibilityPanel){savedDraft=panel.draft();hide();if(Settings.canDrawOverlays(context))CircleOverlay.ensureBubble(context);}}
}
