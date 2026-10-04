package dev.zfold.assistant;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises real Pi events on both native surfaces with an isolated model fixture. */
public final class ToolUiInstrumentation extends Instrumentation {
  PiRuntime runtime;MainActivity activity;AssistantPanel panel;String error;
  boolean running,completed,failed,reopened,history,cleared;
  final CountDownLatch ready=new CountDownLatch(1),prepared=new CountDownLatch(1),done=new CountDownLatch(1),restored=new CountDownLatch(1),empty=new CountDownLatch(1);
  @Override public void onCreate(Bundle args){start();}
  void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
  void await(CountDownLatch latch)throws Exception{if(!latch.await(30,TimeUnit.SECONDS))throw new IllegalStateException("Timed out");if(error!=null)throw new IllegalStateException(error);}
  AssistantPanel freshPanel(){return new AssistantPanel(activity,new AssistantPanel.Host(){public void close(){}public void minimize(){}public void select(){}public void toggleExpansion(){}});}
  @Override public void onStart(){
    try{
      runOnMainSync(()->{
        PiRuntime.testMode=true;runtime=PiRuntime.get(getTargetContext());
        runtime.listen(e->{
          if("ready".equals(e.optString("type")))runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl","http://127.0.0.1:18872/v1","model","tool-ui-test","apiKey","fixture")));
          if("configured".equals(e.optString("type")))ready.countDown();
          if("error".equals(e.optString("type"))){error=e.optString("message");ready.countDown();done.countDown();restored.countDown();empty.countDown();}
        });
      });
      await(ready);
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      runOnMainSync(()->{
        panel=freshPanel();
        runtime.listen(e->{try{
          String type=e.optString("type");
          if(type.equals("tool_execution")){
            String id=e.optString("id"),state=e.optString("status");
            require(activity.toolViews.containsKey(id)&&panel.toolViews.containsKey(id),"Tool missing from a surface");
            if(state.equals("running"))running=true;
            if(state.equals("success")){completed=true;require(activity.toolViews.get(id).details.getText().toString().contains(android.os.Build.MODEL),"Native result missing");}
            if(state.equals("error"))failed=true;
          }
          if(type.equals("turn_end"))done.countDown();
          if(type.equals("history")){
            if(!completed){prepared.countDown();}
            else new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
              if(e.optJSONArray("items").length()==0){cleared=activity.toolViews.isEmpty()&&panel.toolViews.isEmpty();empty.countDown();}
              else{history=activity.toolViews.size()==2&&panel.toolViews.size()==2;restored.countDown();}
            });
          }
        }catch(Exception ex){error=ex.toString();done.countDown();restored.countDown();empty.countDown();}});
        runtime.send(PiRuntime.json("type","new_session"));
      });
      await(prepared);waitForIdleSync();
      runOnMainSync(()->activity.sendText("查看手机状态，并展示工具执行记录。"));await(done);waitForIdleSync();
      runOnMainSync(()->{
        panel.dispose();panel=freshPanel();reopened=panel.toolViews.size()==2;
        activity.toolViews.get("info-ok").toggle();runtime.send(PiRuntime.json("type","history"));
      });await(restored);waitForIdleSync();
      screenshot("tool-ui-main.png");
      runOnMainSync(()->{activity.toolViews.get("read-failed").toggle();});waitForIdleSync();screenshot("tool-ui-details.png");
      runOnMainSync(()->{AssistantOverlay.show(getTargetContext());});waitForIdleSync();Thread.sleep(350);screenshot("tool-ui-panel.png");
      runOnMainSync(()->{AssistantOverlay.hide();runtime.send(PiRuntime.json("type","new_session"));});await(empty);
    }catch(Exception ex){error=ex.toString();}
    runOnMainSync(()->{if(panel!=null)panel.dispose();if(activity!=null)activity.finish();});
    Bundle result=new Bundle();result.putString("error",error);result.putBoolean("running",running);result.putBoolean("completed",completed);result.putBoolean("failed",failed);result.putBoolean("reopened",reopened);result.putBoolean("history",history);result.putBoolean("cleared",cleared);
    finish(error==null&&running&&completed&&failed&&reopened&&history&&cleared?-1:0,result);
  }
  void screenshot(String name)throws Exception{
    Bitmap bitmap=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
    if(bitmap==null)throw new IllegalStateException("Screenshot unavailable");
    File target=new File(getTargetContext().getCacheDir(),name);
    try(FileOutputStream out=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
  }
}
