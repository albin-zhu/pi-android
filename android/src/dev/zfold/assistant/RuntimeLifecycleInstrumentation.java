package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real child cancellation plus an actual Pi round trip lasting over 30 seconds. */
public final class RuntimeLifecycleInstrumentation extends Instrumentation {
  private final Handler main=new Handler(Looper.getMainLooper());
  private Bundle options;
  private File directory,script;
  private PiRuntime runtime;
  private final CountDownLatch ready=new CountDownLatch(1);
  private volatile CountDownLatch turn;
  private volatile String answer="",failure;
  private volatile boolean cancelOnStart,aborted;
  private final PiRuntime.Listener listener=event->{
    String type=event.optString("type");
    if(type.equals("ready"))runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json(
      "baseUrl",options.getString("endpoint","http://127.0.0.1:18872/v1"),"model","runtime-fixture","apiKey","fixture","api","openai-completions")));
    if(type.equals("configured"))ready.countDown();
    if(type.equals("delta"))answer+=event.optString("text");
    if(type.equals("tool_request")&&event.optString("name").equals("runtime_exec")&&cancelOnStart){
      cancelOnStart=false;main.postDelayed(()->runtime.send(PiRuntime.json("type","stop")),500);
    }
    if(type.equals("turn_end")){aborted=event.optBoolean("aborted");if(turn!=null)turn.countDown();}
    if(type.equals("error")){failure=event.optString("message");ready.countDown();if(turn!=null)turn.countDown();}
  };
  @Override public void onCreate(Bundle arguments){options=arguments;start();}
  @Override public void onStart(){
    Bundle outcome=new Bundle();String error="";
    try {
      directory=new File(getTargetContext().getFilesDir(),"runtime/tools/lifecycle-"+SystemClock.uptimeMillis());
      check(directory.mkdirs(),"Cannot create fixture directory");script=new File(directory,"probe.sh");
      Files.writeString(script.toPath(),"#!/system/bin/sh\ncase \"$1\" in\nlong) /system/bin/sleep 35; /system/bin/echo RUNTIME_LONG_OK;;\ntree) (/system/bin/sleep 2; /system/bin/echo SURVIVED > \"$2\") & wait;;\nflood) /system/bin/head -c 524288 /dev/zero; /system/bin/head -c 524288 /dev/zero >&2;;\nstdin) /system/bin/cat; /system/bin/echo EOF_OK;;\ndirect) /system/bin/echo RAN > \"$2\";;\npty) /system/bin/echo RUNTIME_PTY_OK; /system/bin/tty;;\nesac\n",StandardCharsets.UTF_8);
      check(script.setExecutable(true),"Fixture cannot execute");
      File marker=new File(directory,"cancelled-child");
      Reply cancelled=call("cancel-active",args("tree",marker.getPath(),10000),500,false);
      check(cancelled.error!=null&&cancelled.error.contains("停止"),"Cancellation not acknowledged");
      Thread.sleep(2200);check(!marker.exists(),"Child survived cancellation");outcome.putBoolean("processGroupCancelled",true);

      marker=new File(directory,"queued-child");
      Reply queued=call("cancel-queued",args("direct",marker.getPath(),10000),-1,true);
      check(queued.error!=null&&!marker.exists(),"Cancelled queued command ran");outcome.putBoolean("queuedCancellation",true);
      marker=new File(directory,"timeout-child");
      Reply timeout=call("timeout-tree",args("tree",marker.getPath(),1000),-1,false);
      check(timeout.error!=null&&timeout.error.contains("超时"),"Missing timeout");
      Thread.sleep(2200);check(!marker.exists(),"Child survived timeout");outcome.putBoolean("timeoutKillsChildren",true);

      Reply flood=call("flood",args("flood","unused",10000),-1,false);
      check(flood.error==null&&flood.value.optInt("exitCode")==0,"Flood failed");
      check(flood.value.optBoolean("stdoutTruncated")&&flood.value.optBoolean("stderrTruncated"),"Output was not bounded");
      check(flood.value.toString().length()<2*1024*1024,"Result exceeds bridge frame limit");outcome.putBoolean("boundedOutput",true);
      Reply stdin=call("stdin",args("stdin","unused",3000),-1,false);
      check(stdin.error==null&&stdin.value.optString("stdout").contains("EOF_OK"),"Command stdin did not receive EOF");outcome.putBoolean("stdinClosed",true);

      runOnMainSync(()->{PiRuntime.testMode=true;runtime=PiRuntime.get(getTargetContext());runtime.listen(listener);});
      check(ready.await(20,TimeUnit.SECONDS)&&failure==null,"Pi configure failed: "+failure);
      long started=SystemClock.elapsedRealtime();run(args("long","unused",60000),false);
      check(answer.contains("LIFECYCLE_VERIFIED")&&!aborted,"Full Pi long command failed: "+answer);
      outcome.putLong("fullPiCommandMs",SystemClock.elapsedRealtime()-started);
      run(PiRuntime.json("tool","pty","argv",new JSONArray().put(script.getPath()).put("pty")),false);
      check(answer.contains("LIFECYCLE_VERIFIED"),"Full Pi PTY open/read/close failed: "+answer);
      outcome.putBoolean("fullPiPty",true);
      marker=new File(directory,"pi-cancelled-child");run(args("tree",marker.getPath(),10000),true);
      check(aborted,"Pi did not report cancellation");Thread.sleep(2200);check(!marker.exists(),"Pi stop left its child running");
      outcome.putBoolean("fullPiCancellation",true);
    }catch(Throwable e){error=e.toString();outcome.putString("error",error);}
    finally{
      if(runtime!=null)runtime.unlisten(listener);
      if(directory!=null){File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
    }
    finish(error.isEmpty()?-1:0,outcome);
  }
  private JSONObject args(String mode,String target,int timeout){return PiRuntime.json("argv",new JSONArray().put(script.getPath()).put(mode).put(target),"timeoutMs",timeout);}
  private void run(JSONObject args,boolean cancel)throws Exception{
    answer="";aborted=false;cancelOnStart=cancel;turn=new CountDownLatch(1);
    main.post(()->runtime.send(PiRuntime.json("type","prompt","id","lifecycle-"+SystemClock.uptimeMillis(),"text","RUNTIME_FIXTURE "+args)));
    check(turn.await(65,TimeUnit.SECONDS)&&failure==null,"Pi turn failed: "+failure);
  }
  private Reply call(String id,JSONObject args,int cancelAfter,boolean preCancel)throws Exception{
    Reply reply=new Reply();CountDownLatch done=new CountDownLatch(1);AtomicInteger count=new AtomicInteger();
    if(preCancel)NativeToolExecution.cancel(id);
    main.post(()->AndroidTools.execute(getTargetContext(),PiRuntime.json("id",id,"name","runtime_exec","args",args),(value,error)->{
      reply.value=value;reply.error=error;count.incrementAndGet();done.countDown();
    }));
    if(cancelAfter>=0)main.postDelayed(()->NativeToolExecution.cancel(id),cancelAfter);
    check(done.await(15,TimeUnit.SECONDS),"Native call timed out: "+id);check(count.get()==1,"Multiple terminal callbacks");return reply;
  }
  private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
  private static final class Reply{JSONObject value;String error;}
}
