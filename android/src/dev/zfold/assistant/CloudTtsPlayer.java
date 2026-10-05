package dev.zfold.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Streaming AudioTrack playback owns its queue; stop is local and does not wait for the network. */
final class CloudTtsPlayer implements PiRuntime.Listener {
  interface Callback {void start(String id);void done(String id);void error(String id,String reason);}
  private static final class Chunk {final String id;final byte[] bytes;final boolean end;Chunk(String id,byte[] bytes,boolean end){this.id=id;this.bytes=bytes;this.end=end;}}
  private final PiRuntime runtime;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
  private String client=UUID.randomUUID().toString();private volatile int generation;private volatile AudioTrack track;
  private LinkedBlockingQueue<Chunk> queue=new LinkedBlockingQueue<>(512);private AtomicInteger buffered=new AtomicInteger();private Thread worker;
  CloudTtsPlayer(Context context,Callback callback){runtime=PiRuntime.get(context);this.callback=callback;runtime.listen(this);}
  void begin(String id){if(!runtime.ready){callback.error(id,"本机运行时尚未就绪");return;}if(worker==null){int token=generation;LinkedBlockingQueue<Chunk> source=queue;AtomicInteger count=buffered;worker=new Thread(()->play(source,count,token),"chengcheng-cloud-tts");worker.setDaemon(true);worker.start();}send("tts_start",id,null);}
  void text(String id,String text){send("tts_text",id,text);}
  void finish(String id){send("tts_finish",id,null);}
  private void send(String action,String id,String text){JSONObject command=PiRuntime.json("type","speech","client",client,"id",id,"action",action);try{if(text!=null)command.put("text",text);}catch(Exception ignored){}runtime.send(command);}
  void stop(){
    generation++;if(runtime.ready)send("tts_stop","",null);client=UUID.randomUUID().toString();
    LinkedBlockingQueue<Chunk> old=queue;queue=new LinkedBlockingQueue<>(512);buffered=new AtomicInteger();old.clear();
    AudioTrack active=track;track=null;if(active!=null){try{active.pause();active.flush();}catch(Exception ignored){}try{active.release();}catch(Exception ignored){}}
    Thread thread=worker;worker=null;if(thread!=null)thread.interrupt();
  }
  void close(){stop();runtime.unlisten(this);}
  public void event(JSONObject event){
    if(!"speech_event".equals(event.optString("type"))||!client.equals(event.optString("client")))return;
    String id=event.optString("id"),kind=event.optString("kind");
    if(kind.equals("error")){callback.error(id,SpeechConfig.reason(event.optString("code")));return;}
    try{
      if(kind.equals("tts_audio")){
        byte[] bytes=Base64.decode(event.optString("data"),Base64.DEFAULT);
        if(bytes.length%2!=0||buffered.addAndGet(bytes.length)>24000*2*32||!queue.offer(new Chunk(id,bytes,false)))throw new IllegalStateException();
      }else if(kind.equals("tts_done")&&!queue.offer(new Chunk(id,new byte[0],true)))throw new IllegalStateException();
    }catch(Exception error){stop();callback.error(id,"播放缓冲已满或音频格式无效");}
  }
  private void play(LinkedBlockingQueue<Chunk> source,AtomicInteger count,int token){
    AudioTrack audio=null;String current="";long frames=0;
    try{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      while(token==generation){
        Chunk chunk=source.take();count.addAndGet(-chunk.bytes.length);if(token!=generation)break;
        if(!chunk.id.equals(current)){
          if(audio!=null)audio.release();current=chunk.id;frames=0;
          int minimum=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
          audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(minimum,4800)).setTransferMode(AudioTrack.MODE_STREAM).build();
          if(token!=generation){audio.release();audio=null;break;}track=audio;audio.play();String id=current;main.post(()->{if(token==generation)callback.start(id);});
        }
        int offset=0;while(offset<chunk.bytes.length&&token==generation){int n=audio.write(chunk.bytes,offset,chunk.bytes.length-offset,AudioTrack.WRITE_BLOCKING);if(n<=0)throw new IllegalStateException();offset+=n;frames+=n/2;}
        if(chunk.end){
          long deadline=SystemClock.elapsedRealtime()+3000+Math.max(0,frames-(audio.getPlaybackHeadPosition()&0xffffffffL))*1000/24000;
          while(token==generation&&(audio.getPlaybackHeadPosition()&0xffffffffL)<frames){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException();Thread.sleep(15);}
          String id=current;main.post(()->{if(token==generation)callback.done(id);});
          audio.release();if(track==audio)track=null;audio=null;current="";
        }
      }
    }catch(InterruptedException ignored){}
    catch(Exception error){String id=current;main.post(()->{if(token==generation){stop();callback.error(id,"系统音频播放不可用");}});}
    finally{if(audio!=null){if(track==audio)track=null;try{audio.release();}catch(Exception ignored){}}}
  }
}
