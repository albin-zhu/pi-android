package dev.zfold.assistant;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.concurrent.*;

/** Opt-in real planner search test. Uses isolated history and never requests a send. */
public final class WeChatSearchInstrumentation extends Instrumentation {
  PiRuntime runtime;Bundle options;String error,answer="";int calls,images,inputs;long started;
  final CountDownLatch ready=new CountDownLatch(1),fresh=new CountDownLatch(1),done=new CountDownLatch(1);
  @Override public void onCreate(Bundle args){options=args;start();}
  void await(CountDownLatch latch)throws Exception{if(!latch.await(105,TimeUnit.SECONDS))throw new Exception("Timed out");if(error!=null)throw new Exception(error);}
  @Override public void onStart(){
    try{
      String query=options.getString("query","");if(query.isEmpty()||query.length()>80)throw new Exception("Supply a bounded contact query");
      JSONObject model=new JSONObject(Files.readString(new File(getTargetContext().getNoBackupFilesDir(),"model.json").toPath(),StandardCharsets.UTF_8));
      runOnMainSync(()->{
        PiRuntime.testMode=true;runtime=PiRuntime.get(getTargetContext());runtime.listen(e->{String type=e.optString("type");
          if(type.equals("ready"))runtime.send(PiRuntime.json("type","configure","config",model));
          if(type.equals("configured"))ready.countDown();
          if(type.equals("session_changed"))fresh.countDown();
          if(type.equals("tool_request")){calls++;if(e.optString("name").equals("screen_capture"))images++;if(e.optString("name").equals("ui_input"))inputs++;}
          if(type.equals("delta"))answer+=e.optString("text");
          if(type.equals("turn_end"))done.countDown();
          if(type.equals("error")){error=e.optString("message");ready.countDown();fresh.countDown();done.countDown();}
        });
      });await(ready);
      for(int i=0;i<100&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
      if(PhoneAccessibility.instance==null)throw new Exception("Accessibility not connected");
      runOnMainSync(()->runtime.send(PiRuntime.json("type","new_session")));await(fresh);started=System.currentTimeMillis();
      runOnMainSync(()->runtime.send(PiRuntime.json("type","prompt","id","wechat-search-check","text","在微信中搜索联系人「"+query+"」。看到搜索结果就停止，只报告是否存在唯一匹配联系人。不要进入聊天，不要发送任何消息或朋友圈。")));await(done);
    }catch(Exception e){error=e.getMessage();}
    Bundle out=new Bundle();out.putString("error",error);out.putString("answer",answer);out.putInt("nativeCalls",calls);out.putInt("screenshots",images);out.putInt("inputs",inputs);out.putLong("elapsedMs",started==0?0:System.currentTimeMillis()-started);finish(error==null&&inputs>0?-1:0,out);
  }
}
