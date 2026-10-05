package dev.zfold.assistant;

import android.content.Context;
import java.util.*;
import org.json.JSONObject;

/** Process-owned speech engine; Activities and legacy views only observe it. */
final class VoiceCoordinator implements PiRuntime.Listener {
  private static VoiceCoordinator instance;
  static synchronized VoiceCoordinator get(Context context){if(instance==null)instance=new VoiceCoordinator(context.getApplicationContext());return instance;}
  static VoiceTurnController current(){return instance==null?null:instance.controller;}
  private final Context context;private final PiRuntime runtime;
  final VoiceTurnController controller;
  private final Map<Object,VoiceTurnController.Callback> observers=new LinkedHashMap<>();
  private boolean session,speaking;private String pendingVoice="",lastStatus="";
  private VoiceCoordinator(Context context){
    this.context=context;runtime=PiRuntime.get(context);
    controller=new VoiceTurnController(context,new VoiceTurnController.Callback(){
      public boolean active(){return session;}
      public void status(String text){lastStatus=text;VoiceCallActivity.onControllerStatus(text);for(VoiceTurnController.Callback view:views())view.status(text);}
      public void voiceState(boolean active,boolean talking){session=active;speaking=talking;if(!active)CameraCoordinator.disableVideo();VoiceCallActivity.onControllerState(active,talking);for(VoiceTurnController.Callback view:views())view.voiceState(active,talking);}
      public void prompt(String text){submit(text,true);}
      public void sessionEnded(String reason){session=false;speaking=false;CameraCoordinator.disableVideo();VoiceCallActivity.onSessionEnded(reason);for(VoiceTurnController.Callback view:views())view.sessionEnded(reason);}
    });
    runtime.listen(this);
  }
  VoiceTurnController observe(Object owner,VoiceTurnController.Callback observer){observers.put(owner,observer);return controller;}
  void forget(Object owner){observers.remove(owner);}
  private List<VoiceTurnController.Callback> views(){return new ArrayList<>(observers.values());}
  String pendingVoice(){return pendingVoice;}
  void retry(boolean camera){if(!pendingVoice.isEmpty())submit(pendingVoice,camera);}
  void submit(String text,boolean camera){
    if(text==null||text.trim().isEmpty())return;
    if(PromptDispatcher.isStop(text)){PromptDispatcher.cancelPending();runtime.send(PiRuntime.json("type","stop"));return;}
    pendingVoice=text;VoiceCallActivity.onPrompt(text);
    JSONObject command=PiRuntime.json("type","prompt","id",UUID.randomUUID().toString(),"text",text,"sessionId",runtime.sessionId);
    PromptDispatcher.Result result=(accepted,queued,error)->{
      if(accepted){pendingVoice="";if(queued)VoiceCallActivity.onControllerStatus("已排队，保留本次画面");}
      else{VoiceCallActivity.onSubmissionFailed(text,error);if(controller.sessionActive())controller.forceInterrupt();}
    };
    if(camera&&ScreenContext.enabled()&&!CameraCoordinator.get(context).enabledFor(runtime.sessionId)){
      ScreenContext.capture(context,(image,label,error)->{if(error!=null){result.done(false,false,error);return;}
        try{command.put("image",image);}catch(Exception impossible){result.done(false,false,impossible.getMessage());return;}
        PromptDispatcher.get(context).submit(command,false,result);
      });
    }else PromptDispatcher.get(context).submit(command,camera,result);
  }
  public void event(JSONObject event){
    switch(event.optString("type")){
      case "turn_start":controller.turnStart();break;
      case "assistant_start":controller.assistantStart();break;
      case "delta":controller.append(event.optString("text"));break;
      case "tool_execution":if("running".equals(event.optString("status")))controller.toolAnnounce(PhoneHud.action(event));break;
      case "turn_end":controller.turnEnded(event.optBoolean("aborted"));break;
      case "error":controller.error();break;
      case "session_changed":pendingVoice="";if(session)controller.stopSession(false);break;
    }
  }
}
