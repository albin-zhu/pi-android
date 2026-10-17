package dev.zfold.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** One Android-owned phone controller, shared by embedded Pi and independent clients. */
final class PhoneToolDispatcher {
  interface ExternalOwner {
    boolean available();
    void takenOver();
  }
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Set<String> exposed=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
    "phone_capabilities","phone_call","camera_capture","contact_search","phone_sms","device_info",
    "screen_read","screen_windows","ui_system","screen_capture","ui_tap","ui_input","ui_swipe",
    "ui_back","app_open","open","app_list","split_screen")));
  private static final Map<String,Call> calls=new HashMap<>(); // UI-thread confined
  private static volatile boolean embeddedPhoneActive;
  private static int embeddedCalls;
  private static volatile Call external;

  static JSONArray tools(){return new JSONArray(exposed);}
  static boolean allowed(String name){return exposed.contains(name);}
  static boolean phoneBusy(){Call call=external;return embeddedPhoneActive||(call!=null&&call.controlStarted>0);}
  static boolean metadata(String name){return name.equals("device_info")||name.equals("phone_capabilities")||name.equals("app_list");}
  static boolean readOnly(String name,JSONObject args){
    return metadata(name)||name.equals("screen_read")||name.equals("screen_windows")||name.equals("contact_search")||
      (name.equals("screen_capture")&&!args.optBoolean("save"))||
      (name.equals("ui_system")&&args.optString("action","list").equals("list"))||
      (name.equals("split_screen")&&args.optString("action","state").equals("state"))||
      (name.equals("phone_sms")&&(args.optString("action").equals("status")||args.optString("action").equals("subscriptions")));
  }
  private static boolean screenAction(String name){
    return name.equals("screen_windows")||name.equals("ui_system")||name.equals("screen_read")||name.equals("screen_capture")||
      name.equals("ui_act")||name.equals("ui_tap")||name.equals("ui_input")||name.equals("ui_input_focused")||
      name.equals("ui_swipe")||name.equals("ui_back")||name.equals("app_open")||name.equals("split_screen")||name.equals("open");
  }
  private static boolean phoneTool(String name){return exposed.contains(name)||name.equals("phone_task")||name.equals("ui_act")||name.equals("ui_input_focused");}
  static void embedded(Context context,JSONObject request,BooleanSupplier stopped,AndroidTools.Result result,Runnable heartbeat){
    submit(new Call(context,request,null,stopped,result,heartbeat));
  }
  static void external(Context context,JSONObject request,ExternalOwner owner,BooleanSupplier cancelled,AndroidTools.Result result,Runnable heartbeat){
    submit(new Call(context,request,owner,cancelled,result,heartbeat));
  }
  private static void submit(Call call){
    if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->submit(call));return;}
    if(call.invalid()){call.finish(null,"操作已停止或连接已关闭");return;}
    if(calls.containsKey(call.id)){call.finish(null,"重复的工具调用 ID");return;}
    if(call.owner!=null){
      if(!allowed(call.name)){call.finish(null,"不支持的手机工具："+call.name);return;}
      if(external!=null||embeddedPhoneActive||embeddedCalls>0){call.finish(null,"手机正由另一个任务控制，请等待该任务结束");return;}
      external=call;
    }else if(phoneTool(call.name)&&external!=null){
      call.finish(null,"手机正由独立客户端控制，请先结束该请求");return;
    }
    calls.put(call.id,call);call.registered=true;
    if(call.owner!=null){
      // Liveness includes the visible consent wait; it is not permission to act.
      call.pulse=new Runnable(){public void run(){if(call.finished)return;if(call.heartbeat!=null)call.heartbeat.run();main.postDelayed(this,2000);}};
      call.pulse.run();
      if(!metadata(call.name)&&!PhoneToolPolicy.alwaysAllow(call.context)){
        PhoneToolApproval.request(call.context,call.id,call.name,call.request.optJSONObject("args"),readOnly(call.name,call.request.optJSONObject("args")),
          error->{if(error==null)call.start();else call.finish(null,error);});
        return;
      }
    }
    call.start();
  }
  static void cancel(String id){
    NativeToolExecution.cancel(id);
    main.post(()->{Call call=calls.get(id);if(call!=null)call.cancel();});
  }
  static void disconnect(ExternalOwner owner){main.post(()->{Call call=external;if(call!=null&&call.owner==owner)call.cancel();});}
  static void embeddedEnd(){
    if(Looper.myLooper()!=Looper.getMainLooper()){main.post(PhoneToolDispatcher::embeddedEnd);return;}
    embeddedPhoneActive=false;
    if(external==null)PhoneHud.hide();
  }
  static void embeddedProgress(Context context,JSONObject event){
    if(external==null&&!VoiceCallActivity.isForeground()&&!VoiceMiniOverlay.showing()&&
        (screenAction(event.optString("name"))||event.optString("name").equals("phone_task")))PhoneHud.show(context,PhoneHud.action(event));
  }
  static void embeddedStop(String turnId){
    NativeToolExecution.cancelTurn(turnId);
    main.post(()->{
      for(Call call:new ArrayList<>(calls.values()))if(call.owner==null&&turnId.equals(call.request.optString("turnId")))call.cancel();
      embeddedEnd();
    });
  }
  static boolean takeover(Context context,long touchTime){
    Call call=external;
    if(call==null||call.controlStarted==0||touchTime<call.controlStarted)return false;
    call.owner.takenOver();call.cancel();
    android.widget.Toast.makeText(context,"检测到用户接管手机，独立客户端操作已停止",android.widget.Toast.LENGTH_LONG).show();
    PhoneHud.takeover(context,"已由你接管 · 独立客户端已停止");return true;
  }
  static boolean embeddedActive(){return embeddedPhoneActive;}
  static boolean stopExternal(){
    Call call=external;if(call==null)return false;
    call.owner.takenOver();call.cancel();PhoneHud.hide();return true;
  }
  static void stopPhone(Context context){if(!stopExternal())PiRuntime.get(context).send(PiRuntime.json("type","stop"));}

  private static final class Call {
    final Context context;final JSONObject request;final String id,name;final ExternalOwner owner;
    final BooleanSupplier stopped;final AndroidTools.Result result;final Runnable heartbeat;
    boolean registered,finished,cancelled,executing,counted;Runnable pulse;
    long controlStarted;
    Call(Context context,JSONObject request,ExternalOwner owner,BooleanSupplier stopped,AndroidTools.Result result,Runnable heartbeat){
      this.context=context.getApplicationContext();this.request=request;id=request.optString("id");name=request.optString("name");
      this.owner=owner;this.stopped=stopped;this.result=result;this.heartbeat=heartbeat;
    }
    boolean invalid(){return cancelled||stopped.getAsBoolean()||(owner!=null&&!owner.available());}
    void start(){
      if(finished)return;
      if(invalid()){finish(null,"操作已停止或连接已关闭");return;}
      if(owner==null&&name.equals("ask_user_question")){embeddedPhoneActive=false;PhoneHud.hide();}
      if(owner==null&&(name.equals("phone_call")||name.equals("phone_sms")||name.equals("camera_capture")||name.equals("contact_search")))embeddedPhoneActive=false;
      boolean screen=screenAction(name),phoneTask=name.equals("phone_task");
      if(owner!=null&&screen)controlStarted=android.os.SystemClock.uptimeMillis();
      boolean callVisible=VoiceCallActivity.isForeground()||VoiceMiniOverlay.showing();
      if((screen||phoneTask)&&!callVisible)PhoneHud.show(context,PhoneHud.action(request));
      if(owner==null&&(screen||phoneTask))embeddedPhoneActive=true;
      if(owner==null&&phoneTool(name)){embeddedCalls++;counted=true;}
      boolean handoff=false;
      if(screen){
        handoff=AssistantSessionService.handoffToBubble(context);
        if(!handoff)handoff=AssistantOverlay.minimizeForPhoneControl(context);
        if(name.equals("app_open")&&!callVisible&&!SystemWindowNavigation.active())CircleOverlay.ensureBubble(context);
      }
      Runnable execute=()->{
        if(finished)return;
        if(invalid()){finish(null,"操作已停止或连接已关闭");return;}
        executing=true;
        AndroidTools.execute(context,request,(value,error)->main.post(()->finish(value,error)),owner==null?heartbeat:null);
      };
      // Allow consent/assistant windows to leave before observing or touching the target.
      if(handoff||(owner!=null&&!metadata(name)))main.postDelayed(execute,260);else execute.run();
    }
    void cancel(){
      if(finished)return;cancelled=true;
      if(executing)NativeToolExecution.cancel(id);else finish(null,"操作已停止");
    }
    void finish(JSONObject value,String error){
      if(finished)return;finished=true;
      PhoneToolApproval.cancel(id);
      if(pulse!=null)main.removeCallbacks(pulse);
      if(registered)calls.remove(id,this);
      if(counted)embeddedCalls--;
      if(external==this){external=null;PhoneHud.hide();}
      result.done(value,error);
    }
  }
}
