package dev.zfold.assistant;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;

/** ADB-only geometry check. Does not read or enter text. */
public final class KeyboardInstrumentation extends Instrumentation {
  private String error;private int keyboardTop=-1,panelBottom=-1,sendBottom=-1,imeBottom=-1,navBottom=-1,holdMs;private boolean insetVisible,voiceSource;
  @Override public void onCreate(Bundle arguments){voiceSource=Boolean.parseBoolean(arguments.getString("voice","false"));try{holdMs=Math.min(10000,Integer.parseInt(arguments.getString("holdMs","0")));}catch(NumberFormatException ignored){}start();}
  @Override public void onStart(){
    PiRuntime.testMode=true;
    for(int i=0;i<150&&PhoneAccessibility.instance==null;i++)try{Thread.sleep(200);}catch(InterruptedException ignored){}
    if(PhoneAccessibility.instance==null)error="Accessibility service did not bind";
    if(error==null&&voiceSource){for(int i=0;i<150&&!AssistantSessionService.isShowing();i++)try{Thread.sleep(200);}catch(InterruptedException ignored){}if(!AssistantSessionService.isShowing())error="Assistant session did not appear";}
    if(error==null)runOnMainSync(()->{
      if(!voiceSource)AssistantOverlay.show(getTargetContext());AssistantPanel panel=voiceSource?AssistantSessionService.currentPanel():AssistantOverlay.currentPanel();
      if(panel==null){error="Floating panel did not open";return;}
      panel.input.requestFocus();panel.input.postDelayed(()->{
        ((InputMethodManager)getTargetContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(panel.input,InputMethodManager.SHOW_IMPLICIT);
        if(panel.input.getWindowInsetsController()!=null)panel.input.getWindowInsetsController().show(WindowInsets.Type.ime());
      },250);
    });
    int previousTop=-1,stable=0;
    if(error==null)for(int i=0;i<35;i++){
      try{Thread.sleep(200);}catch(InterruptedException ignored){}
      runOnMainSync(()->{
        AssistantPanel panel=voiceSource?AssistantSessionService.currentPanel():AssistantOverlay.currentPanel();if(panel==null)return;
        int[] location=new int[2];panel.getLocationOnScreen(location);panelBottom=location[1]+panel.getHeight();
        panel.primary.getLocationOnScreen(location);sendBottom=location[1]+panel.primary.getHeight();
        WindowInsets insets=panel.getRootWindowInsets();insetVisible=insets!=null&&insets.isVisible(WindowInsets.Type.ime());if(insets!=null){imeBottom=insets.getInsets(WindowInsets.Type.ime()).bottom;navBottom=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;}
        keyboardTop=-1;for(AccessibilityWindowInfo window:PhoneAccessibility.instance.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect bounds=new Rect();window.getBoundsInScreen(bounds);keyboardTop=bounds.top;break;}
      });
      int screenHeight=getTargetContext().getResources().getDisplayMetrics().heightPixels;
      if(keyboardTop>=0&&screenHeight-keyboardTop>=Ui.dp(getTargetContext(),160)){
        stable=Math.abs(keyboardTop-previousTop)<=4?stable+1:0;
        if(stable>=2)break;
      }
      previousTop=keyboardTop;
    }
    if(holdMs>0)try{Thread.sleep(holdMs);}catch(InterruptedException ignored){}
    Bundle outcome=new Bundle();outcome.putString("error",error);outcome.putInt("keyboardTop",keyboardTop);outcome.putInt("panelBottom",panelBottom);outcome.putInt("sendBottom",sendBottom);outcome.putInt("imeBottom",imeBottom);outcome.putInt("navBottom",navBottom);outcome.putBoolean("imeInsetVisible",insetVisible);outcome.putBoolean("voiceSource",voiceSource);
    finish(error==null&&keyboardTop>=0&&panelBottom<=keyboardTop&&sendBottom<=keyboardTop?-1:0,outcome);
  }
}
