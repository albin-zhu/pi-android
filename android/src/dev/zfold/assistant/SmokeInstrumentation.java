package dev.zfold.assistant;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.concurrent.*;
/** ADB instrumentation only. No exported command receiver or network control port. */
public final class SmokeInstrumentation extends Instrumentation {
  Bundle options;String error,result="";boolean toolSeen;final CountDownLatch done=new CountDownLatch(1);
  @Override public void onCreate(Bundle arguments){options=arguments;start();}
  @Override public void onStart(){
    runOnMainSync(()->{
      PiRuntime.testMode=true;PiRuntime runtime=PiRuntime.get(getTargetContext());
      PiRuntime.Listener listener=event->{
        try {
          switch(event.optString("type")){
            case "ready":runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl",options.getString("endpoint","http://127.0.0.1:18868/v1"),"model","zfold-device-test","apiKey","fixture")));break;
            case "configured":runtime.send(PiRuntime.json("type","new_session"));break;
            case "session_changed":runtime.send(PiRuntime.json("type","prompt","id","android-smoke","text","请使用工具读取手机型号、电量和 Android 版本。🙂"));break;
            case "tool_request":toolSeen=true;break;
            case "delta":result+=event.optString("text");break;
            case "turn_end":done.countDown();break;
            case "error":error=event.optString("message");done.countDown();break;
          }
        }catch(Exception e){error=e.getMessage();done.countDown();}
      };runtime.listen(listener);
      if(runtime.ready&&!runtime.configured)runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl",options.getString("endpoint","http://127.0.0.1:18868/v1"),"model","zfold-device-test","apiKey","fixture")));
    });
    try{if(!done.await(45,TimeUnit.SECONDS))error="Timed out";}catch(Exception e){error=e.getMessage();}
    Bundle outcome=new Bundle();outcome.putString("result",result);outcome.putBoolean("nativeToolCalled",toolSeen);outcome.putString("error",error);
    finish(error==null&&toolSeen&&result.contains(android.os.Build.MODEL)?-1:0,outcome);
  }
}
