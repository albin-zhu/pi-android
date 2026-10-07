package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.voice.*;

/** System assistant entry only. Chat is rendered by the same normal application task. */
public final class AssistantSessionService extends VoiceInteractionSessionService {
  private static Session active;
  private static Bitmap assistShot;private static long assistShotAt;
  static synchronized void keepAssistShot(Bitmap bitmap){if(assistShot!=null&&assistShot!=bitmap)assistShot.recycle();assistShot=bitmap;assistShotAt=android.os.SystemClock.uptimeMillis();}
  static synchronized Bitmap takeAssistShot(){
    if(assistShot==null||android.os.SystemClock.uptimeMillis()-assistShotAt>2500)return null;
    Bitmap shot=assistShot;assistShot=null;return shot;
  }
  @Override public VoiceInteractionSession onNewSession(Bundle args){return new Session(this);}
  static boolean isShowing(){return active!=null;}
  static AssistantPanel currentPanel(){return null;}
  static void yieldToCall(){if(active!=null)active.hide();}
  static void yieldToOverlay(){if(active!=null)active.hide();}
  static void startVoice(){if(active!=null)SystemWindowNavigation.showChat(active.context,true);}
  static boolean handoffToBubble(Context context){return false;}
  static final class Session extends VoiceInteractionSession {
    final Context context;private long invocation;
    Session(Context context){super(context);this.context=context;}
    @Override public void onPrepareShow(Bundle args,int flags){super.onPrepareShow(args,flags);setUiEnabled(false);}
    @Override public void onShow(Bundle args,int flags){
      super.onShow(args,flags);active=this;invocation=SystemAssistEntry.begin();
      if(AssistantOverlay.currentPanel()!=null)AssistantOverlay.preserveDraft(AssistantOverlay.currentPanel().draft());
      AssistantOverlay.hide();CircleOverlay.hideBubble();UnifiedOverlay.forceHide();
      if(CircleAskPrefs.assistInvokesCircle(context)){
        hide();
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(()->{
          Bitmap shot=takeAssistShot();
          if(shot!=null)CircleOverlay.showSelection(context,shot,data->SelectionAskOverlay.showImage(context,data));
          else CircleOverlay.beginOnCurrentScreen(context);
        },180);
        return;
      }
      try{SystemWindowNavigation.showChat(context,false);MainActivity main=MainActivity.current();if(main!=null)SystemAssistEntry.attach(main);}
      catch(RuntimeException error){android.widget.Toast.makeText(context,"系统未允许恢复聊天，请从应用图标打开",android.widget.Toast.LENGTH_LONG).show();}
      finally{hide();}
    }
    @Override public void onHandleScreenshot(Bitmap screenshot){
      if(screenshot==null)return;
      Bitmap copy=screenshot.copy(Bitmap.Config.ARGB_8888,false);
      if(CircleAskPrefs.assistInvokesCircle(context))keepAssistShot(copy);
      else SystemAssistEntry.offer(invocation,PhoneAccessibility.encode(copy));
    }
    @Override public void onHide(){if(active==this)active=null;super.onHide();}
    @Override public void onDestroy(){if(active==this)active=null;super.onDestroy();}
  }
}
