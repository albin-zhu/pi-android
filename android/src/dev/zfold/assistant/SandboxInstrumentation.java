package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real runtime check: engine metadata, private tools directory, and tool boundaries. */
public final class SandboxInstrumentation extends Instrumentation {
  private Bundle options;
  private String error,result="";
  private JSONObject status,bash;private String skills="",tools="";
  private final Set<String> calls=new LinkedHashSet<>();
  private final CountDownLatch done=new CountDownLatch(1);

  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    runOnMainSync(()->{
      PiRuntime.testMode=true;
      PiRuntime runtime=PiRuntime.get(getTargetContext());
      String endpoint="http://127.0.0.1:"+options.getString("port","18871")+"/v1";
      runtime.listen(event->{
        try{
          String type=event.optString("type");
          if(type.equals("ready")){
            runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json(
              "baseUrl",endpoint,"model","sandbox-device-test","apiKey","fixture","api","openai-completions")));
          }else if(type.equals("configured")){
            skills=event.optJSONArray("skills")==null?"":event.optJSONArray("skills").toString();
            runtime.send(PiRuntime.json("type","new_session"));
          }else if(type.equals("session_changed")){
            runtime.send(PiRuntime.json("type","prompt","id","sandbox-device-test","text","检查运行时。"));
          }else if(type.equals("tool_request")){
            calls.add(event.optString("name"));
          }else if(type.equals("tool_execution")&&event.optString("status").equals("success")){
            String name=event.optString("name"),output=event.optString("output");
            if(name.equals("sandbox_status"))status=parse(output);else if(name.equals("bash")){bash=parse(output);tools+=output;}
            calls.add(name);result+="\n"+name+":"+output;
          }else if(type.equals("delta")){
            result+="\nanswer:"+event.optString("text");
          }else if(type.equals("turn_end")){
            done.countDown();
          }else if(type.equals("error")){
            error=event.optString("message");done.countDown();
          }
        }catch(Throwable failure){error=String.valueOf(failure);done.countDown();}
      });
      if(runtime.ready&&!runtime.configured)runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json(
        "baseUrl",endpoint,"model","sandbox-device-test","apiKey","fixture","api","openai-completions")));
    });
    try{if(!done.await(60,TimeUnit.SECONDS))error="Timed out";}catch(Exception failure){error=String.valueOf(failure);}
    boolean statusOk=calls.contains("sandbox_status")&&status!=null&&status.optJSONObject("embeddedEngine") != null
      &&status.optJSONObject("binaryExecution").optJSONObject("toolsDirectory")!=null;
    boolean toolsOnPath=calls.contains("bash")&&tools.contains("/files/runtime/tools/cat");
    boolean oldToolsRemoved=!calls.contains("node")&&!calls.contains("npm")&&!calls.contains("npx");
    Bundle outcome=new Bundle();
    outcome.putString("result",result.length()>4000?result.substring(result.length()-4000):result);
    outcome.putString("calls",calls.toString());outcome.putString("error",error);
    outcome.putString("skills",skills);
    outcome.putBoolean("status",statusOk);outcome.putBoolean("oldToolsRemoved",oldToolsRemoved);
    outcome.putBoolean("toolsOnPath",toolsOnPath);
    finish(error==null&&statusOk&&oldToolsRemoved&&toolsOnPath?-1:0,outcome);
  }

  private static JSONObject parse(String value){
    try{return new JSONObject(value);}catch(Exception ignored){return null;}
  }
}
