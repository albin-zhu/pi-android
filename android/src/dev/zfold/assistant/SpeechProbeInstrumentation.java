package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.*;
import android.speech.tts.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only ADB probe for speech providers and downloaded on-device voices. */
public final class SpeechProbeInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
  @Override public void onStart(){
    Bundle result=new Bundle();Context context=getTargetContext();
    try{
      boolean local=android.os.Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
      result.putBoolean("onDeviceAsrService",local);
      if(local&&android.os.Build.VERSION.SDK_INT>=33){
        CountDownLatch ready=new CountDownLatch(1);SpeechRecognizer[] recognizer=new SpeechRecognizer[1];
        Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        runOnMainSync(()->{
          recognizer[0]=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
          recognizer[0].checkRecognitionSupport(request,context.getMainExecutor(),new RecognitionSupportCallback(){
            public void onSupportResult(RecognitionSupport support){result.putString("asrInstalled",support.getInstalledOnDeviceLanguages().toString());result.putString("asrDownloadable",support.getSupportedOnDeviceLanguages().toString());result.putString("asrPending",support.getPendingOnDeviceLanguages().toString());ready.countDown();}
            public void onError(int code){result.putString("asrSupportError",String.valueOf(code));ready.countDown();}
          });
        });
        if(!ready.await(12,TimeUnit.SECONDS))result.putString("asrSupportError","timeout");
        runOnMainSync(()->{if(recognizer[0]!=null)recognizer[0].destroy();});
      }
      CountDownLatch appReady=new CountDownLatch(1);
      runOnMainSync(()->PhoneSpeech.inspectMandarin(context,support->{result.putBoolean("zfoldWillUseLocalAsr",support.installed);result.putString("zfoldAsrLanguage",String.valueOf(support.language));appReady.countDown();}));
      if(!appReady.await(10,TimeUnit.SECONDS))result.putString("zfoldAsrCheck","timeout");
      inspectTts(context,"com.google.android.tts","google",result);
      inspectTts(context,"com.samsung.SMT","samsung",result);
      finish(-1,result);
    }catch(Throwable error){result.putString("error",error.toString());finish(0,result);}
  }

  void inspectTts(Context context,String engine,String label,Bundle result)throws Exception{
    CountDownLatch ready=new CountDownLatch(1);TextToSpeech[] synthesizer=new TextToSpeech[1];int[] status={TextToSpeech.ERROR};
    runOnMainSync(()->synthesizer[0]=new TextToSpeech(context,code->{status[0]=code;ready.countDown();},engine));
    if(!ready.await(12,TimeUnit.SECONDS)){result.putString(label+"Tts","timeout");return;}
    if(status[0]!=TextToSpeech.SUCCESS){result.putString(label+"Tts","init error "+status[0]);return;}
    runOnMainSync(()->{
      Set<Voice> voices=synthesizer[0].getVoices();ArrayList<String> offlineChinese=new ArrayList<>(),installedChinese=new ArrayList<>();
      if(voices!=null)for(Voice voice:voices)if("zh".equals(voice.getLocale().getLanguage())&&!voice.isNetworkConnectionRequired()){
        offlineChinese.add(voice.getName());if(!voice.getFeatures().contains("notInstalled"))installedChinese.add(voice.getName());
      }
      result.putString(label+"RequestedEngine",engine);
      result.putString(label+"OfflineChineseVoices",offlineChinese.toString());
      result.putString(label+"InstalledOfflineChineseVoices",installedChinese.toString());
      result.putString(label+"SelectedOfflineVoice",String.valueOf(PhoneSpeech.selectOfflineChineseVoice(synthesizer[0])));
      result.putString(label+"DefaultVoice",String.valueOf(synthesizer[0].getDefaultVoice()));
      synthesizer[0].shutdown();
    });
  }
}
