package dev.zfold.assistant;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.speech.RecognizerIntent;
import com.k2fsa.sherpa.onnx.*;

import java.io.OutputStream;

/** Captures one 16 kHz PCM stream and feeds Android's external-audio ASR. */
final class VoiceAsrSource {
  private final Context context;
  private AudioRecord audio;private Thread writer;private volatile boolean running;
  private final java.util.function.DoubleConsumer level;
  private final VoiceEndpointGate endpoint;
  private java.util.function.Consumer<String> cloudFailure;

  VoiceAsrSource(Context context,java.util.function.DoubleConsumer level,VoiceEndpointGate endpoint){
    this.context=context.getApplicationContext();this.level=level;this.endpoint=endpoint;
  }

  boolean start(Intent request,java.util.function.Consumer<String> failed){
    ParcelFileDescriptor[] pipe=null;
    try{
      pipe=ParcelFileDescriptor.createPipe();
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0]);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(minimum<=0)throw new IllegalStateException("无法建立连续识别麦克风");
      AudioRecord record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
      if(record.getState()!=AudioRecord.STATE_INITIALIZED){record.release();throw new IllegalStateException("连续识别麦克风未就绪");}
      audio=record;running=true;record.startRecording();
      if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("连续识别麦克风没有开始");
      ParcelFileDescriptor output=pipe[1];int sourceId=record.getAudioSessionId();
      writer=new Thread(()->write(new ParcelFileDescriptor.AutoCloseOutputStream(output),sourceId),"zfold-live-asr");writer.setDaemon(true);writer.start();
      return true;
    }catch(Throwable error){
      running=false;
      if(pipe!=null){try{pipe[0].close();}catch(Exception ignored){}try{pipe[1].close();}catch(Exception ignored){}}
      stop();
      failed.accept(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());
      return false;
    }
  }

  boolean startPcm(java.util.function.Consumer<byte[]> sink,java.util.function.Consumer<String> failed){
    try{
      int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      AudioRecord record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192));
      if(record.getState()!=AudioRecord.STATE_INITIALIZED){record.release();throw new IllegalStateException();}
      audio=record;running=true;cloudFailure=failed;record.startRecording();
      if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException();
      OutputStream output=new OutputStream(){public void write(int value){}public void write(byte[] bytes,int offset,int length){sink.accept(java.util.Arrays.copyOfRange(bytes,offset,offset+length));}};
      int sourceId=record.getAudioSessionId();writer=new Thread(()->write(output,sourceId),"chengcheng-cloud-mic");writer.setDaemon(true);writer.start();return true;
    }catch(Exception error){stop();failed.accept("麦克风不可用");return false;}
  }

  synchronized void stop(){
    running=false;
    AudioRecord record=audio;audio=null;
    if(record!=null){try{record.stop();}catch(IllegalStateException ignored){}record.release();}
    Thread active=writer;writer=null;
    if(active!=null)active.interrupt();
  }

  private synchronized boolean running(){return running;}
  private synchronized AudioRecord record(){return audio;}

  private void write(OutputStream destination,int sessionId){
    Vad vad=null;
    try{
      vad=new Vad(context.getAssets(),new VadModelConfig(new SileroVadModelConfig("models/silero_vad.int8.onnx",0.55f,0.35f,0.20f,512,10f),new TenVadModelConfig(),16000,1,"cpu",false));
    }catch(Throwable error){
      android.util.Log.w("ZfoldSpeech","Listening VAD unavailable; falling back to RMS endpointing",error);
      vad=null;
    }
    try(OutputStream output=destination){
      short[] pcm=new short[512];float[] samples=new float[512];int debugFrames=0;double debugMaxRms=0;
      while(running()){
        AudioRecord record=record();if(record==null)return;
        int size=0;while(size<pcm.length&&running()){
          int count=record.read(pcm,size,pcm.length-size,AudioRecord.READ_BLOCKING);
          if(count<=0)throw new IllegalStateException("连续识别麦克风读取失败（"+count+"）");
          size+=count;
        }
        if(!running())return;
        double power=0;
        for(int i=0;i<size;i++){float value=pcm[i]/32768f;samples[i]=value;power+=value*value;}
        double rms=Math.sqrt(power/Math.max(1,size));debugMaxRms=Math.max(debugMaxRms,rms);
        if(vad!=null&&endpoint!=null){
          float probability=vad.compute(samples);
          VoiceEndpointGate.Event event=endpoint.feed(probability,rms,android.os.SystemClock.elapsedRealtime());
          if(event!=VoiceEndpointGate.Event.NONE)android.util.Log.i("ZfoldSpeech","Listening endpoint "+event+" p="+String.format("%.3f",probability)+" rms="+String.format("%.4f",rms));
          if(event!=VoiceEndpointGate.Event.NONE)endpoint.listener().endpoint(event);
        }
        if(++debugFrames>=16){
          level.accept(debugMaxRms);
          android.util.Log.i("ZfoldSpeech","Live ASR maxRms="+String.format("%.4f",debugMaxRms)+" session="+sessionId);
          debugFrames=0;debugMaxRms=0;
        }
        byte[] bytes=new byte[size*2];
        for(int i=0;i<size;i++){bytes[2*i]=(byte)(pcm[i]&0xff);bytes[2*i+1]=(byte)(pcm[i]>>8);}
        output.write(bytes);
        output.flush();
      }
    }catch(Exception error){if(running&&cloudFailure!=null)cloudFailure.accept("麦克风读取中断");}
    finally{if(vad!=null)vad.release();}
  }
}
