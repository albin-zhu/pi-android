package dev.zfold.assistant;

import android.content.Context;
import android.os.*;
import java.util.*;
import org.json.*;

/** Freezes each user request before queueing, including its one camera frame. */
final class PromptDispatcher implements PiRuntime.Listener {
  interface Result {void done(boolean accepted,boolean queued,String error);}
  private static PromptDispatcher instance;
  static synchronized PromptDispatcher get(Context context){if(instance==null)instance=new PromptDispatcher(context.getApplicationContext());return instance;}
  static void cancelPending(){if(instance!=null){instance.epoch++;instance.queue.clear();instance.changed();}}
  private final Context context;private final PiRuntime runtime;private final Handler main=new Handler(Looper.getMainLooper());
  private final ArrayDeque<JSONObject> queue=new ArrayDeque<>();private int epoch;
  private PromptDispatcher(Context context){this.context=context;runtime=PiRuntime.get(context);runtime.listen(this);}
  void submit(JSONObject command,boolean useCamera,Result callback){
    if(!runtime.configured){callback.done(false,false,"请先连接模型");return;}
    if(queue.size()>=8){callback.done(false,false,"待处理请求已满，请等待当前任务完成");return;}
    String sessionId=command.optString("sessionId",runtime.sessionId);int token=epoch;
    if(!sessionId.equals(runtime.sessionId)){callback.done(false,false,"会话已切换，请重新提交");return;}
    try{command.put("sessionId",sessionId);}catch(JSONException impossible){throw new IllegalArgumentException(impossible);}
    CameraCoordinator camera=CameraCoordinator.get(context);
    if(useCamera&&camera.enabledFor(sessionId)){
      if(!runtime.imageInput){callback.done(false,false,"当前模型不支持图片，可更换模型或选择仅发文字");return;}
      camera.capture(command.optString("id"),(frame,error)->{
        if(error!=null){callback.done(false,false,error);return;}
        try{JSONArray images=command.optJSONArray("images");if(images==null)images=new JSONArray();images.put(frame.attachment());command.put("images",images);command.put("cameraFrame",frame.metadata());}
        catch(JSONException impossible){callback.done(false,false,impossible.getMessage());return;}
        enqueue(command,token,callback);
      });
    }else enqueue(command,token,callback);
  }
  java.util.List<JSONObject> pending(){
    java.util.List<JSONObject> result=new ArrayList<>();
    for(JSONObject item:queue)try{result.add(new JSONObject(item.toString()));}catch(JSONException ignored){}
    return result;
  }
  boolean cancel(String id){boolean removed=queue.removeIf(item->id.equals(item.optString("id")));if(removed)changed();return removed;}
  boolean edit(String id,String text){
    if(text==null||text.trim().isEmpty()||text.length()>32000)return false;
    for(JSONObject item:queue)if(id.equals(item.optString("id"))){try{item.put("text",text.trim());changed();return true;}catch(JSONException ignored){return false;}}
    return false;
  }
  private final java.util.Set<Runnable> observers=new java.util.HashSet<>();
  void listenQueue(Runnable listener){observers.add(listener);}
  void unlistenQueue(Runnable listener){observers.remove(listener);}
  private void changed(){for(Runnable listener:new ArrayList<>(observers))listener.run();}
  private void enqueue(JSONObject command,int token,Result callback){
    if(token!=epoch||!runtime.sessionId.equals(command.optString("sessionId"))){callback.done(false,false,"会话已切换或请求已取消");return;}
    if(queue.size()>=8){callback.done(false,false,"待处理请求已满");return;}
    boolean queued=runtime.busy;
    if(queued){queue.add(command);changed();}else send(command);
    callback.done(true,queued,null);
  }
  private void send(JSONObject command){
    if(!runtime.sessionId.equals(command.optString("sessionId")))return;
    VoiceTurnController voice=VoiceCoordinator.current();if(voice!=null)voice.beginPrompt();
    VoiceCallActivity.onFrameSubmitted(command.optJSONObject("cameraFrame"),command.optJSONArray("images"));runtime.send(command);
  }
  public void event(JSONObject event){
    String type=event.optString("type");
    if(type.equals("session_changed")){cancelPending();CameraCoordinator.get(context).disable();return;}
    // A failed active request must not discard separately accepted user requests.
    if(type.equals("turn_end")||type.equals("error"))main.post(()->{if(!runtime.busy&&!queue.isEmpty()){JSONObject next=queue.remove();changed();send(next);}});
  }
  static boolean isStop(String value){String text=value.toLowerCase(Locale.ROOT).replaceAll("[\\s，,。：:！!？?]+","");return Arrays.asList("停","停止","停下","取消","别做了","请停止","停止任务","停止当前任务").contains(text);}
}
