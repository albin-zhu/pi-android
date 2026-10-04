package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;

/** ADB-only UI check for the interruptible phone-control HUD. */
public final class HudInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){start();}
  @Override public void onStart(){
    try{
      JSONObject request=PiRuntime.json("type","tool_request","id","hud-test","name","phone_task",
        "args",PiRuntime.json("goal","Search 张三","allowedPackages",new org.json.JSONArray().put("com.tencent.mm")));
      runOnMainSync(()->PhoneHud.show(getTargetContext(),PhoneHud.action(request)));
      boolean shown=PhoneHud.showing();
      boolean voiceAction=PhoneHud.hasAction("语音接管当前任务");
      boolean stopAction=PhoneHud.hasAction("停止当前手机操作");
      JSONObject update=PiRuntime.json("type","tool_execution","id","hud-test","name","phone_task","status","running",
        "args",request.optJSONObject("args"),
        "output",PiRuntime.json("status","running","steps",new org.json.JSONArray().put(PiRuntime.json("kind","tap","label","Tap Search / 搜索"))));
      String label=PhoneHud.action(update);
      runOnMainSync(()->PhoneHud.update(getTargetContext(),label));
      boolean updated=PhoneHud.showing();
      runOnMainSync(PhoneHud::hide);
      boolean hidden=!PhoneHud.showing();
      JSONObject blocked=PiRuntime.json("type","tool_execution","id","hud-blocked","name","phone_task","status","running",
        "args",request.optJSONObject("args"),
        "output",PiRuntime.json("status","blocked","reason","low_confidence","steps",new org.json.JSONArray().put(PiRuntime.json("kind","tap","label","Tap Search / 搜索"))));
      String blockedLabel=PhoneHud.action(blocked);
      JSONObject verified=PiRuntime.json("type","tool_execution","id","hud-verified","name","phone_task","status","success",
        "args",request.optJSONObject("args"),"output",PiRuntime.json("status","verified"));
      String verifiedLabel=PhoneHud.action(verified);
      JSONObject failed=PiRuntime.json("type","tool_execution","id","hud-failed","name","screen_read","status","error");
      String failedLabel=PhoneHud.action(failed);
      boolean states=blockedLabel.contains("被阻止")&&blockedLabel.contains("目标置信度低")&&verifiedLabel.contains("已验证")&&failedLabel.contains("失败");
      runOnMainSync(()->PhoneHud.takeover(getTargetContext(),"已由你接管 · 任务正在停止"));
      boolean takeoverShown=PhoneHud.showing();
      runOnMainSync(PhoneHud::hide);
      boolean takeoverPreserved=PhoneHud.showing();
      runOnMainSync(PhoneHud::forceHide);
      boolean takeoverHidden=!PhoneHud.showing();
      Bundle result=new Bundle();result.putBoolean("shown",shown);result.putBoolean("updated",updated);result.putBoolean("hidden",hidden);result.putString("label",label);
      result.putString("blockedLabel",blockedLabel);result.putString("verifiedLabel",verifiedLabel);result.putString("failedLabel",failedLabel);result.putBoolean("states",states);
      result.putBoolean("voiceAction",voiceAction);result.putBoolean("stopAction",stopAction);
      result.putBoolean("takeoverShown",takeoverShown);result.putBoolean("takeoverPreserved",takeoverPreserved);result.putBoolean("takeoverHidden",takeoverHidden);
      finish(shown&&updated&&hidden&&voiceAction&&stopAction&&label.contains("点击")&&states&&takeoverShown&&takeoverPreserved&&takeoverHidden?-1:0,result);
    }catch(Throwable error){
      Bundle result=new Bundle();result.putString("error",error.toString());finish(0,result);
    }
  }
}
