package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import java.util.function.BooleanSupplier;

/** Real assistant-role invocation restores normal chat; the host harness owns role setup/restore. */
public final class SystemEntryInstrumentation extends Instrumentation {
  private MainActivity chat;
  @Override public void onCreate(Bundle args){if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle r=new Bundle();r.putString("error","Emulator only");finish(0,r);return;}PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;
    try{
      check(EmulatorChecks.shell(this,"cmd role get-role-holders android.app.role.ASSISTANT").trim().equals("dev.zfold.assistant"),"Configure emulator assistant role before instrumentation");
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");await(()->MainActivity.current()!=null&&MainActivity.current().runtime.ready,"Chat missing");chat=MainActivity.current();int task=chat.getTaskId();PiRuntime runtime=chat.runtime;
      Bitmap pixel=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);pixel.eraseColor(0xff31a27c);String image=PhoneAccessibility.encode(pixel);
      runOnMainSync(()->{chat.input.setText("系统入口草稿");chat.pendingImage=image;chat.pendingImageLabel="已有附件";chat.updateDraftAttachment();});
      getTargetContext().startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(350);
      invoke();await(()->chat.hasWindowFocus(),"System entry did not bring chat to front");
      check(chat==MainActivity.current()&&chat.getTaskId()==task&&chat.runtime==runtime,"System entry created a second chat");check(AssistantSessionService.currentPanel()==null&&!AssistantOverlay.isShowing(),"Legacy panel was created");report.putBoolean("systemRoleRestoresSameMainTask",true);
      runOnMainSync(()->SystemAssistEntry.offer(SystemAssistEntry.currentId(),image));check(chat.input.getText().toString().equals("系统入口草稿")&&image.equals(chat.pendingImage),"System entry replaced draft or attachment");report.putBoolean("existingDraftAndAttachmentPreserved",true);
      invoke();await(()->chat.hasWindowFocus(),"Repeated invocation lost focus");check(MainActivity.current()==chat&&chat.getTaskId()==task,"Repeated invocation created another task");report.putBoolean("repeatedInvocationDoesNotDuplicateChat",true);
      runOnMainSync(()->{chat.input.setText("");chat.pendingImage=null;chat.updateDraftAttachment();});invoke();await(()->chat.hasWindowFocus(),"Chat not focused for context");runOnMainSync(()->SystemAssistEntry.offer(SystemAssistEntry.currentId(),image));check(image.equals(chat.pendingImage),"Framework context did not reach empty draft");report.putBoolean("frameworkContextUsesMainDraft",true);
      runOnMainSync(()->{chat.pendingImage=null;chat.updateDraftAttachment();long old=SystemAssistEntry.currentId();SystemAssistEntry.begin();SystemAssistEntry.offer(old,image);});check(chat.pendingImage==null,"Stale context was attached");
      runOnMainSync(()->{SystemAssistEntry.cancel();SystemAssistEntry.offer(SystemAssistEntry.currentId(),image);});check(chat.pendingImage==null,"Context arrived after submission/cancel");report.putBoolean("lateAndCancelledContextRejected",true);
      EmulatorChecks.screenshot(this,"system-assistant-main.png");
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{
      runOnMainSync(()->{AssistantSessionService.yieldToOverlay();SystemAssistEntry.cancel();if(chat!=null)chat.finish();});

    }
    finish(code,report);
  }
  private void invoke()throws Exception{long before=SystemAssistEntry.currentId();for(int i=0;i<150;i++){boolean[] shown={false};runOnMainSync(()->shown[0]=AssistantVoiceService.showCurrentSession());if(shown[0]){await(()->SystemAssistEntry.currentId()>before,"Session did not route invocation");return;}Thread.sleep(50);}throw new IllegalStateException("Voice service not ready");}
  private void await(BooleanSupplier condition,String error)throws Exception{for(int i=0;i<200;i++){boolean[] ok={false};runOnMainSync(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(50);}throw new IllegalStateException(error);}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
