package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.View;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Emulator external target + real Pi image handoff, including stale-image failure checks. */
public final class ScreenShareInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private PiRuntime runtime;private MainActivity activity;private AssistantPanel panel;private String runtimeError;
  private final CountDownLatch configured=new CountDownLatch(1),empty=new CountDownLatch(1);private CountDownLatch turn;
  @Override public void onCreate(Bundle arguments){PiRuntime.testMode=true;start();}
  public void event(JSONObject event){String type=event.optString("type");if(type.equals("configured")&&event.optString("model").equals("screen-context-test"))configured.countDown();if(type.equals("history")&&event.optJSONArray("items")!=null&&event.optJSONArray("items").length()==0)empty.countDown();if(type.equals("turn_end")&&turn!=null)turn.countDown();if(type.equals("error"))runtimeError=event.optString("message");}
  @Override public void onStart(){Bundle report=new Bundle();String error="";
    try{
      require(android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"),"This fixture is emulator-only");
      UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      // Instrumentation restarts this process; Android marks its prior service binding crashed.
      automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
      try{
        String component="dev.zfold.assistant/dev.zfold.assistant.PhoneAccessibility";
        String enabled=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),"enabled_accessibility_services");
        String other=enabled==null?"":enabled.replace(component,"").replaceAll("^:|:$","").replace("::",":");
        android.provider.Settings.Secure.putString(getTargetContext().getContentResolver(),"enabled_accessibility_services",other);Thread.sleep(500);
        android.provider.Settings.Secure.putString(getTargetContext().getContentResolver(),"enabled_accessibility_services",other.isEmpty()?component:other+":"+component);
      }finally{automation.dropShellPermissionIdentity();}
      for(int i=0;i<150&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);require(PhoneAccessibility.instance!=null,"Accessibility service not connected");
      runOnMainSync(()->{runtime=PiRuntime.get(getTargetContext());runtime.listen(this);});
      for(int i=0;i<300&&!runtime.ready;i++)Thread.sleep(100);require(runtime.ready&&runtime.isTestRuntime(),"Isolated runtime not ready");
      runOnMainSync(()->runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl","http://127.0.0.1:18875/v1","model","screen-context-test","apiKey","fixture","api","openai-completions"))));
      require(configured.await(30,TimeUnit.SECONDS),"Model fixture not configured: "+runtimeError);
      runOnMainSync(()->runtime.send(PiRuntime.json("type","new_session")));require(empty.await(20,TimeUnit.SECONDS),"Fresh session not ready");
      openPage("red");
      getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      for(int i=0;i<200;i++){runOnMainSync(()->{MainActivity current=MainActivity.current();if(current!=null&&current.input!=null&&current.input.isLaidOut())activity=current;});if(activity!=null)break;Thread.sleep(100);}
      require(activity!=null,"Main UI did not become ready");
      runOnMainSync(()->{activity.input.setText("保留输入草稿");activity.screenShareButton.performClick();});
      for(int i=0;i<100&&AssistantOverlay.currentPanel()==null;i++)Thread.sleep(100);runOnMainSync(()->panel=AssistantOverlay.currentPanel());require(panel!=null,"Main did not enter floating mode");
      require(ScreenContext.enabled(),"Screen state not shared");require(panel.input.getText().toString().equals("保留输入草稿"),"Draft lost during handoff");
      for(int i=0;i<100&&!PhoneAccessibility.instance.screenApplicationPackage().equals("dev.zfold.fixture.screen");i++)Thread.sleep(100);
      require(PhoneAccessibility.instance.screenApplicationPackage().equals("dev.zfold.fixture.screen"),"Main stayed in front of external app");report.putBoolean("mainToExternalOverlay",true);
      runOnMainSync(()->{AssistantOverlay.minimizeForPhoneControl(getTargetContext());AssistantOverlay.show(getTargetContext());panel=AssistantOverlay.currentPanel();});
      require(panel.screenSharing&&panel.input.getText().toString().equals("保留输入草稿"),"Collapse did not preserve mode and draft");report.putBoolean("collapseRestore",true);
      send("Read page A");String red=runtime.lastPromptImage;assertColor(red,0xffd64545);assertReceived(red);report.putBoolean("externalRedImage",true);
      openPage("blue");send("Read page B");String blue=runtime.lastPromptImage;assertColor(blue,0xff245dd6);require(!blue.equals(red),"Stale picture reused");assertReceived(blue);report.putBoolean("freshBlueImage",true);
      runOnMainSync(()->{});Thread.sleep(600);screenshot("screen-sharing-external.png");
      openPage("secure");String previous=runtime.lastPromptText;
      runOnMainSync(()->{panel.attachImage(red,"屏幕 · old fixture");panel.input.setText("Do not send old image");panel.send(panel.input.getText().toString());});awaitCapture();runOnMainSync(()->{});
      require(panel.image==null,"Protected screen retained stale image");require(previous.equals(runtime.lastPromptText),"Failed capture dispatched a prompt");require(panel.input.getText().toString().equals("Do not send old image"),"Failed capture discarded input");require(panel.getVisibility()==View.VISIBLE,"Overlay not restored after error");report.putBoolean("failureKeepsDraftWithoutOldImage",true);
      openPage("blue");runOnMainSync(panel::toggleScreenShare);require(!ScreenContext.enabled(),"Screen mode did not close");send("Text without screen");require(runtime.lastResponse.contains("NO_IMAGE"),"Disabled mode still sent an image");report.putBoolean("offSendsNoImage",true);
      runOnMainSync(()->{panel.host.close();});require(!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Close did not close the entry");report.putBoolean("closeDistinctFromCollapse",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);}
    finally{runOnMainSync(()->{ScreenContext.setEnabled(false);AssistantOverlay.hide();CircleOverlay.hideBubble();if(runtime!=null)runtime.unlisten(this);if(activity!=null)activity.finish();});}
    finish(error.isEmpty()?-1:0,report);
  }
  private void openPage(String page)throws Exception{getTargetContext().startActivity(new Intent().setComponent(new ComponentName("dev.zfold.fixture.screen","dev.zfold.fixture.screen.Target")).putExtra("page",page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(800);}
  private void send(String text)throws Exception{turn=new CountDownLatch(1);runOnMainSync(()->panel.send(text));require(turn.await(30,TimeUnit.SECONDS),"No model turn: "+runtimeError+" / "+panel.state.getText());}
  private void awaitCapture()throws Exception{for(int i=0;i<150;i++){if(!ScreenContext.busy()&&!panel.capturingScreen)return;Thread.sleep(100);}throw new IllegalStateException("Capture did not finish");}
  private void assertReceived(String data)throws Exception{byte[] bytes=android.util.Base64.decode(data,android.util.Base64.DEFAULT);StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b));require(runtime.lastResponse.contains("SCREEN_HASH:"+hash),"Real Pi model received a different image");}
  private void assertColor(String data,int expected){require(data!=null,"Image absent");byte[] bytes=android.util.Base64.decode(data,android.util.Base64.DEFAULT);Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);require(image!=null,"Invalid PNG");int pixel=image.getPixel(image.getWidth()/8,image.getHeight()*3/4);image.recycle();require(pixel==expected,"Captured own overlay or wrong page: "+Integer.toHexString(pixel));}
  private void screenshot(String name)throws Exception{Bitmap image=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
  private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
