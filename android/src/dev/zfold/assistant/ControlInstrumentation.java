package dev.zfold.assistant;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import java.util.concurrent.*;

/** ADB-only end-to-end check against a harmless Android Settings page. */
public final class ControlInstrumentation extends Instrumentation {
  private Bundle options;private String error,result="";private boolean skillLoaded;
  private final java.util.Set<String> calls=new java.util.HashSet<>();
  private final CountDownLatch done=new CountDownLatch(1);
  @Override public void onCreate(Bundle arguments){options=arguments;start();}
  @Override public void onStart(){
    runOnMainSync(()->{
      PiRuntime.testMode=true;PiRuntime runtime=PiRuntime.get(getTargetContext());
      runtime.listen(event->{
        try{
          switch(event.optString("type")){
            case "ready":runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl",options.getString("endpoint","http://127.0.0.1:18869/v1"),"model","zfold-device-test","apiKey","fixture")));break;
            case "configured":{
              JSONArray skills=event.optJSONArray("skills");skillLoaded=skills!=null&&skills.toString().contains("phone-control");
              new Thread(()->{
                for(int attempt=0;attempt<150&&PhoneAccessibility.instance==null;attempt++)try{Thread.sleep(200);}catch(InterruptedException ignored){}
                if(PhoneAccessibility.instance==null){error="Accessibility service did not bind";done.countDown();return;}
                runtime.send(PiRuntime.json("type","prompt","id","android-control","text","按照手机操作说明，查看当前系统设置页，打开字体设置或返回，再确认屏幕。"));
              },"wait-accessibility").start();break;
            }
            case "tool_request":calls.add(event.optString("name"));break;
            case "delta":result+=event.optString("text");break;
            case "turn_end":done.countDown();break;
            case "error":error=event.optString("message");done.countDown();break;
          }
        }catch(Exception e){error=e.getMessage();done.countDown();}
      });
    });
    try{if(!done.await(65,TimeUnit.SECONDS))error="Timed out";}catch(Exception e){error=e.getMessage();}
    boolean action=calls.contains("ui_tap")||calls.contains("ui_back");
    Bundle outcome=new Bundle();outcome.putString("result",result);outcome.putString("calls",calls.toString());outcome.putBoolean("skillLoaded",skillLoaded);outcome.putString("error",error);
    finish(error==null&&skillLoaded&&calls.contains("screen_read")&&action&&result.contains("已完成界面操作")?-1:0,outcome);
  }
}
