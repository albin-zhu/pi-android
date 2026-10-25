package dev.zfold.assistant;

import android.app.*;
import android.content.Context;
import android.media.AudioManager;
import android.os.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.k2fsa.sherpa.onnx.*;

/** Runs the real KWS model on fixed WAVs. No microphone or user conversation is recorded. */
public class WakePipelineInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;VoiceReadyCue cue=null;KeywordSpotter model=null;
    try{
      String product=Build.PRODUCT;if(!product.contains("sdk")&&!product.contains("emulator"))throw new IllegalStateException("Emulator only");PiRuntime.testMode=true;
      Context context=getTargetContext();WakeConfig config=WakeConfig.get(context);
      check(config.word.equals("橙橙"),"Wrong wake name");
      try(java.io.InputStream input=context.getAssets().open("models/kws/keywords.txt")){String keywords=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();check(keywords.equals(config.tokens+" @"+config.word),"Detector and UI configuration disagree");}
      check(config.stripPrefix("橙橙，橙橙！打开设置").equals("打开设置"),"Repeated prefix not stripped");
      check(config.stripPrefix("程程，打开设置").equals("打开设置"),"Homophone prefix not stripped");
      check(config.stripPrefix("cheng cheng, 打开设置").equals("打开设置"),"Latin prefix not stripped");
      check(config.stripPrefix("我想问橙橙一个问题").equals("我想问橙橙一个问题"),"Internal name was rewritten");
      check(config.stripPrefix("小派，打开设置").equals("小派，打开设置"),"Old wake word retained as alias");
      check(config.stripPrefix("橙橙").isEmpty(),"Name-only produced a command");report.putBoolean("singleWakeConfigAndAnchoredPrefixes",true);

      WakeHandoff handoff=new WakeHandoff();ArrayList<byte[]> history=new ArrayList<>();history.add(new byte[]{1,2});long id=handoff.begin(history,100);ArrayList<WakeHandoff.Audio> received=new ArrayList<>();
      check(handoff.claim(id,101,received::add)&&received.isEmpty(),"Handoff delivered before producer release");handoff.append(id,new byte[]{3,4});handoff.finish(id);
      check(received.size()==1&&received.get(0).pcm.size()==2&&received.get(0).pcm.get(1)[0]==3,"Buffered continuation lost");
      check(!handoff.claim(id,102,received::add),"Wake payload consumed twice");handoff.finish(id);check(received.size()==1,"Repeated finish delivered twice");
      long next=handoff.begin(history,200);check(next>id&&!handoff.claim(next,200+WakeHandoff.TTL+1,received::add),"Stale payload accepted");handoff.cancel();handoff.finish(next);check(received.size()==1,"Cancelled handoff delivered");
      next=handoff.begin(history,300);handoff.append(next,new byte[16000*2*6]);handoff.claim(next,301,received::add);handoff.finish(next);check(received.get(1).lost&&received.get(1).pcm.isEmpty(),"Overflow emitted partial command audio");report.putBoolean("handoffWaitsForReleaseAndConsumesOnce",true);report.putBoolean("expiredCancelledAndOverflowAudioRejected",true);
      ArrayList<byte[]> rolling=new ArrayList<>();for(int i=0;i<128;i++)rolling.add(new byte[]{(byte)i});
      ArrayList<byte[]> prefix=WakeHandoff.keywordPrefix(rolling,65536,3.5f);check(prefix.size()<40&&prefix.get(0)[0]>80,"Wake prefix includes unrelated old audio");report.putBoolean("keywordTimestampTrimsPreWakeHistory",true);

      int[] count={0};VoiceReadyCue gate=new VoiceReadyCue(token->count[0]++);gate.arm(1);gate.microphoneReady();gate.backendReady();check(count[0]==0,"Tone before voice UI");gate.viewReady(true);check(count[0]==1,"Ready tone missing");
      gate.viewReady(false);gate.viewReady(true);gate.resetInput();gate.microphoneReady();gate.backendReady();check(count[0]==1,"Tone replayed on resume/retry");
      gate.arm(2);gate.backendReady();gate.viewReady(true);check(count[0]==1,"Tone before mic ready");gate.cancel();gate.microphoneReady();check(count[0]==1,"Cancelled connection beeped");
      gate.arm(3);gate.microphoneReady();gate.viewReady(true);check(count[0]==1,"Tone before backend ready");gate.backendReady();check(count[0]==2,"New wake failed to beep");report.putBoolean("toneRequiresUiMicBackendAndIsOncePerWake",true);

      AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int volume=audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL),mode=audio.getMode(),played=VoiceReadyCue.completedPlays;
      cue=new VoiceReadyCue();cue.arm(10001);cue.microphoneReady();cue.backendReady();cue.viewReady(true);
      for(int i=0;i<150&&VoiceReadyCue.completedPlays==played;i++)Thread.sleep(20);
      check(VoiceReadyCue.completedPlays==played+1,"AudioTrack did not complete the ready tone");check(volume==audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)&&mode==audio.getMode(),"Tone changed volume or audio mode");report.putBoolean("actualTonePlaybackPreservesAudioSettings",true);

      model=config.createSpotter(context);
      for(String name:new String[]{"name","command","old-name","nearby"}){
        java.io.File file=new java.io.File(context.getCacheDir(),"wake-"+name+".wav");byte[] bytes=Files.readAllBytes(file.toPath());ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(input.getInt(24)==16000&&input.getShort(22)==1,"Fixed sample must be 16 kHz mono WAV");float[] samples=new float[(bytes.length-44)/2+16000];
        for(int i=44,j=0;i+1<bytes.length;i+=2,j++)samples[j]=input.getShort(i)/32768f;
        boolean hit=detect(model,samples,config.word);report.putBoolean(name+"Detected",hit);
        check(hit==(name.equals("name")||name.equals("command")),"Unexpected KWS result for "+name);
      }
      short[] beep=VoiceReadyCue.samples();float[] samples=new float[16000+beep.length*2/3];for(int i=0;i<beep.length*2/3;i++)samples[3200+i]=beep[Math.min(beep.length-1,i*3/2)]/32768f;
      check(!detect(model,samples,config.word),"Ready tone self-triggered KWS");report.putBoolean("toneDoesNotMatchWakeModel",true);
      coldVoiceView(context,report);
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{if(cue!=null)cue.cancel();if(model!=null)model.release();}
    finish(code,report);
  }
  private static boolean detect(KeywordSpotter model,float[] samples,String keyword){OnlineStream stream=model.createStream("");try{
    for(int start=0;start<samples.length;start+=512){stream.acceptWaveform(Arrays.copyOfRange(samples,start,Math.min(samples.length,start+512)),16000);while(model.isReady(stream)){model.decode(stream);if(keyword.equals(model.getResult(stream).getKeyword()))return true;}}
    return false;
  }finally{stream.release();}}
  private void coldVoiceView(Context context,Bundle report)throws Exception{
    VoiceCoordinator[] holder=new VoiceCoordinator[1];runOnMainSync(()->holder[0]=VoiceCoordinator.get(context));
    VoiceCoordinator coordinator=holder[0];PiRuntime runtime=PiRuntime.get(context);
    for(int i=0;i<150&&!runtime.ready;i++)Thread.sleep(100);
    check(runtime.ready,"Voice runtime did not initialize");boolean configured=runtime.configured;
    java.lang.reflect.Field pending=VoiceWakeService.class.getDeclaredField("handoff");pending.setAccessible(true);WakeHandoff buffer=(WakeHandoff)pending.get(null);
    java.lang.reflect.Field view=VoiceCallActivity.class.getDeclaredField("active");view.setAccessible(true);
    runtime.unlisten(coordinator);int played=VoiceReadyCue.completedPlays;
    try{
      runOnMainSync(()->VoiceCallActivity.show(context));for(int i=0;i<80&&!VoiceCallActivity.isForeground();i++)Thread.sleep(50);check(VoiceCallActivity.isForeground(),"Voice view did not open");waitForIdleSync();
      Activity original=(Activity)view.get(null);long id=buffer.begin(Collections.emptyList(),SystemClock.elapsedRealtime());buffer.finish(id);
      runOnMainSync(()->{runtime.configured=false;SystemWindowNavigation.showWake(context,id);});Thread.sleep(300);
      check(view.get(null)==original&&coordinator.ownsWake(id)&&!coordinator.controller.sessionActive(),"Pending wake recreated UI or started an unready input");
      check(VoiceReadyCue.completedPlays==played,"Unready connection played a cue");report.putBoolean("pendingWakeKeepsOneVoiceViewWithoutFalseCue",true);
    }finally{
      runOnMainSync(()->{coordinator.controller.stopSession(false);VoiceWakeService.stop(context);runtime.configured=configured;runtime.listen(coordinator);try{Activity shown=(Activity)view.get(null);if(shown!=null)shown.finish();}catch(Exception ignored){}});
    }
  }
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
