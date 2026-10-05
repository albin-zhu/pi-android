package dev.zfold.assistant;

import android.app.*;
import android.graphics.Rect;
import android.os.Bundle;
import java.util.concurrent.*;

/** User-facing entry runs with app permissions; shell only prepares the emulator fixture. */
public final class WindowEntryInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){Bundle report=new Bundle();String oldFreeform=null;boolean saved=false;int code=Activity.RESULT_OK;
    try{
      String product=android.os.Build.PRODUCT;
      if(!product.contains("sdk")&&!product.contains("emulator"))throw new IllegalStateException("Emulator only");
      PiRuntime.testMode=true;
      oldFreeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();saved=true;report.putString("previousFreeformSetting",oldFreeform);
      EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");
      EmulatorChecks.accessibility(this);
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");
      for(int i=0;i<100&&MainActivity.current()==null;i++)Thread.sleep(100);
      MainActivity chat=MainActivity.current();check(chat!=null&&!chat.isInMultiWindowMode(),"Fullscreen fixture unavailable");
      int task=chat.getTaskId();
      runOnMainSync(()->{ScreenContext.setEnabled(false);chat.input.setText("窗口切换保留这段草稿");chat.input.setSelection(4);});
      Thread.sleep(200);
      CountDownLatch done=new CountDownLatch(2);boolean[] result={false},second={false};
      runOnMainSync(()->{SystemWindowNavigation.enterSmallWindow(chat,ok->{result[0]=ok;done.countDown();});SystemWindowNavigation.enterSmallWindow(chat,ok->{second[0]=ok;done.countDown();});});
      check(done.await(5,TimeUnit.SECONDS)&&result[0],"App-owned window request was not accepted");Thread.sleep(400);
      check(chat==MainActivity.current()&&chat.getTaskId()==task&&chat.isInMultiWindowMode(),"Window transition replaced the chat or task");
      check(chat.input.getText().toString().equals("窗口切换保留这段草稿")&&chat.input.getSelectionStart()==4,"Draft/cursor was lost");
      check(second[0],"Coalesced request lost its completion");report.putBoolean("coalescesRepeatedRequests",true);
      report.putBoolean("appPermissionRequestEntersSystemWindow",true);report.putBoolean("sameTaskChatDraftAndCursor",true);
      Rect first=chat.getWindowManager().getCurrentWindowMetrics().getBounds();
      CountDownLatch repeat=new CountDownLatch(1);
      runOnMainSync(()->SystemWindowNavigation.enterSmallWindow(chat,ok->{result[0]=ok;repeat.countDown();}));
      check(repeat.await(3,TimeUnit.SECONDS)&&result[0]&&first.equals(chat.getWindowManager().getCurrentWindowMetrics().getBounds()),"Repeated entry reset geometry");
      report.putBoolean("existingWindowRetainsGeometry",true);
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");
      for(int i=0;i<50&&chat.isInMultiWindowMode();i++)Thread.sleep(100);
      check(!chat.isInMultiWindowMode(),"Could not restore fullscreen fixture");
      runOnMainSync(chat.screenShareButton::performClick);
      for(int i=0;i<50&&!ScreenContext.enabled();i++)Thread.sleep(100);
      check(chat.isInMultiWindowMode()&&ScreenContext.enabled(),"Look-at-screen entry did not enter small window and enable context");
      check(chat==MainActivity.current()&&chat.getTaskId()==task&&chat.input.getText().toString().equals("窗口切换保留这段草稿"),"Look-at-screen entry changed chat/draft");
      check(!AssistantOverlay.isShowing(),"Entry opened old overlay chat");report.putBoolean("screenButtonEnablesOnlyAfterWindowTransition",true);report.putBoolean("noLegacyChatOverlay",true);
      runOnMainSync(chat.screenShareButton::performClick);check(!ScreenContext.enabled(),"Screen context did not turn off");report.putBoolean("screenButtonCanDisable",true);
      EmulatorChecks.screenshot(this,"window-entry.png");
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{if(saved)try{runOnMainSync(()->ScreenContext.setEnabled(false));EmulatorChecks.shell(this,"settings "+(oldFreeform.equals("null")?"delete global enable_freeform_support":"put global enable_freeform_support "+oldFreeform));}catch(Exception error){report.putString("cleanupFailure",error.toString());code=Activity.RESULT_CANCELED;}}
    finish(code,report);
  }
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
