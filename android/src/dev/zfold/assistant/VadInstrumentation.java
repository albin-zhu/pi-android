package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import com.k2fsa.sherpa.onnx.*;
import java.util.concurrent.*;

/** Checks that the bundled Silero model and arm64 runtime execute on Android. */
public final class VadInstrumentation extends Instrumentation {
  Bundle options;
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);options=arguments;start();}
  @Override public void onStart(){
    Bundle result=new Bundle();Vad vad=null;int code=0;
    try{
      SileroVadModelConfig silero=new SileroVadModelConfig("models/silero_vad.int8.onnx",0.5f,0.35f,0.20f,512,10f);
      VadModelConfig config=new VadModelConfig(silero,new TenVadModelConfig(),16000,1,"cpu",false);
      vad=new Vad(getTargetContext().getAssets(),config);
      float[] silence=new float[512];float probability=0;
      for(int i=0;i<8;i++)probability=vad.compute(silence);
      if(!Float.isFinite(probability)||probability<0||probability>1)throw new AssertionError("VAD probability invalid: "+probability);
      result.putString("silenceProbability",String.valueOf(probability));
      result.putBoolean("aecAvailable",android.media.audiofx.AcousticEchoCanceler.isAvailable());
      result.putString("model","Silero int8 loaded; 512-sample inference successful");code=-1;
      if("true".equals(options.getString("mic"))){
        if(getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new AssertionError("microphone permission missing");
        CountDownLatch ready=new CountDownLatch(1);String[] status={"not started"};
        VoiceVadMonitor monitor=new VoiceVadMonitor(getTargetContext(),new VoiceVadMonitor.Callback(){
          public void started(boolean echo){status[0]="recording; AEC="+echo;ready.countDown();}
          public void speech(){status[0]="speech detected; AEC active";ready.countDown();}
          public void failed(String reason){status[0]="failed: "+reason;ready.countDown();}
        });
        runOnMainSync(monitor::start);boolean signaled=ready.await(5,TimeUnit.SECONDS);if(signaled)Thread.sleep(1200);runOnMainSync(monitor::stop);
        if(!signaled||status[0].startsWith("failed"))throw new AssertionError("VAD microphone: "+status[0]);
        result.putString("microphone",status[0]);
      }
    }catch(Throwable error){result.putString("error",error.toString());}
    finally{if(vad!=null)vad.release();}
    finish(code,result);
  }
}
