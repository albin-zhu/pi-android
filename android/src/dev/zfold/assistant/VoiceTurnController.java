package dev.zfold.assistant;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.speech.*;
import android.speech.tts.*;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** Single owner of a continuous ASR/TTS/VAD turn; both assistant surfaces use it. */
final class VoiceTurnController {
  enum Mode {IDLE,LISTENING,THINKING,SPEAKING,PAUSED}
  interface Callback {
    boolean active();
    void status(String text);
    void voiceState(boolean session,boolean speaking);
    void prompt(String text);
    void sessionEnded(String reason);
  }
  private final Context context;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
  private final PiRuntime runtime;
  private SpeechRecognizer recognizer;private TextToSpeech tts;private VoiceBargeIn barge;
  private SpeechConfig speechConfig;private CloudTtsPlayer cloudTts;private CloudAsrClient cloudAsr;
  private String cloudSegment;private boolean asrCloudFailed,ttsCloudFailed;
  private final VoiceReadyCue readyCue=new VoiceReadyCue();private ArrayList<byte[]> wakePrefixAudio;private boolean stripInitialWake;
  private VoiceAsrSource liveAsr;
  private Mode mode=Mode.IDLE;private String activeUtterance,speechPending="",asrLanguage="zh-CN";
  private final StringBuilder spoken=new StringBuilder();
  private boolean ttsReady,listening,asrChecking,recognizerLocal,forceSystemAsr;
  private int emptySpeech,unsupported,retries,transientErrors,epoch;private long serial,audioCooldownUntil,recognizerReleasedAt;private volatile String livePartial="";
  private boolean taskVoice,speechSuppressed;
  private volatile long lastLiveVoiceAt;private long partialFinalToken;
  private final VoiceEndpointGate endpointGate=new VoiceEndpointGate(this::handleEndpoint);
  private long endpointFinalToken;

  VoiceTurnController(Context context,Callback callback){
    this.context=context.getApplicationContext();this.callback=callback;runtime=PiRuntime.get(this.context);
  }
  boolean sessionActive(){return mode!=Mode.IDLE&&mode!=Mode.PAUSED&&callback.active();}
  boolean speaking(){return mode==Mode.SPEAKING||engineSpeaking();}
  boolean listeningForInput(){return sessionActive()&&mode==Mode.LISTENING;}
  private boolean engineSpeaking(){return activeUtterance!=null;}
  void beginSession(){beginSession(0);}
  void beginWakeSession(long id){beginSession(id);}
  private void beginSession(long wakeId){
    if(sessionActive())return;
    android.util.Log.i("ZfoldSpeech","Voice session begin");
    if(wakeId==0)VoiceWakeService.stop(context);readyCue.arm(wakeId);stripInitialWake=wakeId>0;wakePrefixAudio=null;speechConfig=SpeechConfig.load(context);asrCloudFailed=false;ttsCloudFailed=false;ensureTts();
    AudioModeOwner.enterSession(context);
    boolean releasedRecognizer=recognizer!=null;
    if(recognizer!=null){recognizer.destroy();recognizer=null;}
    // A wake handoff already waits for AudioRecord release. Do not insert a
    // half-second recording gap when there was no previous ASR engine to release.
    recognizerReleasedAt=SystemClock.elapsedRealtime()-(wakeId>0&&!releasedRecognizer?500:0);
    epoch++;emptySpeech=0;unsupported=0;retries=0;transientErrors=0;forceSystemAsr=false;asrChecking=false;spoken.setLength(0);activeUtterance=null;speechPending="";livePartial="";
    speechSuppressed=runtime.busy;
    partialFinalToken=0;
    endpointFinalToken=0;
    endpointGate.reset();
    stopLiveAsr();
    mode=Mode.LISTENING;taskVoice=runtime.busy;callback.voiceState(true,false);readyCue.viewReady(VoiceCallActivity.isForeground());
    int token=epoch;
    if(wakeId>0){
      VoiceWakeService.takeAudio(context,wakeId,audio->{
        if(token!=epoch||!sessionActive())return;
        if(audio==null){pause("唤醒录音已过期，请重新唤醒");return;}
        wakePrefixAudio=audio.lost?null:audio.pcm;stripInitialWake=!audio.lost;
        if(audio.lost)callback.status("唤醒指令接续超时，提示音后请重新说");
        listen();
      });
    }else main.postDelayed(()->{if(token==epoch&&sessionActive()&&mode==Mode.LISTENING)listen();},350);
  }
  void beginTaskVoice(){
    if(sessionActive()){
      if(speaking()){taskVoice=true;interrupt();return;}
      if(mode==Mode.LISTENING&&taskVoice){callback.status("任务执行中 · 正在收听新指令");return;}
      if(mode==Mode.LISTENING){taskVoice=true;callback.status("任务执行中 · 正在收听新指令");return;}
      if(mode==Mode.THINKING&&runtime.busy){
        taskVoice=true;mode=Mode.LISTENING;callback.voiceState(true,false);
        callback.status("任务执行中 · 正在收听新指令");listen();return;
      }
      return;
    }
    taskVoice=true;callback.status("任务执行中 · 可语音接管");
    beginSession();
  }
  void interrupt(){interrupt(false);}
  void forceInterrupt(){interrupt(true);}
  private void interrupt(boolean force){
    if(!force&&!speaking())return;
    if(mode==Mode.LISTENING&&(listening||asrChecking)){callback.status("正在听，请说话…");return;}
    speechSuppressed=true;taskVoice=runtime.busy;asrChecking=false;listening=false;
    epoch++;activeUtterance=null;speechPending="";stopBarge(false);stopTts();
    stopLiveAsr();livePartial="";partialFinalToken++;endpointFinalToken++;
    if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;old.cancel();cleanupRecognizer(old);}
    mode=Mode.LISTENING;callback.voiceState(true,false);callback.status("已打断，正在切换收听…");
    audioCooldownUntil=SystemClock.elapsedRealtime()+800;int token=epoch;
    main.postDelayed(()->{if(token==epoch&&sessionActive()&&!engineSpeaking())listen();},800);
  }
  void stopSession(boolean cancelTask){
    readyCue.cancel();wakePrefixAudio=null;stripInitialWake=false;epoch++;mode=Mode.IDLE;activeUtterance=null;ttsSpeakingReset();speechPending="";spoken.setLength(0);stopBarge(false);
    stopLiveAsr();
    if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;old.cancel();cleanupRecognizer(old);}
    stopTts();
    callback.voiceState(false,false);
    taskVoice=false;
    if(cancelTask)runtime.send(PiRuntime.json("type","stop"));
    AudioModeOwner.exitSession(context);
    releaseLater();
  }
  void beginPrompt(){
    speechSuppressed=false;stripInitialWake=false;wakePrefixAudio=null;
    epoch++;activeUtterance=null;speechPending="";spoken.setLength(0);stopBarge(false);
    stopTts();
    stopLiveAsr();
    if(sessionActive())mode=Mode.THINKING;
    taskVoice=false;
  }
  void turnStart(){
    beginPrompt();if(sessionActive()){mode=Mode.THINKING;callback.status("正在思考…");}
  }
  void assistantStart(){
    if(!sessionActive())return;
    if(useCloudTts()){finishResidual();finishCloudSegment();return;}
    // A Pi turn can emit multiple assistant messages around tool calls. Keep the
    // spoken-text reference while the existing TTS queue is still alive so BargeIn
    // can still recognize the next echoed sentence.
    if(engineSpeaking())return;
    speechPending="";spoken.setLength(0);
  }
  void append(String delta){
    if(speechSuppressed||!sessionActive()||!speechReady()||delta==null||delta.isEmpty())return;
    String trimmed=delta.trim();
    if(trimmed.startsWith("{")||trimmed.startsWith("["))return;
    StringBuilder pending=new StringBuilder(speechPending+delta);
    for(String sentence:PhoneSpeech.completeSpeechSentences(pending))queue(sentence);
    speechPending=pending.toString();
  }
  void toolAnnounce(String action){
    if(speechSuppressed||!sessionActive()||!speechReady()||action==null||action.isEmpty())return;
    if(useCloudTts()){finishResidual();finishCloudSegment();}
    String label=PhoneSpeech.speakable(action);
    if(label.length()>40)label=label.substring(0,40);
    if(!label.endsWith("。")&&!label.endsWith("！")&&!label.endsWith("？"))label+="。";
    queue(label);
    if(useCloudTts())finishCloudSegment();
  }
  void turnEnded(boolean aborted){
    if(aborted){stopSession(false);callback.status(runtime.stopUnconfirmed?"停止尚未确认，请检查工具记录":"已停止");return;}
    if(!sessionActive())return;
    if(mode==Mode.LISTENING&&(listening||asrChecking)){callback.status("正在听…");return;}
    if(useCloudTts()){finishResidual();finishCloudSegment();}
    else if(ttsReady&&finishResidual())return;
    if(engineSpeaking()){mode=Mode.SPEAKING;callback.status("正在播报 · 播完自动听");return;}
    if(SystemClock.elapsedRealtime()<audioCooldownUntil){mode=Mode.LISTENING;callback.status("正在播报 · 播完自动听");return;}
    mode=Mode.LISTENING;listen();
  }
  void error(){
    if(sessionActive())stopSession(false);
  }
  void destroy(){
    readyCue.cancel();wakePrefixAudio=null;stripInitialWake=false;epoch++;mode=Mode.IDLE;activeUtterance=null;speechPending="";spoken.setLength(0);stopBarge(false);
    stopLiveAsr();
    if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
    if(tts!=null){tts.stop();tts.shutdown();tts=null;}
    if(cloudTts!=null){cloudTts.close();cloudTts=null;}
    AudioModeOwner.exitSession(context);
  }
  void availableOfflineVoice(Consumer<String> callback){
    ensureTts();int[] waited={0};Runnable[] poll=new Runnable[1];
    poll[0]=()->{
      if(ttsReady||waited[0]>=30){callback.accept(ttsReady&&tts!=null?PhoneSpeech.availableOfflineChineseVoice(tts):null);return;}
      waited[0]++;main.postDelayed(poll[0],100);
    };
    main.post(poll[0]);
  }
  private boolean finishResidual(){
    String sentence=PhoneSpeech.remainingSpeechSentence(new StringBuilder(speechPending));speechPending="";
    return !sentence.isEmpty()&&queue(sentence);
  }
  private boolean queue(String sentence){
    if(speechSuppressed)return false;
    if(useCloudTts()){
      if(cloudSegment==null){cloudSegment="cloud-"+(++serial);activeUtterance=cloudSegment;cloudPlayer().begin(cloudSegment);}
      cloudPlayer().text(cloudSegment,sentence);
      spoken.append(sentence).append('\n');if(barge!=null)barge.spoken(sentence);
      if(sessionActive()){mode=Mode.SPEAKING;callback.voiceState(true,true);}return true;
    }
    boolean fresh=activeUtterance==null;activeUtterance=(mode==Mode.IDLE?"voice":"turn")+"-"+(++serial);
    int result=tts.speak(sentence,fresh?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,null,activeUtterance);
    if(result!=TextToSpeech.SUCCESS){
      activeUtterance=null;stopBarge(false);callback.status("语音播报不可用");
      if(sessionActive()&&!engineSpeaking()&&!runtime.busy){mode=Mode.LISTENING;listen();}
      return false;
    }
    spoken.append(sentence).append('\n');if(barge!=null)barge.spoken(sentence);
    if(sessionActive()){mode=Mode.SPEAKING;callback.voiceState(true,true);}
    return true;
  }
  private void ensureTts(){
    if(tts!=null)return;
    tts=new TextToSpeech(context,result->main.post(()->{
      ttsReady=result==TextToSpeech.SUCCESS;
      if(ttsReady&&tts!=null){
        String voice=PhoneSpeech.selectOfflineChineseVoice(tts);
        if(voice==null)tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        else android.util.Log.i("ZfoldSpeech","Local TTS voice: "+voice);
        tts.setSpeechRate(1.0f);tts.setPitch(1.05f);
      }
    }));
    tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
      public void onStart(String id){main.post(()->{
        if(!sessionActive()||activeUtterance==null||!activeUtterance.equals(id))return;
        mode=Mode.SPEAKING;callback.voiceState(true,true);
        callback.status(VoiceVadMonitor.enabled(context)?"正在播报 · 可直接说话打断":"正在播报 · 点打断后说话");
        startBarge();
      });}
      public void onDone(String id){main.post(()->finishSpeech(id,true));}
      public void onError(String id){main.post(()->finishSpeech(id,true));}
      @Override public void onStop(String id,boolean interrupted){main.post(()->finishSpeech(id,false));}
    });
  }
  private void finishSpeech(String id,boolean completed){
    if(activeUtterance==null||!activeUtterance.equals(id))return;
    activeUtterance=null;mode=Mode.LISTENING;callback.voiceState(true,false);
    if(bargeSpeech())return;
    stopBarge(false);int token=epoch;audioCooldownUntil=SystemClock.elapsedRealtime()+320;
    if(completed&&sessionActive())main.postDelayed(()->{if(token==epoch&&sessionActive()&&!runtime.busy&&!engineSpeaking()&&SystemClock.elapsedRealtime()>=audioCooldownUntil)listen();},320);
  }
  private boolean bargeSpeech(){return mode==Mode.SPEAKING&&barge!=null;}
  private void startBarge(){
    if(!VoiceVadMonitor.enabled(context)||barge!=null)return;
    barge=new VoiceBargeIn(context,new VoiceBargeIn.Callback(){
      public void speech(String partial){
        if(!sessionActive()||!speaking())return;
        speechSuppressed=true;
        activeUtterance=null;speechPending="";stopTts();mode=Mode.LISTENING;
        callback.voiceState(true,false);
        callback.status(partial.isEmpty()?"已识别到插话 · 正在听…":"正在听 · "+partial);
      }
      public void result(String text){
        VoiceBargeIn active=barge;barge=null;stopBarge(false);
        if(!sessionActive())return;mode=Mode.THINKING;callback.status("正在处理…");callback.prompt(text);
      }
      public void failed(String reason){
        VoiceBargeIn active=barge;barge=null;stopBarge(false);
        if(!sessionActive())return;
        if(engineSpeaking())callback.status("自动插话确认暂不可用 · 继续播报");
        else{mode=Mode.LISTENING;callback.status(reason+" · 点打断后说话");listen();}
        android.util.Log.w("ZfoldSpeech","Barge ASR unavailable: "+reason);
      }
    },useCloudAsr());
    barge.setSpoken(spoken.toString());barge.start();
  }
  private void stopBarge(boolean waitForResult){if(barge!=null){VoiceBargeIn active=barge;barge=null;active.stop(waitForResult);}}
  private void cleanupRecognizer(SpeechRecognizer recognizer){
    if(recognizer==null)return;recognizerReleasedAt=SystemClock.elapsedRealtime();
    main.post(()->{try{recognizer.destroy();}catch(Exception ignored){}});
  }
  private void listen(){
    if(!sessionActive()||mode==Mode.SPEAKING||asrChecking||(runtime.busy&&!taskVoice))return;
    long sinceRelease=SystemClock.elapsedRealtime()-recognizerReleasedAt;
    if(sinceRelease<500&&sinceRelease>=0){main.postDelayed(this::listen,500-sinceRelease);return;}
    mode=Mode.LISTENING;livePartial="";
    partialFinalToken=0;
    endpointFinalToken=0;
    endpointGate.reset();
    stopLiveAsr();
    if(useCloudAsr()){listenCloud();return;}
    if(recognizer==null){
      asrChecking=true;int token=epoch;callback.status("启动语音识别…");
      main.post(()->{
        if(token!=epoch)return;asrChecking=false;if(!sessionActive())return;
        recognizerLocal=!forceSystemAsr&&SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        asrLanguage=recognizerLocal?"cmn-Hans-CN":"zh-CN";
        if(!recognizerLocal&&!SpeechRecognizer.isRecognitionAvailable(context)){pause("系统语音识别服务不可用");return;}
        try{recognizer=PhoneSpeech.createRecognizer(context,recognizerLocal);}
        catch(RuntimeException error){
          if(recognizerLocal&&SpeechRecognizer.isRecognitionAvailable(context)){
            recognizerLocal=false;asrLanguage="zh-CN";forceSystemAsr=true;
            try{recognizer=PhoneSpeech.createRecognizer(context,false);}
            catch(RuntimeException again){pause("语音识别服务无法启动");return;}
          }else{pause("语音识别服务无法启动");return;}
        }
        SpeechRecognizer engine=recognizer;
        recognizer.setRecognitionListener(new RecognitionListener(){
          private boolean valid(){return token==epoch&&recognizer==engine&&sessionActive();}
          public void onReadyForSpeech(Bundle bundle){if(valid()){if(liveAsr==null)readyCue.microphoneReady();readyCue.backendReady();callback.status(recognizerLocal?"本机识别 · 正在听…":"系统识别 · 可能联网 · 正在听…");}}
          public void onBeginningOfSpeech(){}
          public void onRmsChanged(float value){}
          public void onBufferReceived(byte[] value){}
          public void onEndOfSpeech(){if(sessionActive())callback.status("正在识别…");}
          public void onError(int error){if(valid())handleAsrError(error);}
          public void onResults(Bundle bundle){
            if(!valid())return;
            partialFinalToken=0;
            endpointFinalToken=0;
            stopLiveAsr();
            ArrayList<String> values=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);emptySpeech=0;retries=0;
            String text=values==null||values.isEmpty()?"":values.get(0);
            if(text.isBlank())text=livePartial;
            text=initialTranscript(text,true);
            android.util.Log.i("ZfoldSpeech","ASR final count="+(values==null?-1:values.size())+" raw="+(values==null||values.isEmpty()?"":values.get(0))+" using="+text);
            SpeechRecognizer active=recognizer;recognizer=null;
            if(active!=null)active.cancel();
            cleanupRecognizer(active);
            if(!text.isBlank()&&usablePrompt(text)){mode=Mode.THINKING;taskVoice=false;callback.prompt(text);}
            else main.postDelayed(this::retryNoMatch,500);
          }
          private void retryNoMatch(){if(sessionActive()&&!engineSpeaking())listen();}
          public void onPartialResults(Bundle bundle){
            ArrayList<String> values=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if(valid()&&values!=null&&!values.isEmpty()){
              String value=initialTranscript(values.get(0),false);
              if(value.isBlank())return;
              if(!value.equals(livePartial))android.util.Log.i("ZfoldSpeech","ASR partial: "+value);
              livePartial=value;callback.status(value);
              endpointGate.setCandidate(value);
              armPartialFinalizer();
            }
          }
          public void onEvent(int code,Bundle bundle){}
        });
        listen();
      });
      return;
    }
    Intent request=PhoneSpeech.intent(asrLanguage,recognizerLocal);
    final long epochToken=epoch;
    java.util.function.DoubleConsumer level=value->{if(epochToken==epoch&&value>=0.035)lastLiveVoiceAt=SystemClock.elapsedRealtime();};
    VoiceAsrSource source=new VoiceAsrSource(context,level,endpointGate);
    java.util.function.Consumer<String> failed=reason->main.post(()->{
      if(sessionActive())callback.status("连续识别麦克风不可用，改用系统采集");
    });
    source.prefix(wakePrefixAudio);wakePrefixAudio=null;
    boolean started=source.start(request,failed);
    if(started){
      liveAsr=source;readyCue.microphoneReady();
    }else{
      request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,asrLanguage)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
      if(recognizerLocal)request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
    }
    listening=true;recognizer.startListening(request);
    android.util.Log.i("ZfoldSpeech","ASR startListening local="+recognizerLocal+" externalPcm="+(liveAsr==source));
  }
  private void handleAsrError(int error){
    stopLiveAsr();
    if(!sessionActive())return;
    if(recognizerLocal&&(error==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE||error==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)){
      if(recognizer!=null){recognizer.destroy();recognizer=null;}
      forceSystemAsr=true;callback.status("本机模型不可用，改用系统识别");main.postDelayed(this::listen,300);return;
    }
    if(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY||error==SpeechRecognizer.ERROR_SERVER||error==SpeechRecognizer.ERROR_CLIENT){
      if(++retries<5){
        if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;old.cancel();cleanupRecognizer(old);}
        callback.status("识别服务忙，正在重试…");main.postDelayed(this::listen,500+retries*250L);return;
      }
      pause("识别服务暂时不可用");return;
    }
    if(error==SpeechRecognizer.ERROR_AUDIO||error==SpeechRecognizer.ERROR_NETWORK_TIMEOUT||error==SpeechRecognizer.ERROR_NETWORK){
      if(++transientErrors<3){
        if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;old.cancel();cleanupRecognizer(old);}
        callback.status("识别管线释放中，正在重试…");main.postDelayed(this::listen,700);return;
      }
    }
    if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){pause("麦克风权限或系统录音授权受限");return;}
    if(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){
      String partial=livePartial;
      SpeechRecognizer old=recognizer;recognizer=null;if(old!=null)old.cancel();cleanupRecognizer(old);
      if(!partial.isBlank()&&usablePrompt(partial)){
        android.util.Log.i("ZfoldSpeech","ASR timeout accepted partial: "+partial);
        mode=Mode.THINKING;taskVoice=false;callback.prompt(partial);return;
      }
      if(++emptySpeech==1)callback.status("我在听，请说指令…");
      android.util.Log.i("ZfoldSpeech","ASR silence timeout; re-listen count="+emptySpeech);
      main.postDelayed(this::listen,650);
      return;
    }
    pause("语音识别暂时不可用（错误码 "+error+"）");
  }
  private void pause(String reason){
    android.util.Log.w("ZfoldSpeech","Voice session pause: "+reason+" mode="+mode+" retries="+retries+" transientErrors="+transientErrors+" emptySpeech="+emptySpeech);
    readyCue.cancel();wakePrefixAudio=null;stripInitialWake=false;epoch++;mode=Mode.PAUSED;activeUtterance=null;speechPending="";stopBarge(false);
    if(recognizer!=null)recognizer.cancel();
    stopTts();
    stopLiveAsr();
    callback.voiceState(false,false);callback.sessionEnded(reason);releaseLater();
  }
  private void ttsSpeakingReset(){/* Kept for call-site readability; activeUtterance is authoritative. */}
  private void stopLiveAsr(){
    VoiceAsrSource source=liveAsr;liveAsr=null;
    if(source!=null)source.stop();
    if(cloudAsr!=null){cloudAsr.cancel();cloudAsr=null;}
    listening=false;
    endpointGate.reset();readyCue.resetInput();
  }
  private void armPartialFinalizer(){
    long token=++partialFinalToken;
    lastLiveVoiceAt=SystemClock.elapsedRealtime();
    android.util.Log.i("ZfoldSpeech","ASR partial finalizer armed token="+token);
    main.postDelayed(()->finalizeLivePartial(token),1600);
  }
  private void handleEndpoint(VoiceEndpointGate.Event event){
    main.post(()->{
      if(!sessionActive()||mode!=Mode.LISTENING)return;
      if(event==VoiceEndpointGate.Event.STARTED){
        lastLiveVoiceAt=SystemClock.elapsedRealtime();
        if(livePartial.isBlank())callback.status("正在听…");
      }else if(event==VoiceEndpointGate.Event.UTTERANCE_ENDED){
        stopListeningForFinal("VAD endpoint");
      }else if(event==VoiceEndpointGate.Event.EMPTY_SPEECH_ENDED){
        stopListeningForFinal("VAD speech without ASR text");
      }
    });
  }
  private void stopListeningForFinal(String reason){
    if(cloudAsr!=null){
      VoiceAsrSource source=liveAsr;liveAsr=null;if(source!=null)source.stop();
      listening=false;cloudAsr.finish();callback.status("豆包识别 · 正在确认…");return;
    }
    long token=++endpointFinalToken;
    android.util.Log.i("ZfoldSpeech","ASR endpoint requested reason="+reason+" partial="+livePartial);
    SpeechRecognizer active=recognizer;
    if(active!=null){try{active.stopListening();}catch(Exception ignored){}}
    main.postDelayed(()->{
      if(token!=endpointFinalToken||!sessionActive()||mode!=Mode.LISTENING)return;
      endpointFinalToken=0;
      submitLivePartial(livePartial,"endpoint fallback");
    },900);
  }
  private void submitLivePartial(String value,String reason){
    if(!sessionActive()||mode!=Mode.LISTENING||!usablePrompt(value))return;
    endpointFinalToken=0;
    android.util.Log.i("ZfoldSpeech","ASR submit from "+reason+": "+value);
    epoch++;SpeechRecognizer active=recognizer;recognizer=null;
    if(active!=null)active.cancel();
    cleanupRecognizer(active);stopLiveAsr();
    mode=Mode.THINKING;taskVoice=false;callback.prompt(value);
  }
  private void finalizeLivePartial(long token){
    if(token!=partialFinalToken||!sessionActive()||mode!=Mode.LISTENING)return;
    String partial=livePartial;
    if(!usablePrompt(partial)){partialFinalToken=0;return;}
    long quietFor=SystemClock.elapsedRealtime()-lastLiveVoiceAt;
    if(quietFor<1200){
      main.postDelayed(()->finalizeLivePartial(token),250);
      return;
    }
    partialFinalToken=0;
    if(cloudAsr!=null){stopListeningForFinal("silence endpoint");return;}
    android.util.Log.i("ZfoldSpeech","ASR silence accepted partial: "+partial+" quietMs="+quietFor);
    submitLivePartial(partial,"silence fallback");
  }
  private static boolean usablePrompt(String value){
    String text=value==null?"":value.trim();
    return !text.isEmpty()&&text.chars().anyMatch(Character::isLetterOrDigit);
  }
  private void releaseLater(){
    int token=epoch;main.postDelayed(()->{
      if(token!=epoch||sessionActive())return;
      stopBarge(false);
      if(recognizer!=null){recognizer.destroy();recognizer=null;}
      if(tts!=null){tts.shutdown();tts=null;}ttsReady=false;listening=false;
      if(cloudTts!=null){cloudTts.close();cloudTts=null;}
    },5*60_000);
  }
  void voiceViewReady(boolean visible){readyCue.viewReady(visible&&sessionActive());}
  private String initialTranscript(String raw,boolean finished){String text=stripInitialWake?WakeConfig.get(context).stripPrefix(raw):raw;if(finished)stripInitialWake=false;return text;}
  private boolean useCloudAsr(){return speechConfig!=null&&speechConfig.cloudAsr&&!asrCloudFailed;}
  private boolean useCloudTts(){return speechConfig!=null&&speechConfig.cloudTts&&!ttsCloudFailed;}
  private boolean speechReady(){return useCloudTts()?runtime.ready:ttsReady&&tts!=null;}
  String providerStatus(){
    SpeechConfig value=sessionActive()?speechConfig:SpeechConfig.load(context);
    if(value==null)value=SpeechConfig.load(context);
    return "识别 · "+(value.cloudAsr?sessionActive()&&asrCloudFailed?"系统（豆包失败后回退）":"豆包 ASR 2.0":"系统")+"\n播报 · "+(value.cloudTts?sessionActive()&&ttsCloudFailed?"系统（豆包失败后回退）":"豆包 TTS 2.0":"系统");
  }
  private void stopTts(){if(tts!=null)tts.stop();if(cloudTts!=null)cloudTts.stop();cloudSegment=null;}
  private void finishCloudSegment(){if(cloudTts!=null&&cloudSegment!=null){cloudTts.finish(cloudSegment);cloudSegment=null;}}
  private CloudTtsPlayer cloudPlayer(){
    if(cloudTts==null)cloudTts=new CloudTtsPlayer(context,new CloudTtsPlayer.Callback(){
      public void start(String id){if(!sessionActive()||activeUtterance==null)return;mode=Mode.SPEAKING;callback.voiceState(true,true);callback.status("豆包播报 · 可点头像打断");startBarge();}
      public void done(String id){finishSpeech(id,true);}
      public void error(String id,String reason){
        if(!sessionActive())return;ttsCloudFailed=true;stopTts();activeUtterance=null;speechPending="";stopBarge(false);
        mode=runtime.busy?Mode.THINKING:Mode.LISTENING;callback.voiceState(true,false);callback.status("豆包播报不可用（"+reason+"），后续改用系统播报");
        if(!runtime.busy){int token=epoch;main.postDelayed(()->{if(token==epoch&&sessionActive())listen();},500);}
      }
    });return cloudTts;
  }
  private void listenCloud(){
    int token=epoch;mode=Mode.LISTENING;callback.status("连接豆包识别…");
    final CloudAsrClient[] holder=new CloudAsrClient[1];
    holder[0]=new CloudAsrClient(context,new CloudAsrClient.Callback(){
      private boolean valid(){return token==epoch&&sessionActive()&&cloudAsr==holder[0];}
      public void ready(){if(valid()){callback.status("豆包识别 · 在线 · 正在听…");readyCue.backendReady();}}
      public void partial(String text){if(!valid())return;text=initialTranscript(text,false);boolean changed=!text.equals(livePartial);livePartial=text;endpointGate.setCandidate(text);callback.status("豆包识别 · "+text);if(changed)armPartialFinalizer();}
      public void result(String text){
        if(!valid())return;text=initialTranscript(text,true);stopLiveAsr();livePartial="";
        if(usablePrompt(text)){mode=Mode.THINKING;taskVoice=false;callback.prompt(text);}
        else main.postDelayed(()->{if(token==epoch&&sessionActive())listen();},400);
      }
      public void error(String reason){
        if(!valid())return;stopLiveAsr();asrCloudFailed=true;livePartial="";
        callback.status("豆包识别不可用（"+reason+"），改用系统，请再说一次");
        main.postDelayed(()->{if(token==epoch&&sessionActive()&&!engineSpeaking())listen();},600);
      }
    });
    cloudAsr=holder[0];cloudAsr.start();if(cloudAsr!=holder[0])return;
    VoiceAsrSource source=new VoiceAsrSource(context,value->{if(token==epoch&&value>=.035)lastLiveVoiceAt=SystemClock.elapsedRealtime();},endpointGate);
    liveAsr=source;listening=true;
    source.prefix(wakePrefixAudio);wakePrefixAudio=null;
    if(source.startPcm(holder[0]::pcm,reason->main.post(()->{if(token==epoch&&cloudAsr==holder[0]){stopLiveAsr();pause(reason);}})))readyCue.microphoneReady();
  }
}
