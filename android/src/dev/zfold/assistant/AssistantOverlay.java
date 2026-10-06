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
    AssistantSessionService.yieldToOverlay();hide();UnifiedOverlay.forceHide();CircleOverlay.hideBubble();
    if(image!=null)CircleOverlay.selectedImage=image;
    SystemWindowNavigation.showChat(context,false);
    MainActivity main=MainActivity.current();if(main!=null)main.root.post(()->{main.acceptLegacyDraft();main.acceptImage();});
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
