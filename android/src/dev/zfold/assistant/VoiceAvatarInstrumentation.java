package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual avatar click against a synthetic busy turn; no microphone capture. */
public final class VoiceAvatarInstrumentation extends Instrumentation {
  private VoiceCallActivity activity;
  @Override public void onCreate(Bundle arguments){PiRuntime.testMode=true;start();}
  @Override public void onStart(){
    Bundle result=new Bundle();String error="";AtomicBoolean active=new AtomicBoolean(true);VoiceTurnController[] controller=new VoiceTurnController[1];
    try{
      EmulatorChecks.accessibility(this);
      Field foreground=MainActivity.class.getDeclaredField("foregroundVoice");foreground.setAccessible(true);
      Field mode=VoiceTurnController.class.getDeclaredField("mode");mode.setAccessible(true);
      Field utterance=VoiceTurnController.class.getDeclaredField("activeUtterance");utterance.setAccessible(true);
      Field taskVoice=VoiceTurnController.class.getDeclaredField("taskVoice");taskVoice.setAccessible(true);
      Method queue=VoiceTurnController.class.getDeclaredMethod("queue",String.class);queue.setAccessible(true);
      PiRuntime runtime=PiRuntime.get(getTargetContext());
      runOnMainSync(()->{
        controller[0]=new VoiceTurnController(getTargetContext(),new VoiceTurnController.Callback(){
          public boolean active(){return active.get();}
          public void status(String text){VoiceCallActivity.onControllerStatus(text);}
          public void voiceState(boolean session,boolean speaking){VoiceCallActivity.onControllerState(session,speaking);}
          public void prompt(String text){throw new IllegalStateException("Fixture must not submit speech");}
          public void sessionEnded(String reason){}
        });
        try{mode.set(controller[0],VoiceTurnController.Mode.SPEAKING);utterance.set(controller[0],"synthetic-utterance");foreground.set(null,controller[0]);}
        catch(Exception failure){throw new RuntimeException(failure);}
        runtime.busy=true;runtime.stopped=false;
      });
      ActivityMonitor monitor=addMonitor(VoiceCallActivity.class.getName(),null,false);getTargetContext().startActivity(new Intent(getTargetContext(),VoiceCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      activity=(VoiceCallActivity)waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);if(activity==null)throw new IllegalStateException("Voice activity not ready");
      for(int i=0;i<80;i++){boolean[] ready={false};runOnMainSync(()->{View avatar=findAvatar(activity.getWindow().getDecorView());ready[0]=avatar!=null&&avatar.isLaidOut();});if(ready[0])break;Thread.sleep(100);}
      boolean[] checks=new boolean[4];
      runOnMainSync(()->{
        View avatar=findAvatar(activity.getWindow().getDecorView());
        checks[0]=avatar!=null&&avatar.isClickable()&&avatar.isFocusable()&&avatar.getAccessibilityClassName().equals("android.widget.Button");
        checks[1]=avatar!=null&&avatar.performClick()&&controller[0].listeningForInput()&&!controller[0].speaking();
        try{checks[2]=taskVoice.getBoolean(controller[0])&&runtime.busy&&!runtime.stopped;checks[3]=Boolean.FALSE.equals(queue.invoke(controller[0],"旧回答的后续内容"));}
        catch(Exception failure){throw new RuntimeException(failure);}
        // Disable the fixture before the recognizer's delayed start.
        active.set(false);runtime.busy=false;
      });
      boolean[] extra=new boolean[2];
      runOnMainSync(()->{
        try{
          active.set(true);runtime.busy=true;mode.set(controller[0],VoiceTurnController.Mode.SPEAKING);utterance.set(controller[0],"mini-utterance");
          UnifiedOverlay.call(getTargetContext(),"测试语音状态");View mini=findNamed(UnifiedOverlay.captureView(),"点击头像打断语音");
          extra[0]=mini!=null&&mini.performClick()&&controller[0].listeningForInput()&&!controller[0].speaking();active.set(false);UnifiedOverlay.forceHide();
          AssistantPanel panel=new AssistantPanel(getTargetContext(),new AssistantPanel.Host(){public void close(){}public void minimize(){}public void select(){}public void toggleExpansion(){}});
          panel.speakingMode=true;runtime.busy=true;mode.set(panel.voice,VoiceTurnController.Mode.SPEAKING);utterance.set(panel.voice,"panel-utterance");panel.updateControls();
          extra[1]=panel.identityBadge.performClick()&&panel.voice.listeningForInput()&&!panel.voice.speaking()&&Boolean.FALSE.equals(queue.invoke(panel.voice,"旧回答"));
          panel.speakingMode=false;panel.dispose();runtime.busy=false;
        }catch(Exception failure){throw new RuntimeException(failure);}
      });
      result.putBoolean("miniAvatarInterrupts",extra[0]);result.putBoolean("panelAvatarInterrupts",extra[1]);for(boolean check:extra)if(!check)throw new IllegalStateException("Floating avatar binding failed");
      Bitmap screenshot=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
      if(screenshot!=null)try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"voice-avatar-check.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);screenshot.recycle();}
      result.putBoolean("accessibleAvatar",checks[0]);result.putBoolean("avatarInterrupts",checks[1]);result.putBoolean("busyTurnCanListen",checks[2]);result.putBoolean("oldSpeechSuppressed",checks[3]);
      for(boolean check:checks)if(!check)throw new IllegalStateException("Avatar interruption contract failed");
      foreground.set(null,null);
    }catch(Throwable failure){error=failure.toString();result.putString("error",error);}
    finally{
      active.set(false);
      runOnMainSync(()->{MainActivity.clearForegroundVoice();VoiceCallActivity.onControllerState(false,false);if(activity!=null)activity.finish();});
    }
    finish(error.isEmpty()?-1:0,result);
  }
  private static View findNamed(View view,String label){if(view==null)return null;if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=findNamed(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;}return null;}
  private static View findAvatar(View view){
    if(view instanceof VoiceCallActivity.CallOrb)return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findAvatar(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
    return null;
  }
}
