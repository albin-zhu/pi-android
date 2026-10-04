package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.*;
import android.speech.tts.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plays one local TTS sentence while VAD listens; no mic samples are retained. */
public final class EchoProbeInstrumentation extends Instrumentation {
  Bundle options;
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);options=arguments;start();}
  @Override public void onStart(){
    Bundle result=new Bundle();Context context=getTargetContext();TextToSpeech[] engine={null};VoiceVadMonitor[] monitor={null};int code=0;
    try{
      CountDownLatch ttsReady=new CountDownLatch(1),captureReady=new CountDownLatch(1),spoken=new CountDownLatch(1);
      int[] init={TextToSpeech.ERROR},rate={0},format={0},channels={0};boolean[] aec={false};AtomicBoolean echoDetected=new AtomicBoolean();String[] captureError={null};ByteArrayOutputStream output=new ByteArrayOutputStream();
      runOnMainSync(()->engine[0]=new TextToSpeech(context,status->{init[0]=status;ttsReady.countDown();}));
      if(!ttsReady.await(10,TimeUnit.SECONDS)||init[0]!=TextToSpeech.SUCCESS)throw new AssertionError("TTS did not initialize");
      runOnMainSync(()->{
        String voice=PhoneSpeech.selectOfflineChineseVoice(engine[0]);result.putString("offlineVoice",String.valueOf(voice));
        engine[0].setOnUtteranceProgressListener(new UtteranceProgressListener(){
          public void onStart(String id){}public void onDone(String id){spoken.countDown();}public void onError(String id){spoken.countDown();}
          @Override public void onBeginSynthesis(String id,int sampleRate,int audioFormat,int channelCount){rate[0]=sampleRate;format[0]=audioFormat;channels[0]=channelCount;}
          @Override public void onAudioAvailable(String id,byte[] audio){synchronized(output){output.write(audio,0,audio.length);}}
        });
      });
      monitor[0]=new VoiceVadMonitor(context,new VoiceVadMonitor.Callback(){
        public void started(boolean enabled){aec[0]=enabled;captureReady.countDown();}
        public void speech(){echoDetected.set(true);}
        public void failed(String reason){captureError[0]=reason;captureReady.countDown();}
      });
      runOnMainSync(monitor[0]::start);
      if(!captureReady.await(8,TimeUnit.SECONDS)||captureError[0]!=null)throw new AssertionError("VAD capture failed: "+captureError[0]);
      runOnMainSync(()->engine[0].speak("这是本机语音播报的回声测试。",TextToSpeech.QUEUE_FLUSH,null,"echo-probe"));
      if(!spoken.await(15,TimeUnit.SECONDS))throw new AssertionError("TTS playback timed out");
      Thread.sleep(350);result.putBoolean("aecEnabled",aec[0]);result.putBoolean("vadTriggeredByOwnTts",echoDetected.get());
      result.putString("synthFormat",rate[0]+"Hz / "+channels[0]+"ch / format "+format[0]);
      if(rate[0]>0&&format[0]==AudioFormat.ENCODING_PCM_16BIT&&channels[0]==1){
        byte[] bytes; synchronized(output){bytes=output.toByteArray();}
        float[] source=new float[bytes.length/2];for(int i=0;i<source.length;i++){int low=bytes[2*i]&255,high=bytes[2*i+1];source[i]=(short)((high<<8)|low)/32768f;}
        Vad model=new Vad(context.getAssets(),new VadModelConfig(new SileroVadModelConfig("models/silero_vad.int8.onnx",0.55f,0.35f,0.20f,512,10f),new TenVadModelConfig(),16000,1,"cpu",false));
        float peak=0;int above=0;try{float[] frame=new float[512];int frames=(int)(source.length*16000.0/rate[0])/512;for(int n=0;n<frames;n++){for(int i=0;i<512;i++){int at=Math.min(source.length-1,(int)((n*512L+i)*rate[0]/16000L));frame[i]=source[at];}float probability=model.compute(frame);peak=Math.max(peak,probability);if(probability>=0.82f)above++;}}finally{model.release();}
        result.putString("directSpeechPeak",String.valueOf(peak));result.putInt("directSpeechFramesAboveThreshold",above);
        if("true".equals(options.getString("feed")))testExternalAsr(context,bytes,rate[0],result);
      }
      code=-1;
    }catch(Throwable error){result.putString("error",error.toString());}
    finally{if(monitor[0]!=null)runOnMainSync(monitor[0]::stop);if(engine[0]!=null)runOnMainSync(()->{engine[0].stop();engine[0].shutdown();});}
    finish(code,result);
  }
  void testExternalAsr(Context context,byte[] pcm,int rate,Bundle result)throws Exception{
    ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();SpeechRecognizer[] recognizer={null};CountDownLatch done=new CountDownLatch(1);String[] transcript={null},error={null},partial={null};boolean[] began={false};
    Intent request=PhoneSpeech.intent("cmn-Hans-CN",true);
    request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0]);
    request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
    request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
    request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
    runOnMainSync(()->{
      recognizer[0]=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
      recognizer[0].setRecognitionListener(new RecognitionListener(){
        public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){began[0]=true;}public void onRmsChanged(float r){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}
        public void onError(int code){error[0]=String.valueOf(code);done.countDown();}
        public void onResults(Bundle b){java.util.ArrayList<String> words=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);transcript[0]=words==null||words.isEmpty()?"":words.get(0);done.countDown();}
        public void onPartialResults(Bundle b){java.util.ArrayList<String> words=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(words!=null&&!words.isEmpty())partial[0]=words.get(0);}public void onEvent(int code,Bundle b){}
      });recognizer[0].startListening(request);
    });
    Thread writer=new Thread(()->{try(java.io.OutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
      int outputSamples=(int)(pcm.length/2.0*16000/rate);byte[] resampled=new byte[outputSamples*2];
      for(int i=0;i<outputSamples;i++){int at=Math.min(pcm.length/2-1,(int)(i*(long)rate/16000))*2;resampled[2*i]=pcm[at];resampled[2*i+1]=pcm[at+1];}
      stream.write(new byte[16000]);int chunk=1280;for(int offset=0;offset<resampled.length;offset+=chunk){int count=Math.min(chunk,resampled.length-offset);stream.write(resampled,offset,count);Thread.sleep(Math.max(1,count*1000L/32000));}
      stream.write(new byte[24000]);
    }catch(Exception ignored){}});writer.setDaemon(true);writer.start();
    boolean completed=done.await(15,TimeUnit.SECONDS);runOnMainSync(recognizer[0]::destroy);pipe[0].close();writer.join(1000);
    result.putString("externalAsrText",String.valueOf(transcript[0]));result.putString("externalAsrPartial",String.valueOf(partial[0]));result.putBoolean("externalAsrSpeechBegan",began[0]);result.putString("externalAsrError",completed?String.valueOf(error[0]):"timeout");
  }
}
