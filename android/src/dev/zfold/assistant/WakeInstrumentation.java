package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

/** ADB-only smoke test: start the opt-in wake service from the application UID. */
public final class WakeInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){
    if((!android.os.Build.HARDWARE.contains("ranchu")&&!android.os.Build.HARDWARE.contains("goldfish"))||arguments==null||!"true".equals(arguments.getString("mic"))){Bundle result=new Bundle();result.putString("failure","Emulator only; explicit -e mic true is required for microphone diagnostics");finish(0,result);return;}
    PiRuntime.testMode=true;
    new Thread(()->{
      Bundle result=new Bundle();Context context=getTargetContext();
      try{
        boolean enable=!"false".equals(arguments.getString("enable","true"));
        VoiceWakeService.setEnabled(context,enable);Thread.sleep(enable?8000:2000);
        boolean active=VoiceWakeService.active();String failure=VoiceWakeService.lastFailure;
        result.putBoolean("requested",enable);result.putBoolean("active",active);result.putString("failure",failure);
        if(!enable)finish(!active?-1:0,result);else finish(active&&failure==null?-1:0,result);
      }catch(Throwable error){result.putString("failure",error.toString());finish(0,result);}
    },"zfold-wake-test").start();
  }
}
