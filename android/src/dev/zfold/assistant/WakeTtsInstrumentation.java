package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ADB-only probe: synthesize the wake phrase and verify KWS opens the assistant. */
public final class WakeTtsInstrumentation extends Instrumentation {
  private Bundle arguments;
  @Override public void onCreate(Bundle arguments){this.arguments=arguments;start();}
  @Override public void onStart(){
    boolean onlyWake=this.arguments!=null&&"only".equals(this.arguments.getString("mode","command"));
    String phrase=this.arguments==null||onlyWake?"小派":this.arguments.getString("phrase","小派，你能和我连续对话吗");
    String expectedCommand=this.arguments==null||onlyWake?"":this.arguments.getString("expectedCommand","你能和我连续对话吗");
    new Thread(()->{
      Bundle result=new Bundle();Context context=getTargetContext();final TextToSpeech[] engine={null};
      try{
        long probeStartedAt=VoiceWakeService.lastWakeTriggerAt;
        VoiceWakeService.setEnabled(context,true);
        boolean serviceReady=false;
        for(int i=0;i<100&&!serviceReady;i++){serviceReady=VoiceWakeService.active();Thread.sleep(100);}
        result.putBoolean("serviceReady",serviceReady);
        if(!serviceReady)throw new IllegalStateException("唤醒服务未进入监听");
        CountDownLatch ttsReady=new CountDownLatch(1);int[] status={TextToSpeech.ERROR};
        runOnMainSync(()->engine[0]=new TextToSpeech(context,code->{status[0]=code;ttsReady.countDown();}));
        if(!ttsReady.await(10,TimeUnit.SECONDS)||status[0]!=TextToSpeech.SUCCESS)throw new IllegalStateException("TTS 初始化失败");
        String voice=PhoneSpeech.selectOfflineChineseVoice(engine[0]);result.putString("voice",voice);
        java.util.concurrent.atomic.AtomicBoolean spokenDone=new java.util.concurrent.atomic.AtomicBoolean(false);
        engine[0].setOnUtteranceProgressListener(new UtteranceProgressListener(){
          @Override public void onStart(String id){}
          @Override public void onDone(String id){if("wake-probe".equals(id))spokenDone.set(true);}
          @Override public void onError(String id){if("wake-probe".equals(id))spokenDone.set(true);}
        });
        for(int attempt=0;attempt<5&&!AssistantOverlay.isShowing();attempt++){
          runOnMainSync(()->engine[0].speak(phrase,TextToSpeech.QUEUE_FLUSH,null,"wake-probe"));
          spokenDone.set(false);
          boolean finished=false;for(int wait=0;wait<80&&!finished;wait++){finished=spokenDone.get();if(!finished)Thread.sleep(100);}
          if(!finished)throw new IllegalStateException("TTS 播报超时");
          for(int i=0;i<30&&!AssistantOverlay.isShowing();i++)Thread.sleep(100);
          if(AssistantOverlay.isShowing())break;
          Thread.sleep(500);
        }
        boolean opened=false;boolean serviceActive=VoiceWakeService.active();
        for(int i=0;i<120&&!(opened=AssistantOverlay.isShowing());i++)Thread.sleep(100);
        boolean voiceActive=false;String voiceState="";
        if(opened){
          for(int i=0;i<80;i++){
            AssistantPanel panel=AssistantOverlay.currentPanel();
            if(panel!=null){boolean freshWake=VoiceWakeService.lastWakeTriggerAt>probeStartedAt;voiceActive=panel.voice.sessionActive();voiceState=panel.state.getText().toString();if(voiceActive&&freshWake)break;}
            Thread.sleep(100);
          }
        }
        boolean continuous=false;
        boolean freshWake=false;
        String prompt="";
        boolean commandAccepted=false;
        if(voiceActive){
          for(int i=0;i<250;i++){
            AssistantPanel panel=AssistantOverlay.currentPanel();
            if(panel!=null){
              freshWake=VoiceWakeService.lastWakeTriggerAt>probeStartedAt;
              voiceState=panel.state.getText().toString();
              prompt=panel.runtime.lastPromptText;
              commandAccepted=expectedCommand.isBlank()?prompt.isBlank():prompt.contains(expectedCommand);
              if(commandAccepted){continuous=true;break;}
              if(voiceState.contains("我在听"))continuous=true;
            }
            Thread.sleep(100);
          }
        }
        result.putBoolean("assistantOpened",opened);result.putBoolean("serviceActive",serviceActive);result.putBoolean("freshWake",freshWake);
        result.putBoolean("voiceActive",voiceActive);result.putString("voiceState",voiceState);
        result.putBoolean("continuous",continuous);
        result.putBoolean("commandAccepted",commandAccepted);
        AssistantPanel activePanel=AssistantOverlay.currentPanel();
        if(activePanel!=null){prompt=activePanel.runtime.lastPromptText;runOnMainSync(()->activePanel.voice.stopSession(false));}
        result.putString("prompt",prompt);
        finish(opened&&voiceActive&&freshWake&&continuous&&(expectedCommand.isBlank()||commandAccepted)?-1:0,result);
      }catch(Throwable error){
        result.putString("failure",error.toString());finish(0,result);
      }finally{
        if(engine[0]!=null)runOnMainSync(engine[0]::shutdown);
        VoiceWakeService.setEnabled(context,false);
      }
    },"zfold-wake-tts").start();
  }
}
