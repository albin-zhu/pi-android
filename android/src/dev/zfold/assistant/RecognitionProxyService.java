package dev.zfold.assistant;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.*;
import android.speech.*;

/** Delegates to an installed system recognizer; does not claim an offline ASR model. */
public final class RecognitionProxyService extends RecognitionService {
  private SpeechRecognizer recognizer;
  interface Delivery{void send()throws RemoteException;}
  void deliver(Delivery action){try{action.send();}catch(RemoteException ignored){}}
  @Override protected void onStartListening(Intent intent,Callback callback){
    ComponentName engine=null;
    for(ResolveInfo service:getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE),0)){
      if(!service.serviceInfo.packageName.equals(getPackageName())){engine=new ComponentName(service.serviceInfo.packageName,service.serviceInfo.name);if(service.serviceInfo.packageName.startsWith("com.google."))break;}
    }
    if(engine==null){deliver(()->callback.error(SpeechRecognizer.ERROR_CLIENT));return;}
    if(recognizer!=null)recognizer.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this,engine);
    recognizer.setRecognitionListener(new RecognitionListener(){
      public void onReadyForSpeech(Bundle b){deliver(()->callback.readyForSpeech(b));}
      public void onBeginningOfSpeech(){deliver(()->callback.beginningOfSpeech());}
      public void onRmsChanged(float r){deliver(()->callback.rmsChanged(r));}
      public void onBufferReceived(byte[] b){deliver(()->callback.bufferReceived(b));}
      public void onEndOfSpeech(){deliver(()->callback.endOfSpeech());}
      public void onError(int e){deliver(()->callback.error(e));}
      public void onResults(Bundle b){deliver(()->callback.results(b));}
      public void onPartialResults(Bundle b){deliver(()->callback.partialResults(b));}
      public void onEvent(int e,Bundle b){}
    });recognizer.startListening(intent);
  }
  @Override protected void onStopListening(Callback callback){if(recognizer!=null)recognizer.stopListening();}
  @Override protected void onCancel(Callback callback){if(recognizer!=null)recognizer.cancel();}
  @Override public void onDestroy(){if(recognizer!=null)recognizer.destroy();super.onDestroy();}
}
