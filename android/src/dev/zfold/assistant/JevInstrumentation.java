package dev.zfold.assistant;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Opt-in live Jev acceptance on our own local fixture; no external app state is changed. */
public final class JevInstrumentation extends Instrumentation {
  PiRuntime runtime;Activity fixture;String error,taskResult;int updates;boolean verified,controlsVerified;
  final CountDownLatch ready=new CountDownLatch(1),fresh=new CountDownLatch(1),done=new CountDownLatch(1);
  @Override public void onCreate(Bundle args){start();}
  void await(CountDownLatch latch)throws Exception{if(!latch.await(100,TimeUnit.SECONDS))throw new Exception("Timed out");if(error!=null)throw new Exception(error);}
  @Override public void onStart(){
    try{
      runOnMainSync(()->{
        PiRuntime.testMode=true;runtime=PiRuntime.get(getTargetContext());
        runtime.listen(e->{String type=e.optString("type");
          if(type.equals("ready"))runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl","http://127.0.0.1:18873/v1","model","jev-planner-fixture","apiKey","fixture")));
          if(type.equals("configured"))ready.countDown();
          if(type.equals("session_changed"))fresh.countDown();
          if(type.equals("tool_execution")&&e.optString("name").equals("phone_task")){updates++;if(!e.optString("status").equals("running")){taskResult=e.optString("output");verified=e.optBoolean("verified");}}
          if(type.equals("turn_end"))done.countDown();
          if(type.equals("error")){error=e.optString("message");ready.countDown();fresh.countDown();done.countDown();}
        });
      });
      await(ready);
      if(!runtime.jevEnabled||!runtime.jevConfigured)throw new Exception("Test Jev key was not provisioned");
      for(int i=0;i<100&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
      if(PhoneAccessibility.instance==null)throw new Exception("Accessibility service not connected");
      fixture=startActivitySync(new Intent(getTargetContext(),PhoneTaskFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      runOnMainSync(()->runtime.send(PiRuntime.json("type","new_session")));await(fresh);
      runOnMainSync(()->runtime.send(PiRuntime.json("type","prompt","id","jev-acceptance","text","在测试页开启演示开关并把备注填为你好，Jev。")));await(done);
      runOnMainSync(()->{android.widget.CheckBox toggle=fixture.findViewById(R.id.jev_fixture_toggle);android.widget.EditText input=fixture.findViewById(R.id.jev_fixture_input);controlsVerified=toggle.isChecked()&&input.getText().toString().equals("你好，Jev");});
    }catch(Exception e){error=e.getMessage();}
    Bundle outcome=new Bundle();outcome.putString("error",error);outcome.putString("taskResult",taskResult);outcome.putInt("updates",updates);outcome.putBoolean("verified",verified);outcome.putBoolean("controlsVerified",controlsVerified);
    runOnMainSync(()->{if(fixture!=null)fixture.finish();});finish(error==null&&verified&&controlsVerified?-1:0,outcome);
  }
}
