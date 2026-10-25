package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.voice.*;

/** System assistant entry only; selection and durable drafts belong to the terminal. */
public final class AssistantSessionService extends VoiceInteractionSessionService {
  private static Session active;
  private static Bitmap assistShot;private static long assistShotAt;
  static synchronized void keepAssistShot(Bitmap bitmap){
    if(assistShot!=null&&assistShot!=bitmap)assistShot.recycle();assistShot=bitmap;assistShotAt=android.os.SystemClock.uptimeMillis();
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
      synchronized(AssistantSessionService.class){if(assistShot==bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();assistShot=null;}}
    },2500);
  }
  static synchronized Bitmap takeAssistShot(){
    if(assistShot==null)return null;
    if(android.os.SystemClock.uptimeMillis()-assistShotAt>2500){assistShot.recycle();assistShot=null;return null;}
    Bitmap shot=assistShot;assistShot=null;return shot;
  }
  @Override public VoiceInteractionSession onNewSession(Bundle args){return new Session(this);}
  static void yieldToCall(){if(active!=null)active.hide();}
  static final class Session extends VoiceInteractionSession {
    final Context context;private long shownAt;
    Session(Context context){super(context);this.context=context;}
    @Override public void onPrepareShow(Bundle args,int flags){super.onPrepareShow(args,flags);setUiEnabled(false);}
    @Override public void onShow(Bundle args,int flags){
      super.onShow(args,flags);active=this;shownAt=android.os.SystemClock.uptimeMillis();
      if(CircleAskPrefs.assistInvokesCircle(context)){
        hide();
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(()->{
          Bitmap shot=takeAssistShot();
          if(shot!=null)CircleOverlay.selectForTerminal(context,shot);
          else CircleOverlay.beginOnCurrentScreen(context);
        },180);
        return;
      }
      try{TerminalActivity.open(context);}
      catch(RuntimeException error){android.widget.Toast.makeText(context,"系统未允许打开终端，请从应用图标打开",android.widget.Toast.LENGTH_LONG).show();}
      finally{hide();}
    }
    @Override public void onHandleScreenshot(Bitmap screenshot){
      if(screenshot==null)return;
      if(android.os.SystemClock.uptimeMillis()-shownAt>2500)return;
      Bitmap copy=screenshot.copy(Bitmap.Config.ARGB_8888,false);if(copy==null)return;
      if(CircleAskPrefs.assistInvokesCircle(context))keepAssistShot(copy);
      else new Thread(()->{
        try{
          TerminalCircleDraft.capture(context,copy);
          new android.os.Handler(android.os.Looper.getMainLooper()).post(()->TerminalActivity.open(context));
        }catch(java.io.IOException error){
          new android.os.Handler(android.os.Looper.getMainLooper()).post(()->android.widget.Toast.makeText(context,error.getMessage(),android.widget.Toast.LENGTH_LONG).show());
        }finally{copy.recycle();}
      },"assistant-terminal-draft").start();
    }
    @Override public void onHide(){if(active==this)active=null;super.onHide();}
    @Override public void onDestroy(){if(active==this)active=null;super.onDestroy();}
  }
}
