package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the same main-thread tool entry point used by PiRuntime. */
public final class RuntimeResponsivenessInstrumentation extends Instrumentation {
  private final Handler main=new Handler(Looper.getMainLooper());
  private Bundle options;
  private volatile boolean watching,commandRunning;
  private volatile long lastBeat,maxMainDelay;
  private final AtomicInteger beatsDuringCommand=new AtomicInteger();
  private final Runnable heartbeat=new Runnable(){
    public void run(){
      if(!watching)return;
      long now=SystemClock.uptimeMillis();
      maxMainDelay=Math.max(maxMainDelay,now-lastBeat);lastBeat=now;
      if(commandRunning)beatsDuringCommand.incrementAndGet();
      main.postDelayed(this,50);
    }
  };

  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    Bundle outcome=new Bundle();String failure="";File script=null,directory=null;
    try{
      directory=new File(getTargetContext().getFilesDir(),"runtime/tools/responsiveness-"+SystemClock.uptimeMillis());
      if(!directory.mkdirs())throw new IllegalStateException("Cannot create test directory");
      script=new File(directory,"probe.sh");
      // Pin fixture utilities to Android: the user may have a Termux printf on PATH.
      Files.write(script.toPath(),("#!/system/bin/sh\n/system/bin/sleep \"$1\"\n/system/bin/toybox printf 'RUNTIME_RESPONSIVE'\n/system/bin/toybox printf 'STDERR_OK' >&2\nexit 7\n").getBytes(StandardCharsets.UTF_8));
      if(!script.setExecutable(true))throw new IllegalStateException("Cannot make probe executable");
      int seconds=Math.max(2,Math.min(20,Integer.parseInt(options.getString("seconds","12"))));
      watching=true;lastBeat=SystemClock.uptimeMillis();main.post(heartbeat);
      commandRunning=true;
      long started=SystemClock.uptimeMillis();
      Reply reply=call(request(script,seconds,30000));
      commandRunning=false;
      outcome.putLong("commandElapsedMs",SystemClock.uptimeMillis()-started);
      outcome.putInt("heartbeatsDuringCommand",beatsDuringCommand.get());
      outcome.putLong("maxMainDelayMs",maxMainDelay);
      outcome.putLong("dispatchReturnMs",reply.dispatchReturnMs);
      if(reply.value!=null){outcome.putString("stdout",reply.value.optString("stdout"));outcome.putString("stderr",reply.value.optString("stderr"));}
      check(reply.dispatchReturnMs<750&&maxMainDelay<750,"Main thread blocked during runtime_exec");
      check(beatsDuringCommand.get()>=seconds*5,"Main thread did not keep responding during command");
      check(reply.error==null,"Command failed: "+reply.error);
      check(reply.value!=null&&reply.value.optInt("exitCode")==7,"Lost nonzero exit status");
      check("RUNTIME_RESPONSIVE".equals(reply.value.optString("stdout")),"Lost stdout");
      check("STDERR_OK".equals(reply.value.optString("stderr")),"Lost stderr");

      Reply timeout=call(request(script,4,1000));
      check(timeout.error!=null&&timeout.error.contains("超时"),"Timeout did not return an error");
      outcome.putBoolean("timeoutPassed",true);
      Reply denied=call(request(new File("/system/bin/sh"),2,1000));
      check(denied.error!=null&&denied.error.contains("只能执行"),"Executable boundary was bypassed");
      outcome.putBoolean("pathGuardPassed",true);
      outcome.putLong("maxMainDelayMs",maxMainDelay);
      check(maxMainDelay<750,"Main thread blocked while handling timeout or invalid path");
    }catch(Throwable error){failure=String.valueOf(error);outcome.putString("error",failure);}
    finally{
      watching=false;main.removeCallbacks(heartbeat);
      if(script!=null)script.delete();if(directory!=null)directory.delete();
    }
    finish(failure.isEmpty()?-1:0,outcome);
  }

  private JSONObject request(File script,int seconds,int timeout){
    return PiRuntime.json("name","runtime_exec","args",PiRuntime.json("argv",new JSONArray().put(script.getAbsolutePath()).put(String.valueOf(seconds)),"timeoutMs",timeout));
  }

  private Reply call(JSONObject request)throws Exception{
    Reply reply=new Reply();CountDownLatch done=new CountDownLatch(1),returned=new CountDownLatch(1);
    AtomicInteger callbacks=new AtomicInteger();long sent=SystemClock.uptimeMillis();
    main.post(()->{
      AndroidTools.execute(getTargetContext(),request,(value,error)->{
        reply.value=value;reply.error=error;callbacks.incrementAndGet();done.countDown();
      });
      reply.dispatchReturnMs=SystemClock.uptimeMillis()-sent;returned.countDown();
    });
    check(done.await(35,TimeUnit.SECONDS),"Tool result timed out");
    check(returned.await(2,TimeUnit.SECONDS),"Tool dispatch did not return");
    // Drain a queued main-loop marker before evaluating heartbeat latency.
    CountDownLatch marker=new CountDownLatch(1);main.post(marker::countDown);
    check(marker.await(2,TimeUnit.SECONDS),"Main thread did not process marker");
    check(callbacks.get()==1,"Tool completed more than once");
    return reply;
  }

  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
  private static final class Reply {JSONObject value;String error;long dispatchReturnMs;}
}
