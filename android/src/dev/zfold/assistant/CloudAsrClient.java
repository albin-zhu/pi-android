package dev.zfold.assistant;

import android.content.Context;
import android.os.*;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.json.JSONObject;

/** One caller-owned stream; cancellation removes the listener before late events arrive. */
final class CloudAsrClient implements PiRuntime.Listener {
  interface Callback {void ready();void partial(String text);void result(String text);void error(String reason);}
  private final PiRuntime runtime;private final Callback callback;private final String client=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
  private final ByteArrayOutputStream pending=new ByteArrayOutputStream();private final Handler main=new Handler(Looper.getMainLooper());
  private volatile boolean stopped,ended;
  CloudAsrClient(Context context,Callback callback){runtime=PiRuntime.get(context);this.callback=callback;}
  void start(){runtime.listen(this);if(!runtime.ready){fail("本机运行时尚未就绪");return;}send("asr_start",null);}
  synchronized void pcm(byte[] bytes){if(stopped||ended)return;pending.write(bytes,0,bytes.length);if(pending.size()>=6400)flush(false);}
  synchronized void finish(){if(stopped||ended)return;ended=true;flush(true);main.postDelayed(()->{if(!stopped)fail("识别结果超时");},7500);}
  private void flush(boolean last){byte[] bytes=pending.toByteArray();pending.reset();send(last?"asr_finish":"asr_audio",Base64.encodeToString(bytes,Base64.NO_WRAP));}
  private void send(String action,String data){JSONObject command=PiRuntime.json("type","speech","action",action,"client",client,"id",id);try{if(data!=null)command.put("data",data);}catch(Exception ignored){}runtime.send(command);}
  synchronized void cancel(){if(stopped)return;stopped=true;pending.reset();runtime.unlisten(this);if(runtime.ready)send("asr_cancel",null);}
  private void fail(String reason){if(stopped)return;cancel();callback.error(reason);}
  public void event(JSONObject event){
    if(stopped||!"speech_event".equals(event.optString("type"))||!client.equals(event.optString("client"))||!id.equals(event.optString("id")))return;
    switch(event.optString("kind")){
      case "asr_ready":callback.ready();break;
      case "asr_partial":callback.partial(event.optString("text"));break;
      case "asr_final":String text=event.optString("text");cancel();callback.result(text);break;
      case "error":fail(SpeechConfig.reason(event.optString("code")));break;
    }
  }
}
