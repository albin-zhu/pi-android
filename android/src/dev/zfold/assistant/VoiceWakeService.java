package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;
import com.k2fsa.sherpa.onnx.*;

/** Local opt-in KWS. A hit opens the voice view immediately and hands off its bounded PCM. */
public final class VoiceWakeService extends Service {
  private static final String CHANNEL="zfold_wake";
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final WakeHandoff handoff=new WakeHandoff();
  private static volatile VoiceWakeService current;
  private static final Set<Object> outputs=Collections.newSetFromMap(new IdentityHashMap<>());
  static volatile boolean alive,listening;static volatile String lastFailure;static volatile long lastWakeTriggerAt;
  private volatile boolean running;private volatile AudioRecord recorder;private volatile long wakeId;

  private static SharedPreferences preferences(Context context){return context.getSharedPreferences(PiRuntime.testMode?"test-voice":"voice",Context.MODE_PRIVATE);}
  static boolean enabled(Context context){return preferences(context).getBoolean("wake_word",false);}
  static void setEnabled(Context context,boolean value){preferences(context).edit().putBoolean("wake_word",value).apply();if(value)resume(context);else stop(context);}
  static void stop(Context context){handoff.cancel();context.stopService(new Intent(context,VoiceWakeService.class));}
  static void resume(Context context){
    VoiceTurnController voice=VoiceCoordinator.current();
    if(!enabled(context)||!outputs.isEmpty()||(voice!=null&&voice.sessionActive()))return;
    try{context.startForegroundService(new Intent(context,VoiceWakeService.class));}catch(RuntimeException failure){lastFailure="系统暂不允许后台唤醒，请打开应用后重试";}
  }
  static void suspendOutput(Context context,Object owner){outputs.add(owner);stop(context);}
  static void releaseOutput(Context context,Object owner){outputs.remove(owner);main.postDelayed(()->resume(context),650);}
  static boolean pending(long id){return handoff.pending(id,SystemClock.elapsedRealtime());}
  static void discard(long id){handoff.cancel(id);}
  static void takeAudio(Context context,long id,Consumer<WakeHandoff.Audio> callback){
    boolean accepted=handoff.claim(id,SystemClock.elapsedRealtime(),audio->main.post(()->callback.accept(audio)));
    if(!accepted){callback.accept(null);return;}
    // Preserve the payload while onDestroy stops the producer. The producer's
    // finally block releases AudioRecord before completing the handoff.
    context.stopService(new Intent(context,VoiceWakeService.class));
  }
  static boolean active(){return alive&&listening&&lastFailure==null;}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public void onCreate(){
    super.onCreate();if(!enabled(this)||!outputs.isEmpty()||(VoiceCoordinator.current()!=null&&VoiceCoordinator.current().sessionActive())){stopSelf();return;}
    current=this;alive=true;running=true;listening=false;lastFailure=null;String word=WakeConfig.get(this).word;
    NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);manager.createNotificationChannel(new NotificationChannel(CHANNEL,word+"唤醒",NotificationManager.IMPORTANCE_LOW));
    Notification notification=new Notification.Builder(this,CHANNEL).setContentTitle(word+"正在监听").setContentText("说“"+word+"”进入实时语音").setSmallIcon(R.drawable.ic_microphone).setOngoing(true).build();
    startForeground(31,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);Thread thread=new Thread(this::listen,"chengcheng-wake");thread.setDaemon(true);thread.start();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){return running?START_STICKY:START_NOT_STICKY;}
  @Override public void onDestroy(){running=false;if(current==this){current=null;alive=false;listening=false;}AudioRecord active=recorder;if(active!=null)try{active.stop();}catch(IllegalStateException ignored){}super.onDestroy();}
  private void listen(){
    KeywordSpotter spotter=null;OnlineStream stream=null;AudioRecord audio=null;String failure=null;ArrayDeque<byte[]> history=new ArrayDeque<>();
    try{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);WakeConfig config=WakeConfig.get(this);spotter=config.createSpotter(this);stream=spotter.createStream("");
      if(!running)return;
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
      if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("唤醒麦克风未就绪");
      recorder=audio;if(!running)return;audio.startRecording();if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("唤醒麦克风未开始录音");if(current==this)listening=true;
      short[] pcm=new short[512];float[] samples=new float[512];long totalSamples=0;
      while(running){
        int size=0;while(size<pcm.length&&running){int count=audio.read(pcm,size,pcm.length-size,AudioRecord.READ_BLOCKING);if(count<=0)throw new IllegalStateException("唤醒麦克风读取中断");size+=count;}
        if(!running)break;byte[] bytes=new byte[pcm.length*2];
        for(int i=0;i<pcm.length;i++){samples[i]=pcm[i]/32768f;bytes[2*i]=(byte)pcm[i];bytes[2*i+1]=(byte)(pcm[i]>>8);}
        if(wakeId>0){handoff.append(wakeId,bytes);if(handoff.full(wakeId))break;continue;}
        totalSamples+=pcm.length;history.addLast(bytes);while(history.size()>128)history.removeFirst();stream.acceptWaveform(samples,16000);
        while(running&&spotter.isReady(stream)){
          spotter.decode(stream);KeywordSpotterResult result=spotter.getResult(stream);
          if(result!=null&&config.word.equals(result.getKeyword())){
            float[] times=result.getTimestamps();float first=times!=null&&times.length>0?times[0]:Float.NaN;
            wakeId=handoff.begin(WakeHandoff.keywordPrefix(history,totalSamples,first),SystemClock.elapsedRealtime());long token=wakeId;lastWakeTriggerAt=SystemClock.elapsedRealtime();main.post(()->wakeAssistant(token));break;
          }
        }
      }
    }catch(Throwable error){if(running){failure="唤醒模型或麦克风不可用";lastFailure=failure;}}
    finally{
      running=false;if(current==this)listening=false;recorder=null;if(audio!=null){try{audio.stop();}catch(Exception ignored){}audio.release();}
      if(stream!=null)stream.release();if(spotter!=null)spotter.release();if(wakeId>0)handoff.finish(wakeId);
    }
    if(failure!=null||wakeId==0)main.post(this::stopSelf);
  }
  private void wakeAssistant(long id){
    if(!pending(id))return;
    try{SystemWindowNavigation.showWake(this,id);}catch(RuntimeException error){handoff.cancel(id);lastFailure="系统未允许打开实时语音，请从应用进入";stopSelf();return;}
    main.postDelayed(()->{if(pending(id)){handoff.cancel(id);stopSelf();}},WakeHandoff.TTL);
  }
}
