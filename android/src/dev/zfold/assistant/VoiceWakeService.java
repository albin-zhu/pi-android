package dev.zfold.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/** Opt-in always-listening wake word. Loads only the small Chinese KWS model. */
public final class VoiceWakeService extends Service {
  static final String WAKE_WORD="小派";
  private static final String CHANNEL="zfold_wake";
  private static final long WAKE_AUDIO_TTL=10_000;
  static volatile boolean alive,listening;static volatile String lastFailure;
  static volatile long lastWakeTriggerAt;
  private static ArrayList<byte[]> wakeAudio;
  private static volatile long wakeAudioAt;
  private final Handler main=new Handler(Looper.getMainLooper());
  private volatile boolean running;private volatile AudioRecord recorder;

  static boolean enabled(Context context){return context.getSharedPreferences("voice",Context.MODE_PRIVATE).getBoolean("wake_word",false);}
  static void setEnabled(Context context,boolean value){
    context.getSharedPreferences("voice",Context.MODE_PRIVATE).edit().putBoolean("wake_word",value).apply();
    if(value)resume(context);else stop(context);
  }
  static void stop(Context context){context.stopService(new Intent(context,VoiceWakeService.class));}
  static void resume(Context context){if(enabled(context))context.startForegroundService(new Intent(context,VoiceWakeService.class));}
  private static synchronized void clearWakeAudio(){wakeAudio=null;wakeAudioAt=0;}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onCreate(){
    super.onCreate();
    if(!enabled(this)){stopSelf();return;}
    alive=true;running=true;listening=true;lastFailure=null;clearWakeAudio();
    NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL,WAKE_WORD+"唤醒",NotificationManager.IMPORTANCE_LOW));
    Notification notification=new Notification.Builder(this,CHANNEL).setContentTitle(WAKE_WORD+"正在监听")
      .setContentText("说“"+WAKE_WORD+"”唤醒助手").setSmallIcon(R.drawable.ic_microphone).setOngoing(true).build();
    startForeground(31,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    new Thread(this::listen,"zfold-wake").start();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){return listening?START_STICKY:START_NOT_STICKY;}
  @Override public void onDestroy(){running=false;listening=false;clearWakeAudio();AudioRecord active=recorder;if(active!=null)try{active.stop();}catch(IllegalStateException ignored){}super.onDestroy();}
  private void listen(){
    KeywordSpotter spotter=null;AudioRecord audio=null;String failure=null;boolean detected=false;
    ArrayDeque<byte[]> history=new ArrayDeque<>();
    try{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      String prefix="models/kws/";
      KeywordSpotterConfig config=new KeywordSpotterConfig(new FeatureConfig(),
        new OnlineModelConfig(new OnlineTransducerModelConfig(prefix+"encoder.int8.onnx",prefix+"decoder.int8.onnx",prefix+"joiner.int8.onnx",new com.k2fsa.sherpa.onnx.QnnConfig()),
          new OnlineParaformerModelConfig(),new OnlineZipformer2CtcModelConfig(),new OnlineNeMoCtcModelConfig(),new OnlineToneCtcModelConfig(),
          prefix+"tokens.txt",1,false,"cpu","zipformer2","",""),4,prefix+"keywords.txt",1.5f,0.25f,2);
      spotter=new KeywordSpotter(getAssets(),config);
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(minimum<=0)throw new IllegalStateException("无法建立唤醒麦克风");
      audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
      if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("唤醒麦克风未就绪");
      recorder=audio;audio.startRecording();
      if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("唤醒麦克风没有开始");
      OnlineStream stream=spotter.createStream("");short[] pcm=new short[512];float[] samples=new float[512];
      int debugFrames=0;double debugMaxRms=0;
      while(running&&listening){
        int size=0;while(size<pcm.length&&running&&listening){int count=audio.read(pcm,size,pcm.length-size,AudioRecord.READ_BLOCKING);if(count<=0)throw new IllegalStateException("唤醒麦克风读取失败");size+=count;}
        if(!running||!listening)break;
        byte[] pcmBytes=new byte[pcm.length*2];
        for(int i=0;i<pcm.length;i++)samples[i]=pcm[i]/32768f;
        for(int i=0;i<pcm.length;i++){pcmBytes[2*i]=(byte)(pcm[i]&0xff);pcmBytes[2*i+1]=(byte)(pcm[i]>>8);}
        double power=0;for(int i=0;i<pcm.length;i++)power+=samples[i]*samples[i];double rms=Math.sqrt(power/pcm.length);
        debugMaxRms=Math.max(debugMaxRms,rms);
        if(++debugFrames>=16){android.util.Log.i("ZfoldWake","KWS listening maxRms="+String.format("%.4f",debugMaxRms));debugFrames=0;debugMaxRms=0;}
        synchronized(history){history.addLast(pcmBytes);while(history.size()>344)history.removeFirst();}
        stream.acceptWaveform(samples,16000);
        while(running&&spotter.isReady(stream)){
          spotter.decode(stream);KeywordSpotterResult result=spotter.getResult(stream);
          String keyword=result==null?"":result.getKeyword();
          if(result!=null&&result.getTokens()!=null&&result.getTokens().length>0)android.util.Log.i("ZfoldWake","KWS result keyword="+keyword+" tokens="+String.join(",",result.getTokens()));
          if(!keyword.isBlank()){
            detected=true;
            long captureStarted=SystemClock.elapsedRealtime(),captureHard=captureStarted+5000,quietDeadline=captureStarted+1500,lastSound=captureStarted;
            while(running&&listening){
              int tailSize=0;while(tailSize<pcm.length&&running&&listening){int count=audio.read(pcm,tailSize,pcm.length-tailSize,AudioRecord.READ_BLOCKING);if(count<=0)throw new IllegalStateException("唤醒麦克风读取失败");tailSize+=count;}
              if(!running||!listening)break;
              byte[] trailingBytes=new byte[pcm.length*2];double tailPower=0;
              for(int i=0;i<pcm.length;i++){float value=pcm[i]/32768f;samples[i]=value;tailPower+=value*value;trailingBytes[2*i]=(byte)(pcm[i]&0xff);trailingBytes[2*i+1]=(byte)(pcm[i]>>8);}
              synchronized(history){history.addLast(trailingBytes);while(history.size()>344)history.removeFirst();}
              long now=SystemClock.elapsedRealtime();double tailRms=Math.sqrt(tailPower/pcm.length);
              if(tailRms>=0.012){lastSound=now;quietDeadline=Math.max(quietDeadline,now+900);}
              if(now>=quietDeadline||now>=captureHard)break;
            }
            android.util.Log.i("ZfoldWake","Wake trailing captured ms="+Math.min(5000,SystemClock.elapsedRealtime()-captureStarted)+" lastSoundAgo="+Math.max(0,SystemClock.elapsedRealtime()-lastSound));
            saveWakeAudio(history);running=false;listening=false;break;
          }
        }
      }
      stream.release();
    }catch(Throwable error){failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();lastFailure=failure;}
    finally{
      running=false;listening=false;AudioRecord active=recorder;recorder=null;
      if(audio!=null){try{audio.stop();}catch(IllegalStateException ignored){}audio.release();}
      if(spotter!=null)spotter.release();
    }
    if(detected)main.postDelayed(this::wakeAssistant,180);
    else if(failure!=null){String reason=failure;main.post(()->{android.util.Log.w("ZfoldWake","Wake unavailable: "+reason);stopSelf();});}
  }
  static boolean active(){return alive&&listening&&lastFailure==null;}
  private static synchronized void saveWakeAudio(ArrayDeque<byte[]> history){
    wakeAudio=new ArrayList<>(history);wakeAudioAt=SystemClock.elapsedRealtime();
  }
  static synchronized ArrayList<byte[]> consumeWakeAudio(){
    ArrayList<byte[]> audio=wakeAudio;wakeAudio=null;
    if(audio==null||SystemClock.elapsedRealtime()-wakeAudioAt>WAKE_AUDIO_TTL)return null;
    return audio;
  }
  private void wakeAssistant(){
    stopSelf();
    lastWakeTriggerAt=SystemClock.elapsedRealtime();
    if(AssistantSessionService.isShowing()){main.postDelayed(()->AssistantSessionService.startVoice(),250);return;}
    AssistantOverlay.showForVoice(this);
  }
}
