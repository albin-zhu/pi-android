package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ADB-only check that a Termux runtime command really owns a controlling PTY. */
public final class PtyInstrumentation extends Instrumentation {
  private Bundle options;
  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    Bundle outcome=new Bundle();
    String error="";
    try {
      System.loadLibrary("zfold");
      if(options.getBoolean("install",true))PiPackageManager.install(getTargetContext(),"bash");
      String executable=PiPackageManager.executable(getTargetContext(),"bash").getCanonicalPath();
      outcome.putString("executable",executable);
      JSONObject opened=call(PiRuntime.json("name","pty","args",PiRuntime.json("action","open","argv",
        new org.json.JSONArray().put(executable).put("--noprofile").put("--norc"),"rows",30,"cols",100)));
      int id=opened.optInt("id");
      outcome.putString("open",String.valueOf(opened));
      if(id<=0)throw new IllegalStateException("PTY did not open");
      Thread.sleep(120);
      call(PiRuntime.json("name","pty","args",PiRuntime.json("action","write","id",id,
        "data","echo PTY_OK; tty; exit\n")));
      String output="";boolean seen=false;int status=-1;
      for(int i=0;i<20;i++) {
        JSONObject read=call(PiRuntime.json("name","pty","args",PiRuntime.json("action","read","id",id,"timeoutMs",250)));
        output+=read.optString("output");
        if(output.contains("PTY_OK")&&output.contains("/dev/pts/")){seen=true;break;}
      }
      JSONObject state=call(PiRuntime.json("name","pty","args",PiRuntime.json("action","status","id",id)));
      status=state.optBoolean("alive")?-1:state.optInt("exitCode",-2);
      outcome.putString("output",output);
      outcome.putInt("ptyId",id);outcome.putBoolean("ptySeen",seen);outcome.putInt("bashStatus",status);
      call(PiRuntime.json("name","pty","args",PiRuntime.json("action","close","id",id)));
      if(!seen)throw new IllegalStateException("PTY output did not prove controlling terminal");
    } catch(Throwable failure) {
      error=String.valueOf(failure);outcome.putString("error",error);
    }
    finish(error.isEmpty()?-1:0,outcome);
  }

  private JSONObject call(JSONObject request)throws Exception {
    CountDownLatch latch=new CountDownLatch(1);
    String[] failure=new String[1];JSONObject[] value=new JSONObject[1];
    AndroidTools.execute(getTargetContext(),request,(result,errorText)->{
      value[0]=result;failure[0]=errorText;latch.countDown();
    });
    if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("PTY call timed out");
    if(failure[0]!=null)throw new IllegalStateException(failure[0]);
    if(value[0]==null)throw new IllegalStateException("PTY returned no value");
    return value[0];
  }
}
