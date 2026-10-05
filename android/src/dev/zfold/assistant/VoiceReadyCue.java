package dev.zfold.assistant;

import android.media.*;
import android.os.*;

/** A single short local tone once both the voice view and input backend are ready. */
final class VoiceReadyCue {
  static final int RATE=24000,DURATION_MS=130;
  static volatile int completedPlays;
  private long id,lastPlayed;private boolean mic,backend,view;private volatile int generation;private volatile AudioTrack track;
  private final java.util.function.LongConsumer play;
  VoiceReadyCue(){this.play=token->playAudio();}
  VoiceReadyCue(java.util.function.LongConsumer play){this.play=play;}
  void arm(long event){cancel();id=event;}
  void microphoneReady(){mic=true;tryPlay();}
  void backendReady(){backend=true;tryPlay();}
  void resetInput(){mic=false;backend=false;}
  void viewReady(boolean visible){view=visible;tryPlay();}
  private void tryPlay(){if(id<=0||id==lastPlayed||!mic||!backend||!view)return;lastPlayed=id;play.accept(id);}
  boolean played(){return id>0&&id==lastPlayed;}
  void cancel(){generation++;id=0;mic=false;backend=false;view=false;AudioTrack old=track;track=null;if(old!=null)try{old.pause();old.flush();old.release();}catch(Exception ignored){}}
  static short[] samples(){int count=RATE*DURATION_MS/1000,ramp=RATE*12/1000;short[] samples=new short[count];for(int i=0;i<count;i++){double fade=Math.min(1,Math.min(i/(double)ramp,(count-1-i)/(double)ramp));samples[i]=(short)Math.round(32767*.14*fade*Math.sin(2*Math.PI*1100*i/RATE));}return samples;}
  private void playAudio(){int token=generation;
    Thread thread=new Thread(()->{AudioTrack audio=null;try{
      short[] samples=samples();audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length*2).build();
      audio.write(samples,0,samples.length);if(token!=generation)return;track=audio;audio.play();long deadline=SystemClock.elapsedRealtime()+1000;
      while(token==generation&&(audio.getPlaybackHeadPosition()&0xffffffffL)<samples.length&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
      if(token==generation&&(audio.getPlaybackHeadPosition()&0xffffffffL)>=samples.length)completedPlays++;
    }catch(Exception error){android.util.Log.w("ZfoldWake","Ready tone unavailable");}finally{if(audio!=null){if(track==audio)track=null;try{audio.release();}catch(Exception ignored){}}}},"chengcheng-ready-cue");thread.setDaemon(true);thread.start();
  }
}
