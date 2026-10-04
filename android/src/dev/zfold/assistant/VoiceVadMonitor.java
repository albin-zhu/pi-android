package dev.zfold.assistant;

import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;

/** Mic-side Silero VAD, active only while an opted-in voice session speaks. */
final class VoiceVadMonitor {
  interface Callback {default void started(boolean echoCancellation){}void speech();void failed(String reason);}
  private final Context context;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
  private volatile boolean running,cancelled;private volatile AudioRecord recorder;
  VoiceVadMonitor(Context context,Callback callback){this.context=context.getApplicationContext();this.callback=callback;}
  static boolean enabled(Context context){return context.getSharedPreferences("voice",Context.MODE_PRIVATE).getBoolean("auto_barge_in",true);}
  static void setEnabled(Context context,boolean enabled){context.getSharedPreferences("voice",Context.MODE_PRIVATE).edit().putBoolean("auto_barge_in",enabled).apply();}
  void start(){if(running)return;cancelled=false;running=true;new Thread(this::monitor,"zfold-voice-vad").start();}
  void stop(){cancelled=true;running=false;AudioRecord active=recorder;if(active!=null)try{active.stop();}catch(IllegalStateException ignored){}}
  private void monitor(){
    Vad vad=null;AudioRecord audio=null;AcousticEchoCanceler echo=null;boolean detected=false;String failure=null;
    try{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      SileroVadModelConfig silero=new SileroVadModelConfig("models/silero_vad.int8.onnx",0.55f,0.35f,0.20f,512,10f);
      vad=new Vad(context.getAssets(),new VadModelConfig(silero,new TenVadModelConfig(),16000,1,"cpu",false));
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(minimum<=0)throw new IllegalStateException("无法建立 16 kHz 麦克风输入");
      audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,4096));
      if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("麦克风输入未就绪");
      recorder=audio;
      if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(audio.getAudioSessionId());if(echo!=null&&!echo.getEnabled())echo.setEnabled(true);}
      audio.startRecording();if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("麦克风没有开始录音");
      boolean echoActive=echo!=null&&echo.getEnabled();main.post(()->{if(!cancelled)callback.started(echoActive);});
      long started=SystemClock.elapsedRealtime();int consecutive=0;short[] pcm=new short[512];float[] samples=new float[512];
      while(running&&!cancelled){
        int size=0;while(size<pcm.length&&running&&!cancelled){int count=audio.read(pcm,size,pcm.length-size,AudioRecord.READ_BLOCKING);if(count<=0)throw new IllegalStateException("麦克风读取失败（"+count+"）");size+=count;}
        if(!running||cancelled)break;
        double power=0;for(int i=0;i<pcm.length;i++){float value=pcm[i]/32768f;samples[i]=value;power+=value*value;}
        float probability=vad.compute(samples);double rms=Math.sqrt(power/pcm.length);
        if(SystemClock.elapsedRealtime()-started>250&&probability>=0.82f&&rms>=0.009)consecutive++;else consecutive=Math.max(0,consecutive-2);
        if(consecutive>=7){detected=true;running=false;break;}
      }
    }catch(Throwable error){failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    finally{recorder=null;if(echo!=null)echo.release();if(audio!=null){try{audio.stop();}catch(IllegalStateException ignored){}audio.release();}if(vad!=null)vad.release();running=false;}
    if(detected&&!cancelled)main.post(()->{if(!cancelled)callback.speech();});
    else if(failure!=null&&!cancelled){String reason=failure;main.post(()->{if(!cancelled)callback.failed(reason);});}
  }
}
