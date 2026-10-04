package dev.zfold.assistant;

import android.content.Intent;
import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.*;
import android.speech.*;
import android.util.Log;
import com.k2fsa.sherpa.onnx.*;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/** VAD proposes an interruption; ASR confirms it is not the phone's own TTS. */
final class VoiceBargeIn {
  interface Callback {
    void speech(String partial);
    void result(String text);
    void failed(String reason);
  }
  static final class Classified {final String text;final boolean echo;
    Classified(String text,boolean echo){this.text=text;this.echo=echo;}
  }
  private final Context context;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
  private final ArrayDeque<byte[]> prebuffer=new ArrayDeque<>();
  private final LinkedBlockingQueue<byte[]> stream=new LinkedBlockingQueue<>();
  private volatile boolean running,cancelled,candidate,recognized,delivered,resetCandidate;
  private volatile int streamEpoch;private volatile long candidateDeadline,echoCooldownUntil;
  private volatile AudioRecord recorder;private volatile String partial="",spoken="";
  private SpeechRecognizer recognizer;

  VoiceBargeIn(Context context,Callback callback){this.context=context.getApplicationContext();this.callback=callback;}
  void setSpoken(String value){synchronized(this){spoken=value==null?"":value;}}
  void spoken(String value){
    if(value==null||value.isBlank())return;
    synchronized(this){spoken=spoken.isBlank()?value:spoken+"\n"+value;if(spoken.length()>8000)spoken=spoken.substring(spoken.length()-8000);}
  }
  void start(){if(running)return;cancelled=false;running=true;new Thread(this::watch,"zfold-barge-in").start();}
  void stop(boolean waitForResult){
    boolean wasRunning=running;running=false;streamEpoch++;
    if(wasRunning&&!waitForResult)stream.clear();
    if(!wasRunning)return;
    if(waitForResult&&!cancelled)closeStream();else{cancelled=true;closeStream();}
    AudioRecord active=recorder;if(active!=null)try{active.stop();}catch(IllegalStateException ignored){}
    if(waitForResult&&recognized&&!cancelled)return;
    SpeechRecognizer activeRecognizer=recognizer;recognizer=null;
    if(activeRecognizer!=null)main.post(()->{try{activeRecognizer.cancel();activeRecognizer.destroy();}catch(Exception ignored){}});
  }
  private void closeStream(){stream.offer(new byte[0]);}
  private void watch(){
    AudioRecord audio=null;AcousticEchoCanceler echo=null;Vad vad=null;String failure=null;boolean speech=false;
    try{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      vad=new Vad(context.getAssets(),new VadModelConfig(new SileroVadModelConfig("models/silero_vad.int8.onnx",0.60f,0.38f,0.22f,512,10f),new TenVadModelConfig(),16000,1,"cpu",false));
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(minimum<=0)throw new IllegalStateException("无法建立 16 kHz 麦克风输入");
      audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
      if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("麦克风输入未就绪");
      recorder=audio;
      if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(audio.getAudioSessionId());if(echo!=null&&!echo.getEnabled())echo.setEnabled(true);}
      audio.startRecording();if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("麦克风没有开始录音");
      long started=SystemClock.elapsedRealtime();int speechFrames=0,silentFrames=0;short[] pcm=new short[512];float[] samples=new float[512];
      while(running&&!cancelled){
        if(resetCandidate){
          resetCandidate=false;candidate=false;recognized=false;delivered=false;partial="";speechFrames=0;silentFrames=0;
          echoCooldownUntil=SystemClock.elapsedRealtime()+1800;streamEpoch++;stream.clear();
          SpeechRecognizer activeRecognizer=recognizer;recognizer=null;
          if(activeRecognizer!=null)main.post(()->{try{activeRecognizer.cancel();activeRecognizer.destroy();}catch(Exception ignored){}});
          continue;
        }
        int size=0;while(size<pcm.length&&running&&!cancelled){int count=audio.read(pcm,size,pcm.length-size,AudioRecord.READ_BLOCKING);if(count<=0)throw new IllegalStateException("麦克风读取失败（"+count+"）");size+=count;}
        if(!running||cancelled)break;
        byte[] bytes=new byte[pcm.length*2];double power=0;
        for(int i=0;i<pcm.length;i++){float value=pcm[i]/32768f;samples[i]=value;power+=value*value;bytes[2*i]=(byte)(pcm[i]&0xff);bytes[2*i+1]=(byte)(pcm[i]>>8);}
        offer(prebuffer,bytes,62);double rms=Math.sqrt(power/pcm.length);float probability=vad.compute(samples);
        long now=SystemClock.elapsedRealtime();boolean active=now-started>250&&now>=echoCooldownUntil&&probability>=0.78f&&rms>=0.010f;
        if(active){speechFrames++;silentFrames=0;}else{speechFrames=Math.max(0,speechFrames-2);if(candidate)silentFrames++;}
        if(!candidate&&speechFrames>=7){
          candidate=true;silentFrames=0;candidateDeadline=now+12000;beginRecognition(new ArrayList<>(prebuffer));
          Log.i("ZfoldSpeech","Barge candidate started");
        }
        if(candidate&&(silentFrames>=30||now>candidateDeadline)){speech=true;break;}
      }
    }catch(Throwable error){failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    finally{
      AudioRecord active=recorder;recorder=null;
      if(echo!=null)echo.release();
      if(audio!=null){try{audio.stop();}catch(IllegalStateException ignored){}audio.release();}
      if(vad!=null)vad.release();
      running=false;
    }
    if(cancelled)return;
    if(speech)closeStream();
    else if(failure!=null){cancelled=true;closeStream();SpeechRecognizer activeRecognizer=recognizer;recognizer=null;String reason=failure;
      if(activeRecognizer!=null)main.post(()->{try{activeRecognizer.cancel();activeRecognizer.destroy();}catch(Exception ignored){}});
      main.post(()->callback.failed(reason));return;
    }else{cancelled=true;closeStream();SpeechRecognizer activeRecognizer=recognizer;recognizer=null;
      if(activeRecognizer!=null)main.post(()->{try{activeRecognizer.cancel();activeRecognizer.destroy();}catch(Exception ignored){}});return;}
  }
  private void offer(ArrayDeque<byte[]> queue,byte[] value,int limit){
    synchronized(queue){queue.addLast(value);while(queue.size()>limit)queue.removeFirst();}
  }
  private void beginRecognition(ArrayList<byte[]> history){
    try{
      int epoch=++streamEpoch;stream.clear();
      ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
      Intent request=PhoneSpeech.intent("cmn-Hans-CN",true);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0]);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
      main.post(()->{
        if(cancelled||!running||epoch!=streamEpoch){try{pipe[0].close();pipe[1].close();}catch(Exception ignored){}return;}
        try{
          recognizer=PhoneSpeech.createRecognizer(context,true);
          recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float value){}
            public void onBufferReceived(byte[] value){}
            public void onEndOfSpeech(){}
            public void onPartialResults(Bundle bundle){
              ArrayList<String> values=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values==null||values.isEmpty())return;
              Classified classified=classify(values.get(0));partial=classified.text;
              if(classified.echo){Log.i("ZfoldSpeech","Barge echo rejected: "+values.get(0));requestReject();}
              else deliverSpeech(classified.text);
            }
            public void onEvent(int code,Bundle bundle){}
            public void onError(int code){
              if(cancelled||epoch!=streamEpoch)return;SpeechRecognizer active=recognizer;recognizer=null;cleanup(active);
              if(delivered&&!partial.isBlank())callback.result(partial);else{Log.i("ZfoldSpeech","Barge ASR error treated as echo: "+code);requestReject();}
            }
            public void onResults(Bundle bundle){
              if(cancelled||epoch!=streamEpoch)return;SpeechRecognizer active=recognizer;recognizer=null;cleanup(active);
              ArrayList<String> values=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);String raw=values==null||values.isEmpty()?"":values.get(0);
              Classified classified=classify(raw);if(classified.text.isEmpty())classified=classify(partial);
              if(classified.echo){Log.i("ZfoldSpeech","Barge final echo rejected: "+raw);requestReject();return;}
              if(!classified.text.isEmpty())partial=classified.text;
              if(!delivered)deliverSpeech(classified.text);
              if(delivered)callback.result(partial);else requestReject();
            }
          });
          recognizer.startListening(request);recognized=true;
          Thread writer=new Thread(()->writePcm(pipe[1],history,epoch),"zfold-barge-asr");writer.setDaemon(true);writer.start();
        }catch(RuntimeException error){
          try{pipe[0].close();pipe[1].close();}catch(Exception ignored){}
          recognizer=null;requestReject();Log.w("ZfoldSpeech","Barge ASR unavailable",error);
        }
      });
    }catch(Exception error){requestReject();Log.w("ZfoldSpeech","Barge audio channel unavailable",error);}
  }
  private void deliverSpeech(String value){
    if(delivered||value==null||value.isBlank())return;delivered=true;partial=value;
    String shown=value;main.post(()->{if(!cancelled&&running)callback.speech(shown);});
  }
  private void requestReject(){resetCandidate=true;}
  private void writePcm(ParcelFileDescriptor descriptor,ArrayList<byte[]> history,int epoch){
    try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)){
      for(byte[] value:history)if(cancelled||epoch!=streamEpoch||output==null)return;else output.write(value);
      while(!cancelled&&epoch==streamEpoch){
        byte[] value=stream.take();if(value.length==0)break;
        output.write(value);
      }
    }catch(Exception ignored){}
  }
  private void cleanup(SpeechRecognizer active){
    if(active!=null)main.post(()->{try{active.destroy();}catch(Exception ignored){}});
  }
  Classified classify(String raw){
    String text=raw==null?"":raw.trim();String heard=normalize(text),expected=normalize(spoken);
    if(heard.isEmpty())return new Classified("",true);
    if(heard.length()<=4&&(heard.equals("停")||heard.equals("停止")||heard.equals("别说了")||heard.equals("闭嘴")||heard.equals("暂停")||heard.equals("继续")||heard.equals("取消")))return new Classified(text,false);
    if(expected.isEmpty())return new Classified(text,false);
    int common=commonPrefix(heard,expected);
    if(common>=3){
      String remainder=heard.substring(common);
      if(remainder.isEmpty())return new Classified("",true);
      return new Classified(remainder,false);
    }
    if(expected.contains(heard))return new Classified("",true);
    int limit=Math.max(3,Math.min(heard.length(),expected.length())/4);
    if(Math.min(heard.length(),expected.length())>=4&&levenshtein(heard,expected)<=limit)return new Classified("",true);
    return new Classified(text,false);
  }
  private static String normalize(String value){return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]","");}
  private static int commonPrefix(String left,String right){int end=Math.min(left.length(),right.length());for(int i=0;i<end;i++)if(left.charAt(i)!=right.charAt(i))return i;return end;}
  private static int levenshtein(String left,String right){
    int[] previous=new int[right.length()+1],current=new int[right.length()+1];
    for(int i=0;i<=right.length();i++)previous[i]=i;
    for(int i=1;i<=left.length();i++){current[0]=i;
      for(int j=1;j<=right.length();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(left.charAt(i-1)==right.charAt(j-1)?0:1));
      int[] swap=previous;previous=current;current=swap;
    }return previous[right.length()];
  }
}
