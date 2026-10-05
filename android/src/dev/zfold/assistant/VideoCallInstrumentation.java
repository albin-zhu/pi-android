package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.MessageDigest;
import org.json.*;

/** Real emulator camera and native-to-Pi JPEG transport, without recording audio. */
public final class VideoCallInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private PiRuntime runtime;private VoiceCallActivity activity;private CameraCoordinator camera;private volatile String runtimeError;
  private String oldFreeform;private boolean savedFreeform,rotationChanged;private int oldAutoRotation,oldRotation;
  private final CountDownLatch configured=new CountDownLatch(1);private volatile CountDownLatch turn;private CountDownLatch changed;
  @Override public void onCreate(Bundle args){if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle result=new Bundle();result.putString("error","Emulator only");finish(0,result);return;}PiRuntime.testMode=true;start();}
  public void event(JSONObject event){String type=event.optString("type");if(type.equals("configured"))configured.countDown();if(type.equals("session_changed")&&changed!=null)changed.countDown();if(type.equals("turn_end")&&turn!=null)turn.countDown();if(type.equals("error"))runtimeError=event.optString("message");}
  @Override public void onStart(){Bundle report=new Bundle();String error="";
    try{
      getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).grantRuntimePermission(getTargetContext().getPackageName(),android.Manifest.permission.CAMERA);
      mainChecked(()->{runtime=PiRuntime.get(getTargetContext());runtime.listen(this);camera=CameraCoordinator.get(getTargetContext());camera.choose("back");});
      for(int i=0;i<250&&!runtime.ready;i++)Thread.sleep(100);check(runtime.ready&&runtime.isTestRuntime(),"Isolated runtime unavailable");
      mainChecked(()->runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl","http://127.0.0.1:18876/v1","model","video-fixture","apiKey","fixture","api","openai-completions"))));check(configured.await(20,TimeUnit.SECONDS),"Fixture not configured: "+runtimeError);
      changed=new CountDownLatch(1);mainChecked(()->runtime.send(PiRuntime.json("type","new_session")));check(changed.await(10,TimeUnit.SECONDS),"Fresh session unavailable");
      activity=(VoiceCallActivity)startActivitySync(new Intent(getTargetContext(),VoiceCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500);
      click("开启摄像头");awaitCamera();EmulatorChecks.screenshot(this,"video-preview.png");
      CameraFrame first=capture();check(first.jpeg[0]==(byte)255&&first.jpeg[1]==(byte)216&&first.width<=1280&&first.height<=1280,"Real camera JPEG invalid");java.nio.file.Files.write(new File(getTargetContext().getCacheDir(),"video-encoded.jpg").toPath(),first.jpeg);report.putBoolean("realCameraJpeg",true);
      send("First camera frame");check(runtime.lastPromptHasImage,"Video prompt missed image");verifyHash();
      send("Second camera frame");verifyHash();report.putBoolean("nativeJpegToPi",true);
      JSONObject queued=PiRuntime.json("type","prompt","id","queued-camera-fixture","text","Queued camera frame");CountDownLatch accepted=new CountDownLatch(1);AtomicBoolean queuedOk=new AtomicBoolean();
      mainChecked(()->{runtime.busy=true;PromptDispatcher.get(getTargetContext()).submit(queued,true,(ok,inQueue,problem)->{queuedOk.set(ok&&inQueue);accepted.countDown();});});check(accepted.await(12,TimeUnit.SECONDS)&&queuedOk.get(),"Camera request did not queue");
      String frozen=queued.getJSONArray("images").getJSONObject(0).getString("data");turn=new CountDownLatch(1);
      mainChecked(()->{camera.disable();runtime.busy=false;PromptDispatcher.get(getTargetContext()).event(PiRuntime.json("type","error","message","fixture preceding request failed"));});check(turn.await(20,TimeUnit.SECONDS),"Queued request lost after preceding failure");check(frozen.equals(runtime.lastPromptImage),"Queued request re-captured instead of using its frame");verifyHash();report.putBoolean("queuedFrameSurvivesPreviousFailure",true);
      click("开启摄像头");awaitCamera();
      mainChecked(()->{
        VoiceCoordinator coordinator=VoiceCoordinator.get(getTargetContext());
        try{java.lang.reflect.Field active=VoiceCoordinator.class.getDeclaredField("session"),mode=VoiceTurnController.class.getDeclaredField("mode");active.setAccessible(true);mode.setAccessible(true);active.setBoolean(coordinator,true);mode.set(coordinator.controller,VoiceTurnController.Mode.THINKING);}
        catch(Exception failure){throw new IllegalStateException(failure);}
        coordinator.submit("Cancelled before camera attachment",true);coordinator.controller.stopSession(false);
        boolean inactive=!coordinator.controller.sessionActive();coordinator.controller.stopSession(false);
        check(inactive,"A cancelled camera attachment restarted the microphone session");
      });
      EmulatorChecks.click(this,"保留");click("开启摄像头");awaitCamera();report.putBoolean("captureCancellationDoesNotRestartVoice",true);
      AtomicInteger callbacks=new AtomicInteger();AtomicBoolean staleDelivered=new AtomicBoolean();
      mainChecked(()->{camera.capture("cancel-by-switch",(frame,problem)->{callbacks.incrementAndGet();staleDelivered.set(frame!=null);});camera.switchLens();});Thread.sleep(1200);
      check(callbacks.get()==1&&!staleDelivered.get(),"Old lens frame escaped after switch");
      mainChecked(()->{camera.choose("back");camera.resume();});awaitCamera();report.putBoolean("lensSwitchCancelsOldFrame",true);
      mainChecked(()->camera.pause(activity));CameraFrame[] paused={null};String[] failure={null};mainChecked(()->camera.capture("paused",(frame,problem)->{paused[0]=frame;failure[0]=problem;}));check(paused[0]==null&&failure[0]!=null,"Paused camera captured");
      click("恢复摄像头");awaitCamera();report.putBoolean("pauseAndExplicitResume",true);
      mainChecked(()->camera.disable());send("Text after camera off");check(!runtime.lastPromptHasImage&&runtime.lastResponse.contains("NONE"),"Camera-off prompt acquired an image");report.putBoolean("cameraOffNoNewFrame",true);
      // The capture tool must open a visible surface and return both a JPEG and a real file.
      CountDownLatch photo=new CountDownLatch(1);JSONObject[] photoResult={null};String[] photoError={null};
      ActivityMonitor photoMonitor=addMonitor(CameraCaptureActivity.class.getName(),null,false);
      mainChecked(()->AndroidTools.execute(getTargetContext(),PiRuntime.json("id","fixture-photo","name","camera_capture","args",PiRuntime.json("facing","back")),(value,problem)->{photoResult[0]=value;photoError[0]=problem;photo.countDown();}));
      Activity photoActivity=waitForMonitorWithTimeout(photoMonitor,5000);removeMonitor(photoMonitor);check(photoActivity!=null,"Visible camera surface missing");mainChecked(()->activity.finish());
      check(photo.await(20,TimeUnit.SECONDS)&&photoError[0]==null&&photoResult[0]!=null&&photoResult[0].optString("status").equals("captured"),"Photo tool: "+photoError[0]+" / "+photoResult[0]);
      File saved=new File(photoResult[0].getString("path"));check(saved.isFile()&&saved.length()>0&&photoResult[0].getString("mimeType").equals("image/jpeg"),"Photo file unavailable");saved.delete();report.putBoolean("photoToolReturnsImageAndFile",true);report.putBoolean("photoSurvivesCallClosure",true);
      Thread.sleep(500);mainChecked(()->activity.finish());Thread.sleep(300);
      oldFreeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();savedFreeform=true;EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");
      EmulatorChecks.shell(this,"am start --windowingMode 5 -n dev.zfold.assistant/.MainActivity");for(int i=0;i<100&&MainActivity.current()==null;i++)Thread.sleep(100);MainActivity chat=MainActivity.current();check(chat!=null&&chat.isInMultiWindowMode(),"Freeform host unavailable");
      ActivityMonitor monitor=addMonitor(VoiceCallActivity.class.getName(),null,false);mainChecked(()->VoiceCallActivity.show(chat));activity=(VoiceCallActivity)waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);check(activity!=null&&activity.isInMultiWindowMode(),"Call escaped system window");
      int width=Ui.dp(activity,239),height=Ui.dp(activity,282);EmulatorChecks.shell(this,"am task resize "+activity.getTaskId()+" 10 220 "+(10+width)+" "+(220+height));Thread.sleep(700);
      click("开启摄像头");awaitCamera();Thread.sleep(300);
      mainChecked(()->{for(String label:new String[]{"关闭摄像头","切换前后摄像头","结束实时通话","点击头像，打断播报并说话"}){View control=find(activity.getWindow().getDecorView(),label);android.graphics.Rect visible=new android.graphics.Rect();check(control!=null&&control.getWidth()>=Ui.dp(activity,48)&&control.getHeight()>=Ui.dp(activity,48)&&control.getGlobalVisibleRect(visible)&&visible.width()==control.getWidth()&&visible.height()==control.getHeight(),"Small-window control clipped: "+label);}});
      EmulatorChecks.screenshot(this,"video-small-window.png");report.putBoolean("239x282VideoControls",true);
      oldAutoRotation=android.provider.Settings.System.getInt(getTargetContext().getContentResolver(),"accelerometer_rotation",1);oldRotation=android.provider.Settings.System.getInt(getTargetContext().getContentResolver(),"user_rotation",0);rotationChanged=true;
      getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(UiAutomation.ROTATION_FREEZE_90);Thread.sleep(700);mainChecked(camera::transform);EmulatorChecks.screenshot(this,"video-rotated.png");CameraFrame rotated=capture();check(rotated.jpeg.length<=CameraFrame.MAX_BYTES,"Rotated capture invalid");report.putBoolean("rotationKeepsCapture",true);
      report.putString("scope","No microphone capture, outbound calls, SMS or contacts access");
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"video-failure.png");}catch(Exception ignored){}}
    finally{mainChecked(()->{if(camera!=null)camera.disable();if(runtime!=null)runtime.unlisten(this);if(activity!=null)activity.finish();});if(rotationChanged){getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(oldRotation);if(oldAutoRotation==1)getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(UiAutomation.ROTATION_UNFREEZE);}if(savedFreeform)try{EmulatorChecks.shell(this,oldFreeform.equals("null")?"settings delete global enable_freeform_support":"settings put global enable_freeform_support "+oldFreeform);}catch(Exception ignored){}}
    finish(error.isEmpty()?-1:0,report);
  }
  private void mainChecked(Runnable action){Throwable[] failure={null};super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new IllegalStateException(failure[0]);}
  private void click(String label){mainChecked(()->{View view=find(activity.getWindow().getDecorView(),label);check(view!=null&&view.performClick(),"Missing control: "+label);});}
  private void awaitCamera()throws Exception{for(int i=0;i<150;i++){boolean[] ready={false};mainChecked(()->ready[0]=camera.ready());if(ready[0])return;Thread.sleep(100);}throw new IllegalStateException("Camera not ready: "+camera.state());}
  private CameraFrame capture()throws Exception{CountDownLatch latch=new CountDownLatch(1);CameraFrame[] result={null};String[] error={null};mainChecked(()->camera.capture(UUID.randomUUID().toString(),(frame,problem)->{result[0]=frame;error[0]=problem;latch.countDown();}));check(latch.await(12,TimeUnit.SECONDS)&&result[0]!=null,"Capture: "+error[0]);return result[0];}
  private void send(String text)throws Exception{turn=new CountDownLatch(1);runtimeError=null;mainChecked(()->PromptDispatcher.get(getTargetContext()).submit(PiRuntime.json("type","prompt","id",UUID.randomUUID().toString(),"text",text),true,(ok,queued,problem)->{if(!ok)runtimeError=problem;}));check(turn.await(20,TimeUnit.SECONDS),"Prompt did not complete: "+runtimeError);}
  private void verifyHash()throws Exception{StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(android.util.Base64.decode(runtime.lastPromptImage,android.util.Base64.DEFAULT)))hash.append(String.format("%02x",b));check(runtime.lastResponse.contains(hash),"Model received different JPEG bytes");}
  private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())&&view.isShown())return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
