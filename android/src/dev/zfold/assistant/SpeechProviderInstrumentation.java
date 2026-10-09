package dev.zfold.assistant;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.nio.file.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Emulator only. Optional live tests use fixed synthetic audio, never a microphone. */
public final class SpeechProviderInstrumentation extends Instrumentation {
  private boolean live;private CloudTtsPlayer player;private CloudAsrClient recognizer;private MainActivity chat;private AlertDialog dialog;
  @Override public void onCreate(Bundle args){live=args!=null&&"true".equals(args.getString("live"));start();}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;byte[] original=null;boolean existed=false,isolated=false;
    try{
      String product=Build.PRODUCT;if(!product.contains("sdk")&&!product.contains("emulator"))throw new IllegalStateException("Emulator only");
      PiRuntime.testMode=true;isolated=true;java.io.File configFile=SpeechConfig.file(getTargetContext());existed=configFile.isFile();if(existed)original=Files.readAllBytes(configFile.toPath());
      SpeechConfig.save(getTargetContext(),true,false,1,"synthetic-test-secret");SpeechConfig config=SpeechConfig.load(getTargetContext());
      check(config.cloudAsr&&!config.cloudTts&&config.speaker.equals(SpeechConfig.VOICES[1]),"ASR/TTS not independently configured");
      SpeechConfig.save(getTargetContext(),false,true,2,"");config=SpeechConfig.load(getTargetContext());
      check(!config.cloudAsr&&config.cloudTts&&config.document.optString("apiKey").equals("synthetic-test-secret"),"Blank key did not preserve credential");
      check(config.document.optJSONObject("asr").optJSONArray("hotwords").length()>0,"ASR hotwords missing");report.putBoolean("independentProvidersAndKeyPreservation",true);
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");
      for(int i=0;i<150&&(MainActivity.current()==null||!MainActivity.current().runtime.ready);i++)Thread.sleep(100);
      chat=MainActivity.current();check(chat!=null&&chat.runtime.ready,"Runtime not ready");
      // A silence deadline ends cloud audio; only the server's final revision may dispatch a prompt.
      int[] premature={0};VoiceTurnController isolatedVoice=new VoiceTurnController(getTargetContext(),new VoiceTurnController.Callback(){public boolean active(){return true;}public void status(String text){}public void voiceState(boolean session,boolean speaking){}public void prompt(String text){premature[0]++;}public void sessionEnded(String reason){}});
      CloudAsrClient pendingRecognition=new CloudAsrClient(getTargetContext(),new CloudAsrClient.Callback(){public void ready(){}public void partial(String text){}public void result(String text){}public void error(String reason){}});
      field(isolatedVoice,"mode",VoiceTurnController.Mode.LISTENING);field(isolatedVoice,"cloudAsr",pendingRecognition);field(isolatedVoice,"livePartial","尚未确定的识别片段");field(isolatedVoice,"partialFinalToken",7L);field(isolatedVoice,"lastLiveVoiceAt",SystemClock.elapsedRealtime()-3000);
      java.lang.reflect.Method deadline=VoiceTurnController.class.getDeclaredMethod("finalizeLivePartial",long.class);deadline.setAccessible(true);
      runOnMainSync(()->{try{deadline.invoke(isolatedVoice,7L);}catch(Exception error){throw new IllegalStateException(error);}});
      check(premature[0]==0&&isolatedVoice.listeningForInput(),"Cloud partial dispatched before final result");runOnMainSync(isolatedVoice::destroy);report.putBoolean("cloudSilenceWaitsForFinalRevision",true);
      runOnMainSync(()->dialog=SpeechSettings.show(chat,chat.voice,()->{}));Thread.sleep(400);
      check(find(dialog.getWindow().getDecorView(),"语音识别 · ASR") instanceof Spinner,"ASR selector missing");
      check(find(dialog.getWindow().getDecorView(),"语音合成 · TTS") instanceof Spinner,"TTS selector missing");
      View field=find(dialog.getWindow().getDecorView(),"豆包语音 API Key");check(field instanceof EditText&&((EditText)field).getText().length()==0,"Secret was rendered in UI");
      EmulatorChecks.screenshot(this,"speech-settings.png");report.putBoolean("settingsHasSelectorsWithoutRenderingSecret",true);
      runOnMainSync(()->{dialog.dismiss();dialog=null;});
      if(original!=null)Files.write(configFile.toPath(),original);else Files.deleteIfExists(configFile.toPath());
      if(live){
        check(SpeechConfig.load(getTargetContext()).hasKey(),"Provision an isolated test key before live tests");
        CountDownLatch played=new CountDownLatch(1);String[] problem={null};
        runOnMainSync(()->{player=new CloudTtsPlayer(chat,new CloudTtsPlayer.Callback(){public void start(String id){}public void done(String id){played.countDown();}public void error(String id,String reason){problem[0]=reason;played.countDown();}});player.begin("live-tts");player.text("live-tts","你好，我是"+AgentIdentity.name(chat)+"。语音服务已经连接。");player.finish("live-tts");});
        check(played.await(25,TimeUnit.SECONDS)&&problem[0]==null,"Live TTS playback failed: "+problem[0]);runOnMainSync(()->{player.close();player=null;});report.putBoolean("liveVolcTtsThroughAndroidAudioTrack",true);
        CountDownLatch started=new CountDownLatch(1);int[] completions={0};
        runOnMainSync(()->{player=new CloudTtsPlayer(chat,new CloudTtsPlayer.Callback(){public void start(String id){started.countDown();}public void done(String id){completions[0]++;}public void error(String id,String reason){problem[0]=reason;started.countDown();}});player.begin("cancel-tts");player.text("cancel-tts","这是一段用于验证点击打断的固定测试文本。停止之后，这一段语音不会继续播放。");player.finish("cancel-tts");});
        check(started.await(15,TimeUnit.SECONDS)&&problem[0]==null,"Cancel test did not start audio");runOnMainSync(()->player.stop());Thread.sleep(900);check(completions[0]==0,"Cancelled playback completed a stale utterance");runOnMainSync(()->{player.close();player=null;});report.putBoolean("liveTtsCancellationIgnoresLateCompletion",true);
        byte[] wave=Files.readAllBytes(new java.io.File(chat.getCacheDir(),"speech-asr.wav").toPath());ByteBuffer header=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);check(wave.length>44&&header.getInt(24)==16000&&header.getShort(22)==1,"Fixed ASR sample must be 16 kHz mono WAV");
        CountDownLatch recognized=new CountDownLatch(1);String[] text={""};problem[0]=null;
        runOnMainSync(()->{recognizer=new CloudAsrClient(chat,new CloudAsrClient.Callback(){public void ready(){}public void partial(String value){}public void result(String value){text[0]=value;recognized.countDown();}public void error(String reason){problem[0]=reason;recognized.countDown();}});recognizer.start();});
        for(int offset=44;offset<wave.length;offset+=6400){byte[] pcm=Arrays.copyOfRange(wave,offset,Math.min(wave.length,offset+6400));recognizer.pcm(pcm);Thread.sleep(200);}
        recognizer.finish();check(recognized.await(10,TimeUnit.SECONDS)&&problem[0]==null&&text[0].contains("403"),"Live ASR synthetic roundtrip failed: "+problem[0]);report.putBoolean("liveVolcAsrFromSyntheticPcm",true);report.putString("asrSource","generated synthetic WAV; no microphone");
      }
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{
      if(isolated)try{runOnMainSync(()->{if(dialog!=null)dialog.dismiss();if(player!=null)player.close();if(recognizer!=null)recognizer.cancel();});if(existed&&original!=null)Files.write(SpeechConfig.file(getTargetContext()).toPath(),original);else Files.deleteIfExists(SpeechConfig.file(getTargetContext()).toPath());}catch(Exception error){report.putString("cleanupFailure",error.toString());code=Activity.RESULT_CANCELED;}
    }
    finish(code,report);
  }
  private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=find(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;}return null;}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
  private static void field(Object target,String name,Object value)throws Exception{java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
}
